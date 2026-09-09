package pl.meshcore.monitor.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class ImportedMap(val session: MapSession, val name: String)

object MapFileStore {
    fun encode(session: MapSession, name: String): String = JSONObject().apply {
        put("format", "M2_MAP"); put("version", 1); put("name", name)
        put("session", JSONObject(MapSessionJson.encode(session.copy(running = false))))
    }.toString()

    fun decode(raw: String): ImportedMap {
        val root = JSONObject(raw)
        require(root.optString("format") == "M2_MAP") { "Not an M² map file" }
        require(root.optInt("version") == 1) { "Unsupported map version" }
        val sessionJson = root.getJSONObject("session").toString()
        val key = root.getJSONObject("session").getString("key")
        return ImportedMap(MapSessionJson.decode(sessionJson, key).copy(running = false), root.optString("name"))
    }

    fun save(context: Context, session: MapSession, name: String, progress: (Int) -> Unit = {}): String {
        progress(10)
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val prefix = "${session.key.take(4).uppercase()}_${date}_"
        val resolver = context.contentResolver
        var highest = 0
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("$prefix%.m2map"), null)?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val number = cursor.getString(column).removePrefix(prefix).removeSuffix(".m2map").toIntOrNull() ?: 0
                if (number > highest) highest = number
            }
        }
        val fileName = "$prefix${(highest + 1).toString().padStart(3, '0')}.m2map"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/M2")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create map file")
        try {
            progress(40)
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(encode(session, name)) }
                ?: error("Cannot write map file")
            progress(85)
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); resolver.update(uri, values, null, null)
            progress(100)
            return fileName
        } catch (error: Throwable) { resolver.delete(uri, null, null); throw error }
    }
}
