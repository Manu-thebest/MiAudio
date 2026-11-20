package cat.manu.miaudio

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cache de audio en disco para escuchar sin cobertura.
 * Cada video se guarda como <videoId>.aud dentro de filesDir/audio (el contenido
 * puede ser m4a o webm/opus; ExoPlayer lo reconoce por la cabecera).
 * Se descargan por delante las siguientes pistas de la cola.
 */
object AudioCache {

    private const val TAG = "MiAudio/Cache"
    private const val LIMITE = 1500L * 1024L * 1024L   // 1,5 GB
    private const val MINIMO = 40_000L

    @Volatile
    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir == null) {
            synchronized(this) {
                if (dir == null) {
                    val d = File(ctx.filesDir, "audio")
                    if (!d.exists()) d.mkdirs()
                    // restos de descargas cortadas
                    runCatching { d.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() } }
                    dir = d
                }
            }
        }
    }

    fun fichero(id: String): File? = dir?.let { File(it, id + ".aud") }

    /** Hay audio ya descargado para ese video. */
    fun tiene(id: String): Boolean {
        val f = fichero(id) ?: return false
        return runCatching { f.exists() && f.length() > MINIMO }.getOrDefault(false)
    }

    fun tamanoTotal(): Long {
        val d = dir ?: return 0L
        return runCatching { d.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)
    }

    fun cuantos(): Int {
        val d = dir ?: return 0
        return runCatching { d.listFiles()?.count { it.length() > MINIMO } ?: 0 }.getOrDefault(0)
    }

    fun vaciar() {
        val d = dir ?: return
        runCatching { d.listFiles()?.forEach { it.delete() } }
        Log.i(TAG, "cache vaciada")
    }

    /** Descarga el audio de un video. Devuelve true si quedo guardado. */
    fun guardar(id: String, url: String): Boolean {
        if (id.isBlank() || url.isBlank()) return false
        if (tiene(id)) return true
        val destino = fichero(id) ?: return false
        val tmp = File(destino.parentFile, id + ".part")
        return runCatching {
            val conexion = URL(url).openConnection() as HttpURLConnection
            try {
                conexion.connectTimeout = 20000
                conexion.readTimeout = 30000
                conexion.instanceFollowRedirects = true
                conexion.setRequestProperty("User-Agent", Yt.UA)
                conexion.inputStream.use { entrada ->
                    tmp.outputStream().use { salida -> entrada.copyTo(salida, 64 * 1024) }
                }
            } finally {
                runCatching { conexion.disconnect() }
            }
            if (tmp.length() < MINIMO) {
                tmp.delete()
                false
            } else {
                tmp.setLastModified(System.currentTimeMillis())
                val ok = tmp.renameTo(destino)
                if (ok) {
                    Log.i(TAG, "guardado $id (${destino.length() / 1024} KB)")
                    purgar()
                }
                ok
            }
        }.getOrDefault(false)
    }

    /** Borra las mas antiguas si se pasa del limite. */
    private fun purgar() {
        val d = dir ?: return
        runCatching {
            val ficheros = d.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            var total = ficheros.sumOf { it.length() }
            for (f in ficheros) {
                if (total <= LIMITE) break
                val t = f.length()
                if (f.delete()) total -= t
            }
        }
    }
}
