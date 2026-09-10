package pl.meshcore.monitor.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile

object ExportLocationStore {
    private const val PREFS = "export_location"
    private const val URI_KEY = "tree_uri"

    fun selectedUri(context: Context): String? =
        context.getSharedPreferences(PREFS, 0).getString(URI_KEY, null)

    fun saveSelection(context: Context, uri: String?) {
        context.getSharedPreferences(PREFS, 0).edit().apply {
            if (uri.isNullOrBlank()) remove(URI_KEY) else putString(URI_KEY, uri)
        }.commit()
        if (uri.isNullOrBlank()) ensureDefaultFolder(context)
    }

    fun label(context: Context, uri: String? = selectedUri(context)): String =
        if (uri.isNullOrBlank()) "Download/M2"
        else DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name ?: "Selected folder"

    fun existingNames(context: Context, prefix: String, suffix: String): List<String> {
        val tree = selectedUri(context)
        if (!tree.isNullOrBlank()) return DocumentFile.fromTreeUri(context, Uri.parse(tree))?.listFiles()
            ?.mapNotNull { it.name }?.filter { it.startsWith(prefix) && it.endsWith(suffix) }.orEmpty()
        val names = mutableListOf<String>()
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("$prefix%$suffix"), null)?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) names += cursor.getString(column)
        }
        return names
    }

    fun write(context: Context, fileName: String, mimeType: String, content: String) {
        writeBytes(context, fileName, mimeType, content.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(context: Context, fileName: String, mimeType: String, content: ByteArray) {
        val tree = selectedUri(context)
        if (!tree.isNullOrBlank()) {
            val directory = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: error("Selected folder is unavailable")
            directory.findFile(fileName)?.delete()
            val file = directory.createFile(mimeType, fileName) ?: error("Cannot create export file")
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(content) }
                ?: error("Cannot write export file")
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/M2")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create export file")
        try {
            resolver.openOutputStream(uri)?.use { it.write(content) }
                ?: error("Cannot write export file")
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); resolver.update(uri, values, null, null)
        } catch (error: Throwable) { resolver.delete(uri, null, null); throw error }
    }

    private fun ensureDefaultFolder(context: Context) {
        val marker = ".m2-folder"
        runCatching { write(context, marker, "text/plain", "M² export folder") }
    }
}
