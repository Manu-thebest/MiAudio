package cat.manu.miaudio

import android.util.Log
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Envoltorio de NewPipeExtractor: busqueda paginada en YouTube (igual que la web,
 * de 20 en 20), extraccion de listas completas, recomendados y URL de audio.
 */
object Yt {

    private const val TAG = "MiAudio/Yt"
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    const val VIDEOS = "videos"
    const val LISTAS = "playlists"

    @Volatile
    private var inited = false

    /** videoId -> (url de audio, caducidad en ms) */
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()

    data class ListaYt(
        val titulo: String,
        val autor: String,
        val url: String,
        val n: Long
    )

    data class Pagina(val tracks: List<Track>, val listas: List<ListaYt>, val siguiente: Int?)

    // ------------------------------------------------------------------ red

    private class Descargador : Downloader() {
        override fun execute(request: Request): Response {
            val conn = URL(request.url()).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.requestMethod = request.httpMethod()

                val cabeceras = HashMap<String, List<String>>()
                request.headers().forEach { (k, v) -> cabeceras[k] = v }
                if (request.url().contains("youtube.com") && !cabeceras.containsKey("Cookie")) {
                    val cookie = runCatching { YoutubeParsingHelper.generateConsentCookie() }.getOrNull()
                    if (!cookie.isNullOrEmpty()) cabeceras["Cookie"] = listOf(cookie)
                }
                cabeceras.forEach { (k, vs) ->
                    vs.forEach { v -> runCatching { conn.setRequestProperty(k, v) } }
                }

                val datos = request.dataToSend()
                if (datos != null && datos.isNotEmpty()) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(datos) }
                }

                val codigo = conn.responseCode
                val cuerpo = runCatching {
                    val flujo = if (codigo in 200..299) conn.inputStream else conn.errorStream
                    flujo?.use { it.bufferedReader(Charsets.UTF_8).readText() } ?: ""
                }.getOrDefault("")

                val cab = HashMap<String, List<String>>()
                conn.headerFields?.forEach { (k, v) ->
                    if (k != null && v != null) cab[k] = v
                }
                return Response(codigo, conn.responseMessage ?: "", cab, cuerpo, conn.url.toString())
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    private fun asegurarInit() {
        if (!inited) {
            synchronized(this) {
                if (!inited) {
                    NewPipe.init(Descargador(), Localization("es", "ES"))
                    inited = true
                }
            }
        }
    }

    // ------------------------------------------------------------ busqueda paginada

    /** Una busqueda viva: recuerda el extractor y las paginas ya descargadas. */
    private class Sesion(val consulta: String, val tipo: String) {
        val extractor: SearchExtractor by lazy {
            ServiceList.YouTube.getSearchExtractor(consulta, listOf(tipo), "")
        }
        var arrancada = false
        var siguiente: Page? = null
        var agotada = false
        val paginas = ArrayList<Pair<List<Track>, List<ListaYt>>>()
    }

    private val sesiones = object : LinkedHashMap<String, Sesion>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Sesion>?): Boolean =
            size > 8
    }

    /**
     * Devuelve la pagina [indice] (0 = primera, 20 resultados por pagina) de la busqueda.
     * Es la misma paginacion que usa la app y la que se sirve a Android Auto.
     */
    @Synchronized
    fun pagina(consulta: String, tipo: String, indice: Int): Pagina {
        asegurarInit()
        val clave = "$tipo\u0001${consulta.trim().lowercase()}"
        val sesion = sesiones.getOrPut(clave) { Sesion(consulta.trim(), tipo) }

        var guarda = 0
        while (sesion.paginas.size <= indice && !sesion.agotada) {
            if (guarda++ > 12) break
            val trozo = cargarSiguiente(sesion)
            if (trozo == null) {
                sesion.agotada = true
                break
            }
            sesion.paginas.add(trozo)
        }

        val actual = sesion.paginas.getOrNull(indice)
        val hayMas = indice + 1 < sesion.paginas.size || !sesion.agotada
        return Pagina(
            tracks = actual?.first ?: emptyList(),
            listas = actual?.second ?: emptyList(),
            siguiente = if (hayMas) indice + 1 else null
        )
    }

    private fun cargarSiguiente(s: Sesion): Pair<List<Track>, List<ListaYt>>? {
        val pagina: ListExtractor.InfoItemsPage<*> = try {
            if (!s.arrancada) {
                s.extractor.fetchPage()
                s.arrancada = true
                s.extractor.initialPage
            } else {
                val p = s.siguiente ?: return null
                s.extractor.getPage(p)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pagina fallida (${s.tipo} '${s.consulta}'): ${t.message}")
            return null
        }

        s.siguiente = pagina.nextPage
        if (!pagina.hasNextPage()) s.agotada = true

        val tracks = ArrayList<Track>()
        val listas = ArrayList<ListaYt>()
        for (item in pagina.items) {
            when (item) {
                is StreamInfoItem -> {
                    val id = idDeVideo(item.url) ?: continue
                    tracks.add(
                        Track.yt(
                            videoId = id,
                            title = item.name ?: "(sin titulo)",
                            author = item.uploaderName ?: "YouTube",
                            durMs = if (item.duration > 0) item.duration * 1000L else 0L
                        )
                    )
                }
                is PlaylistInfoItem -> listas.add(
                    ListaYt(
                        titulo = item.name ?: "(lista)",
                        autor = item.uploaderName ?: "YouTube",
                        url = item.url ?: continue,
                        n = item.streamCount
                    )
                )
            }
        }
        Log.i(TAG, "pagina ${s.paginas.size} '${s.consulta}' (${s.tipo}): ${tracks.size} videos, ${listas.size} listas")
        return tracks to listas
    }

    /** Atajo: los primeros [minimo] resultados (para la busqueda normal de la app). */
    fun hasta(consulta: String, tipo: String, minimo: Int): Pagina {
        var p = pagina(consulta, tipo, 0)
        val tracks = ArrayList(p.tracks)
        val listas = ArrayList(p.listas)
        var sig = p.siguiente
        while ((tracks.size + listas.size) < minimo && sig != null) {
            val siguiente = pagina(consulta, tipo, sig)
            if (siguiente.tracks.isEmpty() && siguiente.listas.isEmpty()) break
            tracks.addAll(siguiente.tracks)
            listas.addAll(siguiente.listas)
            sig = siguiente.siguiente
        }
        return Pagina(tracks, listas, sig)
    }

    /** Cierra la sesion de busqueda cuando el usuario sale de la pantalla. */
    @Synchronized
    fun cerrarBusqueda(consulta: String) {
        sesiones.keys.removeAll { it.endsWith("\u0001${consulta.trim().lowercase()}") }
    }

    // ------------------------------------------------------------ listas de YouTube

    /** Extrae una lista de YouTube ENTERA (album completo, playlist de canal, etc). */
    fun videosDeLista(url: String, tope: Int = 200): Pair<String, List<Track>> {
        asegurarInit()
        val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
        extractor.fetchPage()
        val nombre = runCatching { extractor.name }.getOrNull() ?: "Lista de YouTube"

        val salida = ArrayList<Track>()
        var pagina = runCatching { extractor.initialPage }.getOrNull()
        var vueltas = 0
        while (pagina != null && vueltas++ < 20) {
            for (item in pagina.items) {
                if (item is StreamInfoItem) {
                    val id = idDeVideo(item.url) ?: continue
                    salida.add(
                        Track.yt(
                            videoId = id,
                            title = item.name ?: "(sin titulo)",
                            author = item.uploaderName ?: nombre,
                            durMs = if (item.duration > 0) item.duration * 1000L else 0L,
                            grupo = nombre
                        )
                    )
                }
                if (salida.size >= tope) break
            }
            if (salida.size >= tope || !pagina.hasNextPage()) break
            pagina = runCatching { extractor.getPage(pagina.nextPage) }.getOrNull()
        }
        Log.i(TAG, "lista '$nombre': ${salida.size} videos")
        return nombre to salida
    }

    // ------------------------------------------------------------ recomendados

    /** Videos relacionados (lo que YouTube pone al lado). Se usa al acabar una lista. */
    fun relacionados(videoId: String, limite: Int = 20): List<Track> {
        asegurarInit()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val salida = ArrayList<Track>()
        val rel = info.relatedItems ?: return emptyList()
        for (item in rel) {
            if (item is StreamInfoItem) {
                val id = idDeVideo(item.url) ?: continue
                if (id == videoId) continue
                salida.add(
                    Track.yt(
                        videoId = id,
                        title = item.name ?: "(sin titulo)",
                        author = item.uploaderName ?: "YouTube",
                        durMs = if (item.duration > 0) item.duration * 1000L else 0L
                    )
                )
                if (salida.size >= limite) break
            }
        }
        Log.i(TAG, "relacionados de $videoId: ${salida.size}")
        return salida
    }

    // ------------------------------------------------------------ audio

    /** Resuelve la URL de audio de un video (cacheada 30 min). */
    fun urlAudio(videoId: String): String {
        asegurarInit()
        val ahora = System.currentTimeMillis()
        cache[videoId]?.let { (url, caduca) -> if (caduca > ahora) return url }

        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val pistas = info.audioStreams.filter { !it.url.isNullOrEmpty() }
        if (pistas.isEmpty()) throw IOException("Ese video no tiene audio disponible")

        val m4a = pistas
            .filter { it.format?.name?.contains("M4A", true) == true }
            .maxByOrNull { it.averageBitrate }
        val elegida = m4a ?: pistas.maxByOrNull { it.averageBitrate }
        if (elegida == null) throw IOException("Sin pista de audio")
        val url = elegida.url ?: throw IOException("Sin URL de audio")

        cache[videoId] = url to (ahora + 30 * 60 * 1000L)
        Log.i(TAG, "audio $videoId -> ${elegida.format?.name} ${elegida.averageBitrate}k")
        return url
    }

    fun olvidarCache() {
        cache.clear()
        synchronized(this) { sesiones.clear() }
    }

    fun idDeVideo(url: String?): String? {
        if (url == null) return null
        val m = Regex("[?&]v=([A-Za-z0-9_-]{6,})").find(url)
        if (m != null) return m.groupValues[1]
        val m2 = Regex("(?:youtu\\.be/|/shorts/|/live/)([A-Za-z0-9_-]{6,})").find(url)
        return m2?.groupValues?.get(1)
    }
}
