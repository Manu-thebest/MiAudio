package cat.manu.miaudio

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Copia de seguridad en la nube: solo los enlaces, las posiciones y la
 * estructura (listas, orden, indice por lista y por donde iba cada audio).
 * Nunca se guarda el audio ni las descargas.
 */
object Copia {

    const val VERSION = 1

    /** Nombre propuesto para el fichero. */
    fun nombreFichero(): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return "miaudio-${f.format(java.util.Date())}.json"
    }

    // ---------------------------------------------------------------- exportar
    fun exportar(ctx: Context): String {
        val root = JSONObject()
        root.put("app", "MiAudio")
        root.put("version", VERSION)
        root.put("fecha", System.currentTimeMillis())

        // Estructura + enlaces: las listas con su orden y su indice.
        val listas = JSONArray()
        for (l in Lib.store.listas) {
            val o = JSONObject()
            o.put("nombre", l.nombre)
            o.put("indice", l.indice)
            val ps = JSONArray()
            for (t in l.pistas) ps.put(t.aJson())
            o.put("pistas", ps)
            listas.put(o)
        }
        root.put("listas", listas)

        // Posiciones: por donde se quedo cada audio (ms).
        val posiciones = JSONObject()
        for ((k, v) in prefs(ctx).all) {
            if (k.startsWith("pos:") && v is Long && v > 0L) posiciones.put(k.removePrefix("pos:"), v)
        }
        root.put("posiciones", posiciones)

        // Ya escuchados (la marca verde).
        val esc = JSONArray()
        for (m in Estado.escuchados().sorted()) esc.put(m)
        root.put("escuchados", esc)

        // Ultima cola, para "seguir escuchando" en otro movil.
        val ultima = JSONObject()
        val cola = Estado.cola()
        if (cola != null) {
            val arr = JSONArray()
            for (t in cola.first) arr.put(t.aJson())
            ultima.put("pistas", arr)
            ultima.put("indice", cola.second)
            ultima.put("origen", Estado.colaOrigen)
        }
        root.put("ultimaCola", ultima)

        return root.toString(2)
    }

    fun escribir(ctx: Context, uri: Uri, texto: String): Boolean = runCatching {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(texto.toByteArray()) }
        true
    }.getOrDefault(false)

    fun leer(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    // ---------------------------------------------------------------- importar
    class Resumen(var listasNuevas: Int = 0, var listasYaEstaban: Int = 0,
                 var posiciones: Int = 0, var escuchados: Int = 0, var cola: Boolean = false)

    /**
     * Restaura la copia sin pisar lo que ya hay: las listas que ya existen se
     * dejan como estan, las posiciones solo se rellenan si aqui no habia nada.
     */
    fun importar(ctx: Context, texto: String): Resumen? {
        val root = runCatching { JSONObject(texto) }.getOrNull() ?: return null
        val res = Resumen()

        // Listas
        val arr = root.optJSONArray("listas") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            val nombre = o.optString("nombre").trim()
            if (nombre.isBlank()) continue
            if (Lib.store.buscar(nombre) != null) {
                res.listasYaEstaban++
                continue
            }
            val l = Lib.store.crear(nombre)
            l.indice = o.optInt("indice", 0)
            val ps = o.optJSONArray("pistas") ?: JSONArray()
            for (j in 0 until ps.length()) {
                runCatching { l.pistas.add(Track.deJson(ps.getJSONObject(j))) }
            }
            l.indice = l.indice.coerceIn(0, (l.pistas.size - 1).coerceAtLeast(0))
            res.listasNuevas++
        }
        Lib.store.save()

        // Posiciones
        val pos = root.optJSONObject("posiciones")
        if (pos != null) {
            val e = prefs(ctx).edit()
            for (k in pos.keys()) {
                val ms = pos.optLong(k, 0L)
                if (ms > 0L && Estado.progreso(k) <= 0L) {
                    e.putLong("pos:$k", ms)
                    res.posiciones++
                }
            }
            e.apply()
        }

        // Marcas de escuchado
        val esc = root.optJSONArray("escuchados") ?: JSONArray()
        for (i in 0 until esc.length()) {
            val m = esc.optString(i)
            if (m.isNotBlank() && !Estado.esEscuchado(m)) {
                Estado.marcarEscuchado(m)
                res.escuchados++
            }
        }

        // Ultima cola (solo si aqui no hay ninguna)
        val ultima = root.optJSONObject("ultimaCola")
        if (ultima != null && Estado.cola() == null) {
            val ps = ultima.optJSONArray("pistas") ?: JSONArray()
            val l = ArrayList<Track>()
            for (i in 0 until ps.length()) {
                runCatching { l.add(Track.deJson(ps.getJSONObject(i))) }
            }
            if (l.isNotEmpty()) {
                Estado.guardarCola(l, ultima.optInt("indice", 0))
                val org = ultima.optString("origen")
                if (org.isNotBlank()) Estado.colaOrigen = org
                res.cola = true
            }
        }

        return res
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("estado", Context.MODE_PRIVATE)
}
