package pl.meshcore.monitor.data

import android.content.Context
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
        var highest = 0
        ExportLocationStore.existingNames(context, prefix, ".m2map").forEach { existing ->
            val number = existing.removePrefix(prefix).removeSuffix(".m2map").toIntOrNull() ?: 0
            if (number > highest) highest = number
        }
        val fileName = "$prefix${(highest + 1).toString().padStart(3, '0')}.m2map"
        progress(40)
        ExportLocationStore.write(context, fileName, "application/json", encode(session, name))
        progress(100)
        return fileName
    }
}
