package cat.manu.miaudio

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lee una lista publica de Spotify SIN cuenta ni permisos: se pide la pagina
 * "embed" de la lista, que trae el nombre y las canciones en un JSON.
 */
object Spotify {

    private const val TAG = "Spotify"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    class Pista(val titulo: String, val artistas: String, val durMs: Long)

    class Lista(val nombre: String, val pistas: List<Pista>)

    /** Saca el tipo y el id de un enlace pegado o de un texto compartido. */
    fun idDe(texto: String): Pair<String, String>? {
        Regex("spotify:(playlist|album|track):([A-Za-z0-9]{16,24})")
            .find(texto)?.let { return it.groupValues[1] to it.groupValues[2] }
        Regex("open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(playlist|album|track)/([A-Za-z0-9]{16,24})")
            .find(texto)?.let { return it.groupValues[1] to it.groupValues[2] }
        return null
    }

    /** Descarga y lee la lista. Llamar SIEMPRE desde un hilo aparte. */
    fun leer(texto: String): Lista? {
        val (tipo, id) = idDe(texto) ?: return null
        val html = descargar("https://open.spotify.com/embed/$tipo/$id") ?: return null

        val json = Regex(
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1) ?: return null

        val entidad = runCatching {
            JSONObject(json)
                .getJSONObject("props")
                .getJSONObject("pageProps")
                .getJSONObject("state")
                .getJSONObject("data")
                .getJSONObject("entity")
        }.getOrNull() ?: return null

        val nombre = entidad.optString("name").ifBlank {
            entidad.optString("title").ifBlank { "Lista de Spotify" }
        }
        val lista = entidad.optJSONArray("trackList") ?: return null

        val pistas = ArrayList<Pista>()
        for (i in 0 until lista.length()) {
            val o = lista.optJSONObject(i) ?: continue
            if (o.optString("entityType", "track") != "track") continue
            val titulo = o.optString("title").trim()
            if (titulo.isBlank()) continue
            if (titulo.equals("Ad", true) || titulo.equals("Advertisement", true)) continue
            val artistas = o.optString("subtitle").trim()
            if (artistas.equals("Advertisement", true)) continue
            pistas.add(Pista(titulo, artistas, o.optLong("duration", 0L)))
        }
        Log.i(TAG, "$tipo '$nombre': ${pistas.size} canciones")
        return Lista(nombre, pistas)
    }

    /**
     * De los resultados de YouTube elige el que mas se parece: duracion parecida,
     * el titulo de la cancion dentro del titulo del video y sin versiones raras.
     */
    fun elegir(p: Pista, candidatos: List<Track>): Track? {
        if (candidatos.isEmpty()) return null
        val malas = listOf(
            "karaoke", "instrumental", "cover", "tribute", "nightcore", "sped up",
            "speed up", "slowed", "reverb", "8d audio", "mashup", "reaction",
            "tutorial", "como tocar", "aprende a tocar", "1 hour", "10 hours",
            "loop", "ringtone", "tono de llamada"
        )
        val titulo = normal(p.titulo)
        val palabras = titulo.split(' ').filter { it.length > 2 }

        var mejor: Track? = null
        var mejorPuntos = -1_000

        candidatos.forEachIndexed { posicion, c ->
            val ct = normal(c.title)
            var puntos = 100 - posicion * 6

            if (ct.contains(titulo)) puntos += 45
            else if (palabras.isNotEmpty() && palabras.all { ct.contains(it) }) puntos += 30
            else if (palabras.isNotEmpty() && palabras.count { ct.contains(it) } * 2 > palabras.size) puntos += 10
            else puntos -= 25

            malas.forEach { if (ct.contains(it)) puntos -= 30 }

            // El artista ayuda a distinguir la buena.
            val primerArtista = normal(p.artistas.substringBefore(','))
            if (primerArtista.length > 2 && ct.contains(primerArtista)) puntos += 15

            if (p.durMs > 0 && c.durMs > 0) {
                val dif = kotlin.math.abs(c.durMs - p.durMs).toDouble() / p.durMs
                puntos += when {
                    dif < 0.04 -> 60
                    dif < 0.12 -> 35
                    dif < 0.25 -> 12
                    dif < 0.5 -> -10
                    else -> -45
                }
                // Listas enteras o directos largos: casi nunca son la cancion.
                if (c.durMs > 12 * 60_000L && p.durMs < 8 * 60_000L) puntos -= 60
            }

            if (puntos > mejorPuntos) {
                mejorPuntos = puntos
                mejor = c
            }
        }
        return mejor
    }

    private fun normal(s: String): String = s.lowercase()
        .replace(Regex("[\\[({].*?[\\])}]"), " ")
        .replace(Regex("[^a-z0-9áéíóúüñç ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun descargar(url: String): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${conn.responseCode} en $url")
                return null
            }
            val sb = StringBuilder()
            conn.inputStream.use { entrada ->
                val lector = entrada.bufferedReader(Charsets.UTF_8)
                val buf = CharArray(8192)
                var n = lector.read(buf)
                while (n > 0) {
                    sb.append(buf, 0, n)
                    if (sb.length > 8_000_000) break
                    n = lector.read(buf)
                }
            }
            return sb.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "no se pudo descargar: ${t.message}")
            return null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
