package cat.manu.miaudio

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject

object Ids {
    const val ROOT = "root"
    const val LOCALES = "locales"
    const val LISTAS = "listas"
    const val YT = "yt"
    const val PL = "pl:"        // pl:<indice>
    const val YTQ = "ytq:"      // ytq:<consulta>
    const val YTPL = "ytpl:"    // ytpl:<url de lista codificada>
    const val YTVID = "ytvid:"  // ytvid:<videoId>
    const val LOCAL = "local:"  // local:<id de MediaStore>
    const val CARPETAS = "carpetas"        // lista de carpetas del movil
    const val CARPETA = "carpetalocal:"    // carpetalocal:<nombre codificado>
}

/** Una pista reproducible: audio del movil o video de YouTube (solo audio). */
data class Track(
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val durMs: Long,
    /** Portada: URL https o content:// ; vacia = sin portada. */
    val art: String = "",
    /** Nombre del album/lista de YouTube del que vino (para agrupar). */
    val grupo: String? = null,
    /** URI real del fichero: content:// del volumen donde este (interna, SD o USB). */
    val uriPropia: String = "",
    /** Carpeta de origen, con el nombre del almacenamiento delante. */
    val carpeta: String = ""
) {
    fun mediaId(): String = if (kind == "local") Ids.LOCAL + id else Ids.YTVID + id

    fun uri(): Uri = when {
        uriPropia.isNotBlank() -> Uri.parse(uriPropia)
        kind == "local" -> Uri.parse("content://media/external/audio/media/$id")
        else -> Uri.parse("yt:$id")
    }

    fun artUri(): Uri? = when {
        art.startsWith("http") || art.startsWith("content://") -> Uri.parse(art)
        else -> null
    }

    fun toMediaItem(inicioMs: Long = 0L): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
        if (durMs > 0) meta.setDurationMs(durMs)
        val portada = artUri()
            ?: if (kind == "yt") Uri.parse("https://i.ytimg.com/vi/$id/mqdefault.jpg") else null
        if (portada != null) meta.setArtworkUri(portada)
        val b = MediaItem.Builder()
            .setMediaId(mediaId())
            .setUri(uri())
            .setMediaMetadata(meta.build())
        // Reanudar donde se dejo (podcasts): inicio recortado.
        if (inicioMs > 0) {
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(inicioMs)
                    .build()
            )
        }
        return b.build()
    }

    fun aJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("id", id)
        put("title", title)
        put("subtitle", subtitle)
        put("durMs", durMs)
        put("art", art)
        put("grupo", grupo ?: "")
        put("uri", uriPropia)
        put("carpeta", carpeta)
    }

    companion object {
        fun local(
            id: String,
            title: String,
            artist: String,
            durMs: Long,
            art: String = "",
            uriPropia: String = "",
            carpeta: String = ""
        ) = Track("local", id, title, artist, durMs, art, null, uriPropia, carpeta)

        fun yt(videoId: String, title: String, author: String, durMs: Long, grupo: String? = null) =
            Track("yt", videoId, title, author, durMs, "https://i.ytimg.com/vi/$videoId/mqdefault.jpg", grupo)

        fun deJson(o: JSONObject): Track = Track(
            kind = o.optString("kind", "local"),
            id = o.optString("id"),
            title = o.optString("title"),
            subtitle = o.optString("subtitle"),
            durMs = o.optLong("durMs", 0L),
            art = o.optString("art", ""),
            grupo = o.optString("grupo", "").ifBlank { null },
            uriPropia = o.optString("uri", ""),
            carpeta = o.optString("carpeta", "")
        )
    }
}

/** Fila de la interfaz. */
data class Row(
    val tipo: Tipo = Tipo.PISTA,
    val titulo: String = "",
    val sub: String = "",
    val art: String = "",
    val track: Track? = null,
    val flecha: Boolean = false,
    /** Ya escuchado (marca visual). */
    val hecho: Boolean = false,
    val accion: (() -> Unit)? = null
) {
    enum class Tipo { PISTA, CARPETA, ACCION, CABECERA, CARGA }

    companion object {
        fun pista(t: Track, sub: String, grupo: String? = null, hecho: Boolean = false) =
            Row(Tipo.PISTA, t.title, sub, t.art, t, false, hecho, null)

        fun carpeta(titulo: String, sub: String, art: String = "", abrir: () -> Unit = {}) =
            Row(Tipo.CARPETA, titulo, sub, art, null, true, false, abrir)

        fun accion(titulo: String, sub: String = "", art: String = "", pulsar: () -> Unit) =
            Row(Tipo.ACCION, titulo, sub, art, null, true, false, pulsar)

        fun cabecera(titulo: String) = Row(Tipo.CABECERA, titulo)
    }
}
