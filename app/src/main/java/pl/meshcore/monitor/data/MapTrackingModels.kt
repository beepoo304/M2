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

enum class MapTrackingMode(val label: String) {
    STARTS_AT_KEY("Starts at key"),
    ENDS_AT_KEY("Ends at key"),
    RELATED_TO_KEY("Related to key"),
    REPORTED_BY_KEY("Reported by key"),
    ALL_FOR_SELECTED_KEY("All for selected key"),
}

data class MapNodePoint(val hash: String, val lat: Double, val lon: Double, val uncertain: Boolean = false)

data class MapEdge(
    val from: MapNodePoint,
    val to: MapNodePoint,
    val uncertain: Boolean,
    val count: Int = 1,
    val longestRoute: Boolean = false,
)

data class MapRouteEvent(
    val packetId: String,
    val packetHash: String,
    val payloadType: Int,
    val timestamp: String,
    val observedAt: Long,
    val path: List<String>,
    val uncertainAttribution: Boolean = false,
    val longestRouteEligible: Boolean = true,
    val logicalDestinationUnavailable: Boolean = false,
)

data class MapSession(
    val key: String,
    val filter: MapPacketFilter = MapPacketFilter.ANY,
    val trackingMode: MapTrackingMode = MapTrackingMode.ALL_FOR_SELECTED_KEY,
    val running: Boolean = false,
    val startedAt: Long = 0L,
    val events: List<MapRouteEvent> = emptyList(),
)

internal object MapSessionJson {
    fun encode(session: MapSession): String = JSONObject().apply {
        put("key", session.key); put("filter", session.filter.name); put("trackingMode", session.trackingMode.name)
        put("running", session.running)
        put("startedAt", session.startedAt)
        put("events", JSONArray().apply { session.events.forEach { event -> put(JSONObject().apply {
            put("packetId", event.packetId); put("packetHash", event.packetHash)
            put("payloadType", event.payloadType); put("timestamp", event.timestamp)
            put("observedAt", event.observedAt); put("path", JSONArray(event.path))
            put("uncertainAttribution", event.uncertainAttribution)
            put("longestRouteEligible", event.longestRouteEligible)
            put("logicalDestinationUnavailable", event.logicalDestinationUnavailable)
        }) } })
    }.toString()

    fun decode(raw: String, fallbackKey: String): MapSession = runCatching {
        val root = JSONObject(raw); val events = root.optJSONArray("events") ?: JSONArray()
        val sessionKey = root.optString("key", fallbackKey)
        MapSession(
            key = sessionKey,
            filter = runCatching { MapPacketFilter.valueOf(root.optString("filter")) }.getOrDefault(MapPacketFilter.ANY),
            trackingMode = runCatching { MapTrackingMode.valueOf(root.optString("trackingMode")) }
                .getOrDefault(MapTrackingMode.ALL_FOR_SELECTED_KEY),
            running = root.optBoolean("running"), startedAt = root.optLong("startedAt"),
            events = buildList { for (i in 0 until events.length()) events.optJSONObject(i)?.let { event ->
                val path = event.optJSONArray("path") ?: JSONArray()
                val eventPath = buildList { for (hop in 0 until path.length()) add(path.optString(hop)) }
                val eligible = if (event.has("longestRouteEligible")) event.optBoolean("longestRouteEligible")
                    else eventPath.any { hop -> hop.length >= 4 && sessionKey.startsWith(hop, true) }
                add(MapRouteEvent(event.optString("packetId"), event.optString("packetHash"),
                    event.optInt("payloadType"), event.optString("timestamp"), event.optLong("observedAt"),
                    eventPath, event.optBoolean("uncertainAttribution"), eligible,
                    event.optBoolean("logicalDestinationUnavailable")))
            } },
        )
    }.getOrDefault(MapSession(fallbackKey))
}
