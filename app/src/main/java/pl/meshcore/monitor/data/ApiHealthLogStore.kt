package pl.meshcore.monitor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ApiHealthEntry(
    val startedAtMs: Long,
    val lastCheckedAtMs: Long,
    val status: String,
    val responseMs: Long,
    val checks: Int = 1,
    val ongoing: Boolean = true,
)

/** Broker URL is the storage boundary; selecting another API never merges outages. */
class ApiHealthLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("api_health_history", Context.MODE_PRIVATE)

    fun load(base: String): List<ApiHealthEntry> = runCatching {
        decode(prefs.getString(storageKey(base), "[]").orEmpty())
    }.getOrDefault(emptyList())

    fun save(base: String, entries: List<ApiHealthEntry>) {
        check(prefs.edit().putString(storageKey(base), encode(entries)).commit()) { "Cannot save API history" }
    }

    private fun storageKey(base: String) = "broker:${base.trim().trimEnd('/')}"

    companion object {
        const val LIMIT = 250
        internal fun encode(entries: List<ApiHealthEntry>): String = JSONArray().apply {
            entries.take(LIMIT).forEach { e -> put(JSONObject().apply {
                put("start", e.startedAtMs); put("last", e.lastCheckedAtMs)
                put("status", e.status); put("response", e.responseMs)
                put("checks", e.checks); put("ongoing", e.ongoing)
            }) }
        }.toString()

        internal fun decode(raw: String): List<ApiHealthEntry> {
            val values = JSONArray(raw)
            return buildList {
                for (index in 0 until minOf(values.length(), LIMIT)) {
                    val e = values.getJSONObject(index)
                    add(ApiHealthEntry(e.getLong("start"), e.getLong("last"), e.getString("status"),
                        e.getLong("response"), e.getInt("checks"), e.getBoolean("ongoing")))
                }
            }
        }
    }
}
