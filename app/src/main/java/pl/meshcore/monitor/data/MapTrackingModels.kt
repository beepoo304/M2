package pl.meshcore.monitor.data

import org.json.JSONArray
import org.json.JSONObject

enum class MapPacketFilter(val label: String) {
    ANY("Any"), ADVERT("Advert"), CHANNEL("Channel message"), DIRECT("Direct message"),
    REQUEST_RESPONSE("Request / response"), TRACE("Trace");

    fun accepts(type: Int): Boolean = when (this) {
        ANY -> true
        ADVERT -> type == 4
        CHANNEL -> type == 5
        DIRECT -> type == 2
        REQUEST_RESPONSE -> type == 0 || type == 1
        TRACE -> type == 9
    }
}

data class MapNodePoint(val hash: String, val lat: Double, val lon: Double, val uncertain: Boolean = false)

data class MapEdge(
    val from: MapNodePoint,
    val to: MapNodePoint,
    val uncertain: Boolean,
    val count: Int = 1,
)

data class MapRouteEvent(
    val packetId: String,
    val packetHash: String,
    val payloadType: Int,
    val timestamp: String,
    val observedAt: Long,
    val path: List<String>,
)

data class MapSession(
    val key: String,
    val filter: MapPacketFilter = MapPacketFilter.ANY,
    val running: Boolean = false,
    val startedAt: Long = 0L,
    val events: List<MapRouteEvent> = emptyList(),
)

internal object MapSessionJson {
    fun encode(session: MapSession): String = JSONObject().apply {
        put("key", session.key); put("filter", session.filter.name); put("running", session.running)
        put("startedAt", session.startedAt)
        put("events", JSONArray().apply { session.events.forEach { event -> put(JSONObject().apply {
            put("packetId", event.packetId); put("packetHash", event.packetHash)
            put("payloadType", event.payloadType); put("timestamp", event.timestamp)
            put("observedAt", event.observedAt); put("path", JSONArray(event.path))
        }) } })
    }.toString()

    fun decode(raw: String, fallbackKey: String): MapSession = runCatching {
        val root = JSONObject(raw); val events = root.optJSONArray("events") ?: JSONArray()
        MapSession(
            key = root.optString("key", fallbackKey),
            filter = runCatching { MapPacketFilter.valueOf(root.optString("filter")) }.getOrDefault(MapPacketFilter.ANY),
            running = root.optBoolean("running"), startedAt = root.optLong("startedAt"),
            events = buildList { for (i in 0 until events.length()) events.optJSONObject(i)?.let { event ->
                val path = event.optJSONArray("path") ?: JSONArray()
                add(MapRouteEvent(event.optString("packetId"), event.optString("packetHash"),
                    event.optInt("payloadType"), event.optString("timestamp"), event.optLong("observedAt"),
                    buildList { for (hop in 0 until path.length()) add(path.optString(hop)) }))
            } },
        )
    }.getOrDefault(MapSession(fallbackKey))
}
