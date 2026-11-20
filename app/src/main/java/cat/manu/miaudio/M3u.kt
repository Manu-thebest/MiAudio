package cat.manu.miaudio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Lee una lista .m3u / .m3u8 y la convierte en una lista de la app. */
object M3u {

    class Resultado(
        val nombre: String,
        val pistas: MutableList<Track> = ArrayList(),
        val noEncontradas: MutableList<String> = ArrayList()
    )

    fun leer(ctx: Context, uri: Uri): Resultado? {
        val texto = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return null
        if (texto.isBlank()) return null

        val fichero = nombreFichero(ctx, uri)
        val nombre = fichero.substringBeforeLast('.').trim().ifBlank { "Lista importada" }
        val res = Resultado(nombre)

        // Titulos de lo que hay en el movil, normalizados, para emparejar.
        val exactos = HashMap<String, Track>()
        val todos = ArrayList<Pair<String, Track>>()
        for (t in Lib.localTracks) {
            val n = normal(t.title)
            if (n.isNotBlank()) {
                exactos.putIfAbsent(n, t)
                todos.add(n to t)
            }
        }

        for (linea in texto.lineSequence()) {
            val l = linea.trim()
            if (l.isEmpty() || l.startsWith("#")) continue

            // Enlaces de internet: se guardan tal cual (streaming).
            if (l.startsWith("http://") || l.startsWith("https://")) {
                val titulo = Uri.decode(l.substringAfterLast('/')).ifBlank { l }
                res.pistas.add(
                    Track.local(
                        id = "web:" + Integer.toHexString(l.hashCode()),
                        title = titulo,
                        artist = "Enlace de internet",
                        durMs = 0L,
                        uriPropia = l,
                        carpeta = "Enlaces"
                    )
                )
                continue
            }

            val base = Uri.decode(l.substringAfterLast('/').substringAfterLast('\\')).trim()
            if (base.isEmpty()) continue
            val sinExt = base.substringBeforeLast('.', base)

            val encontrada = exactos[normal(base)]
                ?: exactos[normal(sinExt)]
                ?: parecida(normal(sinExt), todos)
            if (encontrada != null) res.pistas.add(encontrada) else res.noEncontradas.add(base)
        }
        return res
    }

    /** Guarda el resultado como lista nueva (o añade " (2)" si el nombre ya existe). */
    fun guardar(res: Resultado): String {
        var nombre = res.nombre
        var n = 2
        while (Lib.store.buscar(nombre) != null && n < 99) {
            nombre = "${res.nombre} ($n)"
            n++
        }
        val l = Lib.store.crear(nombre)
        for (t in res.pistas) l.pistas.add(t)
        l.indice = 0
        Lib.store.save()
        return nombre
    }

    private fun parecida(nombre: String, todos: List<Pair<String, Track>>): Track? {
        if (nombre.length < 5) return null
        // Contiene el nombre completo del fichero...
        todos.firstOrNull { (n, _) -> n.length >= 5 && n.contains(nombre) }?.let { return it.second }
        // ...o el fichero contiene el titulo (sin contar titulos muy cortos).
        todos.firstOrNull { (n, _) -> n.length >= 5 && nombre.contains(n) }?.let { return it.second }
        return null
    }

    private fun normal(s: String): String = s.lowercase()
        .replace('_', ' ')
        .replace('-', ' ')
        .replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun nombreFichero(ctx: Context, uri: Uri): String {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) return c.getString(0) ?: "" }
        }
        return uri.lastPathSegment ?: ""
    }
}
