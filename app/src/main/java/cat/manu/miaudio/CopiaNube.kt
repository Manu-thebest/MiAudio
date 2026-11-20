package cat.manu.miaudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Copia automatica en una carpeta de la nube (Drive, etc.) elegida una sola vez.
 * Se guarda un unico fichero que se reescribe solo: enlaces, posiciones y estructura.
 */
object CopiaNube {

    private const val NOMBRE = "miaudio-copia.json"
    private const val CLAVE = "carpetaCopia"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("estado", Context.MODE_PRIVATE)

    fun carpeta(ctx: Context): Uri? = prefs(ctx).getString(CLAVE, "")
        ?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

    fun activa(ctx: Context): Boolean = carpeta(ctx) != null

    /** Nombre de la carpeta elegida, para enseñarlo en Ajustes. */
    fun nombreCarpeta(ctx: Context): String {
        val uri = carpeta(ctx) ?: return ""
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return ""
        val limpio = id.substringAfter(':', id).ifBlank { id }
        return Uri.decode(limpio).substringAfterLast('/').ifBlank { "Nube" }
    }

    fun guardarCarpeta(ctx: Context, uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        prefs(ctx).edit().putString(CLAVE, uri.toString()).apply()
    }

    fun quitarCarpeta(ctx: Context) {
        carpeta(ctx)?.let {
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs(ctx).edit().remove(CLAVE).apply()
    }

    /** Escribe (o reescribe) la copia dentro de la carpeta elegida. */
    fun guardar(ctx: Context): Boolean {
        val arbol = carpeta(ctx) ?: return false
        return runCatching {
            val r = ctx.contentResolver
            val idRaiz = DocumentsContract.getTreeDocumentId(arbol)
            val raiz = DocumentsContract.buildDocumentUriUsingTree(arbol, idRaiz)
            val hijosUri = DocumentsContract.buildChildDocumentsUriUsingTree(arbol, idRaiz)

            var viejo: String? = null
            r.query(
                hijosUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == NOMBRE) {
                        viejo = c.getString(0)
                        break
                    }
                }
            }
            // Se borra y se crea de nuevo para que no se quede a medias.
            viejo?.let {
                runCatching { DocumentsContract.deleteDocument(r, DocumentsContract.buildDocumentUriUsingTree(arbol, it)) }
            }
            val doc = DocumentsContract.createDocument(r, raiz, "application/json", NOMBRE) ?: return false
            r.openOutputStream(doc)?.use { it.write(Copia.exportar(ctx).toByteArray()) }
            true
        }.getOrDefault(false)
    }

    /** Copia silenciosa al salir de la app (si esta activada). */
    fun autoGuardar(ctx: Context): Boolean {
        if (!activa(ctx)) return false
        return guardar(ctx)
    }
}
