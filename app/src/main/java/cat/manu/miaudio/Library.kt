package cat.manu.miaudio

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Listas de reproduccion guardadas en el almacenamiento interno de la app. */
class PlaylistStore(private val file: File) {

    class Lista(val nombre: String, val pistas: MutableList<Track> = mutableListOf()) {
        /** Indice de la pista por la que va (para seguir una serie de podcasts). */
        var indice: Int = 0
    }

    val listas = ArrayList<Lista>()

    @Synchronized
    fun load() {
        listas.clear()
        runCatching {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("listas") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val l = Lista(o.optString("nombre"))
                l.indice = o.optInt("indice", 0)
                val ps = o.optJSONArray("pistas") ?: JSONArray()
                for (j in 0 until ps.length()) {
                    l.pistas.add(Track.deJson(ps.getJSONObject(j)))
                }
                listas.add(l)
            }
        }
    }

    @Synchronized
    fun save() {
        runCatching {
            val arr = JSONArray()
            for (l in listas) {
                val o = JSONObject()
                o.put("nombre", l.nombre)
                o.put("indice", l.indice)
                val ps = JSONArray()
                for (t in l.pistas) ps.put(t.aJson())
                o.put("pistas", ps)
                arr.put(o)
            }
            val root = JSONObject()
            root.put("listas", arr)
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
        }
    }

    @Synchronized
    fun crear(nombre: String): Lista {
        val l = Lista(nombre.trim())
        listas.add(l)
        save()
        return l
    }

    @Synchronized
    fun buscar(nombre: String): Lista? = listas.firstOrNull { it.nombre == nombre }

    @Synchronized
    fun anadir(nombre: String, t: Track, grupo: String? = null): Int {
        val l = buscar(nombre) ?: crear(nombre)
        l.pistas.add(if (grupo != null) t.copy(grupo = grupo) else t)
        save()
        return l.pistas.size
    }

    /** Guarda un album/lista de YouTube entero dentro de una lista propia. */
    @Synchronized
    fun anadirGrupo(nombre: String, grupo: String, tracks: List<Track>): Int {
        val l = buscar(nombre) ?: crear(nombre)
        val yaEsta = l.pistas.count { it.grupo == grupo }
        if (yaEsta >= tracks.size && tracks.isNotEmpty()) return l.pistas.size
        for (t in tracks) l.pistas.add(t.copy(grupo = grupo))
        save()
        return l.pistas.size
    }

    @Synchronized
    fun quitar(nombre: String, indice: Int) {
        val l = buscar(nombre) ?: return
        if (indice in l.pistas.indices) {
            l.pistas.removeAt(indice)
            save()
        }
    }

    /** Apunta por donde va la lista (podcast: capitulo actual). */
    @Synchronized
    fun fijarIndice(nombre: String, indice: Int) {
        val l = buscar(nombre) ?: return
        val nuevo = indice.coerceIn(0, (l.pistas.size - 1).coerceAtLeast(0))
        if (l.indice != nuevo) {
            l.indice = nuevo
            save()
        }
    }

    /** Posicion de una pista dentro de la lista (-1 si no esta). */
    @Synchronized
    fun posicion(nombre: String, t: Track): Int {
        val l = buscar(nombre) ?: return -1
        return l.pistas.indexOfFirst { it.kind == t.kind && it.id == t.id }
    }

    /** Mueve una pista de sitio dentro de la lista (reordenar). */
    @Synchronized
    fun mover(nombre: String, desde: Int, hasta: Int) {
        val l = buscar(nombre) ?: return
        if (desde !in l.pistas.indices) return
        val destino = hasta.coerceIn(0, l.pistas.size - 1)
        if (destino == desde) return
        val t = l.pistas.removeAt(desde)
        l.pistas.add(destino, t)
        save()
    }

    @Synchronized
    fun moverAlFinal(nombre: String, indice: Int) {
        val l = buscar(nombre) ?: return
        if (indice !in l.pistas.indices) return
        val t = l.pistas.removeAt(indice)
        l.pistas.add(t)
        save()
    }

    @Synchronized
    fun borrarLista(nombre: String) {
        listas.removeAll { it.nombre == nombre }
        save()
    }
}

/** Estado compartido del proceso: listas + audio local escaneado. */
object Lib {
    lateinit var store: PlaylistStore

    @Volatile
    var localTracks: List<Track> = emptyList()
        private set

    /** Los mismos audios agrupados por carpeta: "Memoria interna/Music/...". */
    @Volatile
    var localCarpetas: Map<String, List<Track>> = emptyMap()
        private set

    @Volatile
    var permisoAudio: Boolean = false
        private set

    fun init(ctx: Context) {
        if (!::store.isInitialized) {
            store = PlaylistStore(File(ctx.filesDir, "listas.json")).also { it.load() }
        }
        Estado.init(ctx)
    }

    fun refrescarLocal(ctx: Context): List<Track> {
        val t = if (tienePermiso(ctx)) scanLocal(ctx) else emptyList()
        localTracks = t
        localCarpetas = t.groupBy { it.carpeta.ifBlank { "Sin carpeta" } }
            .toList()
            .sortedBy { it.first.lowercase() }
            .toMap()
        permisoAudio = tienePermiso(ctx)
        return t
    }

    /** Canciones de las listas que ya no estan en el movil (borradas o memoria quitada). */
    private fun indicesRotos(): List<Pair<String, Int>> {
        if (localTracks.isEmpty()) return emptyList()   // sin permiso o sin audio: no se toca nada
        val vivos = localTracks.map { it.id }.toHashSet()
        val out = ArrayList<Pair<String, Int>>()
        for (l in store.listas) {
            l.pistas.forEachIndexed { i, t ->
                if (t.kind == "local" && t.uriPropia.isNotBlank() && t.id.isNotBlank() &&
                    !vivos.contains(t.id)
                ) out.add(l.nombre to i)
            }
        }
        return out
    }

    /** Cuenta sin volver a escanear (usa la ultima revision del movil). */
    fun contarRotasRapido(): Int = indicesRotos().size

    fun contarRotas(ctx: Context): Int {
        if (!tienePermiso(ctx)) return 0
        refrescarLocal(ctx)
        return indicesRotos().size
    }

    /** Quita de las listas las canciones que ya no se encuentran. Devuelve cuantas. */
    fun limpiarRotas(ctx: Context): Int {
        if (!tienePermiso(ctx)) return 0
        refrescarLocal(ctx)
        val rotas = indicesRotos()
        if (rotas.isEmpty()) return 0
        for ((nombre, indices) in rotas.groupBy({ it.first }, { it.second })) {
            val l = store.buscar(nombre) ?: continue
            for (i in indices.sortedDescending()) if (i in l.pistas.indices) l.pistas.removeAt(i)
            l.indice = l.indice.coerceIn(0, (l.pistas.size - 1).coerceAtLeast(0))
        }
        store.save()
        return rotas.size
    }

    /** Audios de una carpeta (para el coche y para la pantalla de carpetas). */
    fun pistasDeCarpeta(nombre: String): List<Track> = localCarpetas[nombre] ?: emptyList()

    private fun tienePermiso(ctx: Context): Boolean {
        val p = if (android.os.Build.VERSION.SDK_INT >= 33)
            android.Manifest.permission.READ_MEDIA_AUDIO
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        return ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Almacenamientos con audio: (volumen de MediaStore, nombre en pantalla). */
    private fun volumenes(ctx: Context): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
            val nombres = runCatching { MediaStore.getExternalVolumeNames(ctx) }
                .getOrDefault(setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY))
            for (v in nombres.sorted()) {
                val etiqueta = if (v == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                    "Memoria interna"
                } else {
                    val d = runCatching {
                        sm?.getStorageVolume(File("/storage/$v"))?.getDescription(ctx)
                    }.getOrNull()
                    d?.takeIf { it.isNotBlank() } ?: "Memoria externa"
                }
                out.add(v to etiqueta)
            }
        }
        if (out.isEmpty()) out.add("external" to "Memoria interna")
        return out
    }

    /**
     * Recorre todos los almacenamientos (interna, tarjeta SD y USB) y devuelve
     * los audios con su URI real y su carpeta, para poder hacer listas con ellos.
     */
    private fun scanLocal(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val nuevaApi = android.os.Build.VERSION.SDK_INT >= 29
        val proj = ArrayList<String>().apply {
            add(MediaStore.Audio.Media._ID)          // 0
            add(MediaStore.Audio.Media.TITLE)        // 1
            add(MediaStore.Audio.Media.ARTIST)       // 2
            add(MediaStore.Audio.Media.DURATION)     // 3
            add(MediaStore.Audio.Media.DISPLAY_NAME) // 4
            add(MediaStore.Audio.Media.ALBUM_ID)     // 5
            add(MediaStore.Audio.Media.ALBUM)        // 6
            if (nuevaApi) add(MediaStore.Audio.Media.RELATIVE_PATH) // 7
            else add(MediaStore.Audio.Media.DATA)                   // 7
        }.toTypedArray()
        val sel = "${MediaStore.Audio.Media.DURATION} >= 20000"
        val orden = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        for ((volumen, etiqueta) in volumenes(ctx)) {
            val coleccion = if (nuevaApi) MediaStore.Audio.Media.getContentUri(volumen)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            runCatching {
                ctx.contentResolver.query(coleccion, proj, sel, null, orden)?.use { c ->
                    while (c.moveToNext()) {
                        val idReal = c.getLong(0)
                        val nombre = c.getString(1) ?: c.getString(4) ?: "(sin titulo)"
                        val artista = c.getString(2) ?: ""
                        val dur = c.getLong(3)
                        val albumId = c.getLong(5)
                        val album = c.getString(6) ?: ""
                        val ruta = c.getString(7) ?: ""

                        // Carpeta con el almacenamiento delante: "Memoria interna/Music/X".
                        val relativa = if (nuevaApi) ruta.trimEnd('/')
                        else ruta.substringBeforeLast('/', "").trimEnd('/')
                        val carpeta = if (relativa.isBlank()) etiqueta else "$etiqueta/$relativa"

                        val art = if (albumId > 0L && (volumen == "external_primary" || !nuevaApi))
                            Uri.parse("content://media/external/audio/albumart").buildUpon()
                                .appendPath(albumId.toString()).build().toString()
                        else ""

                        // Id unico: en la memoria principal vale el de MediaStore;
                        // en SD/USB se le pone el volumen delante por si se repite.
                        val id = if (volumen == "external_primary" || !nuevaApi) idReal.toString()
                        else "$volumen:$idReal"

                        out.add(
                            Track.local(
                                id = id,
                                title = nombre,
                                artist = artista.ifBlank { album },
                                durMs = dur,
                                art = art,
                                uriPropia = android.content.ContentUris
                                    .withAppendedId(coleccion, idReal).toString(),
                                carpeta = carpeta
                            )
                        )
                    }
                }
            }
        }
        return out.sortedBy { it.title.lowercase() }
    }
}

/** Ajustes y estado recordado entre sesiones. */
object Estado {

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences("estado", Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences = prefs

    // ---- ajustes
    var seguirConRecomendados: Boolean
        get() = p().getBoolean("autoplay", true)
        set(v) = p().edit().putBoolean("autoplay", v).apply()

    // ---- busquedas recientes
    fun recientes(): List<String> = runCatching {
        val g = p().getString("recientes", "") ?: ""
        if (g.isBlank()) emptyList() else g.split("\u0001").filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun guardarReciente(q: String) {
        val l = ArrayList(recientes())
        l.removeAll { it.equals(q, ignoreCase = true) }
        l.add(0, q)
        p().edit().putString("recientes", l.take(15).joinToString("\u0001")).apply()
    }

    fun vaciarRecientes() = p().edit().remove("recientes").apply()

    // ---- coche / Android Auto
    /** Al conectar al coche, seguir desde el ultimo elemento. */
    var autoAlConectar: Boolean
        get() = p().getBoolean("autoCoche", true)
        set(v) = p().edit().putBoolean("autoCoche", v).apply()

    /** Guardar audio en el movil para escuchar sin cobertura. */
    var cacheOffline: Boolean
        get() = p().getBoolean("cacheOffline", true)
        set(v) = p().edit().putBoolean("cacheOffline", v).apply()

    // ---- ya escuchados (marca visual)
    private var escuchadosMemoria: MutableSet<String>? = null

    fun escuchados(): MutableSet<String> {
        val m = escuchadosMemoria
        if (m != null) return m
        val guardado = runCatching { p().getStringSet("escuchados", emptySet()) ?: emptySet() }
            .getOrDefault(emptySet())
        val nuevo = HashSet(guardado)
        escuchadosMemoria = nuevo
        return nuevo
    }

    fun esEscuchado(mediaId: String): Boolean = escuchados().contains(mediaId)

    fun marcarEscuchado(mediaId: String) {
        if (mediaId.isBlank()) return
        val m = escuchados()
        if (m.add(mediaId)) {
            p().edit().putStringSet("escuchados", HashSet(m)).apply()
        }
    }

    fun desmarcarEscuchado(mediaId: String) {
        if (mediaId.isBlank()) return
        val m = escuchados()
        if (m.remove(mediaId)) p().edit().putStringSet("escuchados", HashSet(m)).apply()
    }

    /** En las listas, enseñar solo lo que queda por oir. */
    var ocultarEscuchados: Boolean
        get() = p().getBoolean("ocultarEscuchados", false)
        set(v) = p().edit().putBoolean("ocultarEscuchados", v).apply()

    fun olvidarEscuchados() {
        escuchados().clear()
        p().edit().remove("escuchados").apply()
    }

    // ---- progreso por pista (reanudar un podcast a medias)
    fun progreso(mediaId: String): Long =
        if (mediaId.isBlank()) 0L else p().getLong("pos:$mediaId", 0L)

    fun guardarProgreso(mediaId: String, ms: Long) {
        if (mediaId.isBlank()) return
        if (ms <= 0) p().edit().remove("pos:$mediaId").apply()
        else p().edit().putLong("pos:$mediaId", ms).apply()
    }

    // ---- de donde salio la cola (nombre de la lista, si salio de una)
    var colaOrigen: String
        get() = p().getString("colaOrigen", "") ?: ""
        set(v) = p().edit().putString("colaOrigen", v).apply()

    // ---- ultimo video de YouTube (para recomendados)
    var ultimoYt: String
        get() = p().getString("ultimoYt", "") ?: ""
        set(v) = p().edit().putString("ultimoYt", v).apply()

    // ---- ultima cola (para "seguir escuchando")
    fun guardarCola(tracks: List<Track>, indice: Int) {
        runCatching {
            val arr = JSONArray()
            for (t in tracks.take(300)) arr.put(t.aJson())
            p().edit()
                .putString("cola", arr.toString())
                .putInt("colaIndice", indice)
                .apply()
        }
    }

    fun cola(): Pair<List<Track>, Int>? = runCatching {
        val g = p().getString("cola", "") ?: ""
        if (g.isBlank()) return null
        val arr = JSONArray(g)
        val l = ArrayList<Track>()
        for (i in 0 until arr.length()) l.add(Track.deJson(arr.getJSONObject(i)))
        if (l.isEmpty()) null else l to p().getInt("colaIndice", 0).coerceIn(0, l.size - 1)
    }.getOrNull()

    fun vaciarCola() = p().edit().remove("cola").remove("colaIndice").apply()
}
