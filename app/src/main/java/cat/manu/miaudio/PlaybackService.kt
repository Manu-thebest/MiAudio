package cat.manu.miaudio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Servicio de reproduccion y biblioteca. Es lo que ve Android Auto:
 * expone Locales, Listas y YouTube (busqueda por voz y paginada).
 */
class PlaybackService : MediaLibraryService() {

    private lateinit var reproductor: ExoPlayer
    private lateinit var sesion: MediaLibrarySession
    private val io = Executors.newFixedThreadPool(2)
    private val principal = Handler(Looper.getMainLooper())

    private var radioEnMarcha = false

    // seguimiento de la pista que esta sonando (para marcas y reanudacion)
    private var sonandoId = ""
    private var sonandoDur = 0L
    private var sonandoPos = 0L
    private var arranqueCocheHecho = false

    private val reloj = object : Runnable {
        override fun run() {
            runCatching {
                val item = reproductor.currentMediaItem
                if (item != null && reproductor.isPlaying) {
                    val dur = reproductor.duration
                    sonandoId = item.mediaId
                    if (dur > 0) sonandoDur = dur
                    else if (sonandoDur <= 0) sonandoDur = item.mediaMetadata.durationMs ?: 0L
                    sonandoPos = reproductor.currentPosition
                    if (sonandoPos > 3000L) Estado.guardarProgreso(sonandoId, sonandoPos)
                }
            }
            principal.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Lib.init(this)
        AudioCache.init(this)
        Lib.refrescarLocal(this)

        // Fuente de datos: las pistas "yt:<id>" se resuelven a la URL real justo antes de abrirse.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Yt.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(30000)

        val base = DefaultDataSource.Factory(this, http)

        // "yt:<id>": si el audio esta descargado se usa el fichero local (sin datos),
        // si no, se resuelve la URL real de YouTube en ese momento.
        val resolutor = ResolvingDataSource.Factory(base) { spec ->
            if (spec.uri.scheme == "yt") {
                val videoId = spec.uri.schemeSpecificPart
                val cacheado = AudioCache.fichero(videoId)
                if (cacheado != null && AudioCache.tiene(videoId)) {
                    spec.buildUpon().setUri(Uri.fromFile(cacheado)).build()
                } else {
                    val url = Yt.urlAudio(videoId)
                    spec.buildUpon().setUri(Uri.parse(url)).build()
                }
            } else {
                spec
            }
        }

        reproductor = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolutor))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        reproductor.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                cerrarAnterior()
                recordar()
                apuntarLista()
                precachear(1)
                precachear(2)
            }

            override fun onPlaybackStateChanged(estado: Int) {
                if (estado == Player.STATE_ENDED) {
                    // Sonó hasta el final: queda marcada como escuchada aunque el
                    // reloj hubiera cerrado la pista un momento antes.
                    reproductor.currentMediaItem?.let {
                        Estado.marcarEscuchado(it.mediaId)
                        Estado.guardarProgreso(it.mediaId, 0L)
                    }
                    cerrarAnterior(terminada = true)
                    radio()
                }
            }

            override fun onIsPlayingChanged(esta: Boolean) {
                if (esta) precachear(1) else cerrarAnterior(terminada = false)
            }
        })

        principal.postDelayed(reloj, 5000)

        sesion = MediaLibrarySession.Builder(this, reproductor, Callback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = sesion

    override fun onDestroy() {
        principal.removeCallbacksAndMessages(null)
        runCatching { cerrarAnterior(terminada = false) }
        runCatching { sesion.release() }
        runCatching { reproductor.release() }
        runCatching { io.shutdownNow() }
        super.onDestroy()
    }

    // ----------------------------------------------------- seguir con recomendados

    /**
     * Cuando la cola se acaba, si el ultimo era de YouTube se anaden sus
     * recomendados y sigue sonando (como la radio de Spotify).
     */
    private fun radio() {
        if (radioEnMarcha) return
        if (!Estado.seguirConRecomendados) return
        val ultimo = reproductor.currentMediaItem ?: return
        if (!ultimo.mediaId.startsWith(Ids.YTVID)) return
        val videoId = ultimo.mediaId.removePrefix(Ids.YTVID)
        if (videoId.isBlank()) return

        radioEnMarcha = true
        io.execute {
            val siguientes = runCatching { Yt.relacionados(videoId, 12) }.getOrDefault(emptyList())
            principal.post {
                radioEnMarcha = false
                if (siguientes.isEmpty()) return@post
                val yaEstan = HashSet<String>()
                for (i in 0 until reproductor.mediaItemCount) yaEstan.add(reproductor.getMediaItemAt(i).mediaId)
                val nuevos = siguientes.filter { it.mediaId() !in yaEstan }.map { it.toMediaItem() }
                if (nuevos.isEmpty()) return@post
                Log.i("MiAudio", "radio: +${nuevos.size} recomendados tras $videoId")
                reproductor.addMediaItems(nuevos)
                reproductor.prepare()
                reproductor.play()
            }
        }
    }

    // ------------------------------------------------- marcas, podcasts y cache

    /**
     * Cierra la pista que estaba sonando: guarda por donde iba y, si llego
     * al final (85 %), la marca como escuchada.
     */
    private fun cerrarAnterior(terminada: Boolean = false) {
        val id = sonandoId
        if (id.isBlank()) return
        val dur = sonandoDur
        val pos = sonandoPos
        val completa = terminada || (dur > 0 && pos >= (dur * 0.85).toLong())
        if (completa) {
            Estado.marcarEscuchado(id)
            Estado.guardarProgreso(id, 0L)
        } else if (pos > 5000L) {
            Estado.guardarProgreso(id, pos)
        }
        sonandoId = ""
        sonandoPos = 0L
        sonandoDur = 0L
    }

    /** Guarda por que capitulo va la lista de la que salio la cola (podcasts). */
    private fun apuntarLista() {
        val origen = Estado.colaOrigen
        if (origen.isBlank()) return
        Lib.store.fijarIndice(origen, reproductor.currentMediaItemIndex)
    }

    /** Descarga por delante las siguientes pistas de YouTube (para ir sin cobertura). */
    private fun precachear(saltos: Int) {
        if (!Estado.cacheOffline) return
        val objetivo = reproductor.currentMediaItemIndex + saltos
        if (objetivo !in 0 until reproductor.mediaItemCount) return
        val item = reproductor.getMediaItemAt(objetivo)
        if (!item.mediaId.startsWith(Ids.YTVID)) return
        val videoId = item.mediaId.removePrefix(Ids.YTVID)
        if (videoId.isBlank() || AudioCache.tiene(videoId)) return
        io.execute {
            val url = runCatching { Yt.urlAudio(videoId) }.getOrNull() ?: return@execute
            runCatching { AudioCache.guardar(videoId, url) }
        }
    }

    /**
     * Arranca la cola guardada por el ultimo elemento que sonaba.
     * Se llama cuando Android Auto se conecta y pide la biblioteca.
     */
    private fun autoArranqueCoche() {
        if (arranqueCocheHecho) return
        arranqueCocheHecho = true
        if (!Estado.autoAlConectar) return
        if (reproductor.mediaItemCount > 0) return
        principal.post { arrancarCola(Estado.cola()) }
    }

    /** Carga la cola guardada y empieza a sonar donde se quedo. */
    private fun arrancarCola(guardada: Pair<List<Track>, Int>?): Boolean {
        if (guardada == null) return false
        val pistas = guardada.first
        if (pistas.isEmpty()) return false
        if (reproductor.mediaItemCount > 0) return false
        val indice = guardada.second.coerceIn(0, pistas.size - 1)

        // La pista de arranque se reanuda con setMediaItems(posicion);
        // las demas llevan su propio recorte si quedaron a medias.
        val items = pistas.mapIndexed { i, t ->
            val inicio = if (i == indice) 0L else Estado.progreso(t.mediaId())
            t.toMediaItem(inicio)
        }
        val desde = Estado.progreso(pistas[indice].mediaId())
        Log.i("MiAudio", "reanudo en ${pistas[indice].title} ($desde ms)")
        reproductor.setMediaItems(items, indice, desde)
        reproductor.prepare()
        reproductor.play()
        return true
    }

    /** Recuerda la cola para el "Seguir escuchando" de la pantalla de inicio. */
    private fun recordar() {
        val n = reproductor.mediaItemCount
        if (n == 0) return
        val pistas = ArrayList<Track>()
        for (i in 0 until minOf(n, 300)) pistas.add(aTrack(reproductor.getMediaItemAt(i)))
        Estado.guardarCola(pistas, reproductor.currentMediaItemIndex)
        val actual = reproductor.currentMediaItem?.mediaId ?: ""
        if (actual.startsWith(Ids.YTVID)) Estado.ultimoYt = actual.removePrefix(Ids.YTVID)
    }

    private fun aTrack(item: MediaItem): Track {
        val mid = item.mediaId
        val esLocal = mid.startsWith(Ids.LOCAL)
        val id = if (esLocal) mid.removePrefix(Ids.LOCAL) else mid.removePrefix(Ids.YTVID)
        val md = item.mediaMetadata
        return Track(
            kind = if (esLocal) "local" else "yt",
            id = id,
            title = md.title?.toString() ?: "",
            subtitle = md.artist?.toString() ?: "",
            durMs = md.durationMs ?: 0L,
            art = md.artworkUri?.toString() ?: ""
        )
    }

    // ---------------------------------------------------------------- biblioteca

    private inner class Callback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Android Auto pide la raiz al conectar: seguir donde lo dejo.
            autoArranqueCoche()
            return Futures.immediateFuture(LibraryResult.ofItem(raiz(), params))
        }

        /** Reanudacion del sistema/coche desde el ultimo elemento. */
        override fun onPlaybackResumption(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val guardada = Estado.cola()
            if (guardada == null || guardada.first.isEmpty()) {
                val vacio = SettableFuture.create<MediaItemsWithStartPosition?>()
                vacio.set(null)
                @Suppress("UNCHECKED_CAST")
                return vacio as ListenableFuture<MediaItemsWithStartPosition>
            }
            val pistas = guardada.first
            val indice = guardada.second.coerceIn(0, pistas.size - 1)
            val items = pistas.mapIndexed { i, t ->
                val inicio = if (i == indice) 0L else Estado.progreso(t.mediaId())
                t.toMediaItem(inicio)
            }
            val desde = Estado.progreso(pistas[indice].mediaId())
            return Futures.immediateFuture(
                MediaItemsWithStartPosition(items, indice, desde)
            )
        }

        /** Si se elige una pista a medias (podcast), se reanuda por donde iba. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaItemsWithStartPosition> {
            var desde = startPositionMs
            if (desde == C.TIME_UNSET && startIndex in mediaItems.indices) {
                desde = Estado.progreso(mediaItems[startIndex].mediaId)
            }
            return Futures.immediateFuture(
                MediaItemsWithStartPosition(mediaItems, startIndex, desde)
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = elemento(mediaId)
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = asincrono {
            LibraryResult.ofItemList(hijos(parentId, page, pageSize), params)
        }

        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            if (parentId == Ids.LOCALES) Lib.refrescarLocal(this@PlaybackService)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onUnsubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String
        ): ListenableFuture<LibraryResult<Void>> =
            Futures.immediateFuture(LibraryResult.ofVoid())

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            if (query.isNotBlank()) Estado.guardarReciente(query)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        /** Resultados de busqueda paginados (la misma paginacion que la app). */
        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = asincrono {
            LibraryResult.ofItemList(resultadosPagina(query, page, pageSize), params)
        }
    }

    // ---------------------------------------------------------------- ayudantes

    private fun <T : Any> asincrono(bloque: () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val futuro = SettableFuture.create<LibraryResult<T>>()
        io.execute {
            try {
                futuro.set(bloque())
            } catch (t: Throwable) {
                futuro.set(LibraryResult.ofError<T>(LibraryResult.RESULT_ERROR_UNKNOWN))
            }
        }
        return futuro
    }

    private fun raiz(): MediaItem = carpeta(Ids.ROOT, getString(R.string.app_name), "Tus audios, listas y YouTube")

    private fun carpeta(mediaId: String, titulo: String, subtitulo: String = ""): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(titulo)
            .setIsBrowsable(true)
            .setIsPlayable(false)
        if (subtitulo.isNotEmpty()) meta.setArtist(subtitulo)
        return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(meta.build()).build()
    }

    private fun hijos(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val desde = page * pageSize
        val hasta = desde + pageSize
        return when {
            parentId == Ids.ROOT -> listOf(
                carpeta(Ids.LOCALES, getString(R.string.locales), "${Lib.localTracks.size} audios"),
                carpeta(Ids.LISTAS, getString(R.string.listas), "${Lib.store.listas.size} listas"),
                carpeta(Ids.YT, getString(R.string.youtube), "Busca en YouTube")
            )

            parentId == Ids.LOCALES -> Lib.localTracks
                .drop(desde).take(pageSize).map { it.toMediaItem() }

            parentId == Ids.LISTAS -> Lib.store.listas.drop(desde).take(pageSize).mapIndexed { i, l ->
                val va = if (l.indice > 0 && l.indice < l.pistas.size) " - va por ${l.indice + 1}" else ""
                carpeta(Ids.PL + (desde + i), l.nombre, "${l.pistas.size} pistas$va")
            }

            parentId.startsWith(Ids.PL) -> {
                val indice = parentId.removePrefix(Ids.PL).toIntOrNull()
                val lista = indice?.let { Lib.store.listas.getOrNull(it) }
                lista?.pistas?.drop(desde)?.take(pageSize)?.map { it.toMediaItem() } ?: emptyList()
            }

            parentId == Ids.YT -> Estado.recientes().drop(desde).take(pageSize).map {
                carpeta(Ids.YTQ + it, it, "Buscar en YouTube")
            }

            parentId.startsWith(Ids.YTQ) -> resultadosPagina(parentId.removePrefix(Ids.YTQ), page, pageSize)

            parentId.startsWith(Ids.YTPL) -> {
                val url = runCatching {
                    URLDecoder.decode(parentId.removePrefix(Ids.YTPL), "UTF-8")
                }.getOrDefault("")
                if (url.isBlank()) emptyList()
                else runCatching {
                    Yt.videosDeLista(url).second.drop(desde).take(pageSize).map { it.toMediaItem() }
                }.getOrDefault(emptyList())
            }

            else -> resultadosPagina(parentId, page, pageSize)
        }
    }

    /** Pagina de resultados tal como la pide el cliente (coche o app). */
    private fun resultadosPagina(consulta: String, page: Int, pageSize: Int): List<MediaItem> {
        val q = consulta.trim()
        if (q.isEmpty()) return emptyList()
        val desde = page * pageSize
        val hasta = desde + pageSize

        val videos = ArrayList<Track>()
        val listas = ArrayList<Yt.ListaYt>()
        var indice = 0
        var siguiente: Int? = 0
        while ((videos.size + listas.size) < hasta && siguiente != null && indice < 12) {
            val p = runCatching { Yt.pagina(q, Yt.VIDEOS, indice) }.getOrNull() ?: break
            if (p.tracks.isEmpty() && p.listas.isEmpty()) break
            videos.addAll(p.tracks)
            siguiente = p.siguiente
            indice++
        }
        if (videos.isNotEmpty()) Estado.guardarReciente(q)

        val salida = ArrayList<MediaItem>()
        videos.drop(desde).take(pageSize).forEach { salida.add(it.toMediaItem()) }

        // Listas de YouTube al final, como carpetas navegables.
        if (page == 0) {
            runCatching { Yt.pagina(q, Yt.LISTAS, 0) }.getOrNull()?.listas?.take(6)?.forEach { l ->
                salida.add(
                    carpeta(
                        Ids.YTPL + URLEncoder.encode(l.url, "UTF-8"),
                        l.titulo,
                        "${l.n} videos - ${l.autor}"
                    )
                )
            }
        }
        return salida
    }

    private fun elemento(mediaId: String): MediaItem? = when {
        mediaId == Ids.ROOT -> raiz()
        mediaId == Ids.LOCALES || mediaId == Ids.LISTAS || mediaId == Ids.YT -> carpeta(mediaId, mediaId)
        mediaId.startsWith(Ids.PL) || mediaId.startsWith(Ids.YTQ) || mediaId.startsWith(Ids.YTPL) ->
            carpeta(mediaId, mediaId)
        mediaId.startsWith(Ids.LOCAL) -> {
            val id = mediaId.removePrefix(Ids.LOCAL)
            Lib.localTracks.firstOrNull { it.id == id }?.toMediaItem()
        }
        mediaId.startsWith(Ids.YTVID) -> {
            val videoId = mediaId.removePrefix(Ids.YTVID)
            Track.yt(videoId, "YouTube", "", 0L).toMediaItem()
        }
        else -> carpeta(mediaId, mediaId)
    }
}
