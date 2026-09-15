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
    ENDS_AT_KEY("Last recorded hop"),
    RELATED_TO_KEY("Related to key"),
    REPORTED_BY_KEY("Reported by key"),
    ALL_FOR_SELECTED_KEY("All for selected key"),
}

data class MapNodePoint(
    val hash: String,
    val lat: Double,
    val lon: Double,
    val uncertain: Boolean = false,
    val sourceHash: String = hash,
    val publicKey: String = hash,
    val hopIndex: Int = 0,
    val missingBefore: List<String> = emptyList(),
    val unknownBefore: Boolean = false,
)

data class MapEdge(
    val from: MapNodePoint,
    val to: MapNodePoint,
    val uncertain: Boolean,
    val count: Int = 1,
    val longestRoute: Boolean = false,
    val reply: Boolean = false,
    val missingGps: Boolean = false,
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
    val replyToSelected: Boolean = false,
    val inferredLastHop: Boolean = false,
    val closestObservedReply: Boolean = false,
    val resolvedPath: List<String> = emptyList(),
    val acceptedKeys: Set<String> = emptySet(),
)

data class MapSession(
    val key: String,
    val filter: MapPacketFilter = MapPacketFilter.ANY,
    val trackingMode: MapTrackingMode = MapTrackingMode.ALL_FOR_SELECTED_KEY,
    val running: Boolean = false,
    val startedAt: Long = 0L,
    val events: List<MapRouteEvent> = emptyList(),
    val baseline: Map<String, Int> = emptyMap(),
    val routingRevision: Int = RouteTrackingPolicy.REVISION,
    val capturedPacketIds: Set<String> = emptySet(),
    val pendingRechecks: Set<String> = emptySet(),
)

/** Keep only routes with a direct relationship to the selected key. */
internal object MapRouteScope {
    fun includes(event: MapRouteEvent, selectedKey: String): Boolean {
        val hash = selectedKey.take(4)
        return !event.closestObservedReply && (event.acceptedKeys.any { it.equals(selectedKey, true) } ||
            hash.length == 4 && event.path.any { MeshPath.isReliableHop(it) && selectedKey.startsWith(it, true) })
    }
}

internal object MapReplySelection {
    fun replaceForPacket(events: List<MapRouteEvent>, packetId: String, additions: List<MapRouteEvent>): List<MapRouteEvent> =
        events.filterNot { it.packetId == packetId } + additions

    fun keepLatestSaved(events: List<MapRouteEvent>): List<MapRouteEvent> {
        val latest = events.withIndex().filter { it.value.closestObservedReply }
            .associate { it.value.packetId to it.index }
        return events.filterIndexed { index, event ->
            !event.closestObservedReply || latest[event.packetId] == index
        }
    }
}

internal object MapSessionJson {
    fun encode(session: MapSession): String = JSONObject().apply {
        put("key", session.key); put("filter", session.filter.name); put("trackingMode", session.trackingMode.name)
        put("running", session.running)
        put("startedAt", session.startedAt)
        put("baseline", JSONObject(session.baseline))
        put("routingRevision", session.routingRevision)
        put("capturedPacketIds", JSONArray(session.capturedPacketIds.toList()))
        put("pendingRechecks", JSONArray(session.pendingRechecks.toList()))
        put("events", JSONArray().apply { session.events.forEach { event -> put(JSONObject().apply {
            put("packetId", event.packetId); put("packetHash", event.packetHash)
            put("payloadType", event.payloadType); put("timestamp", event.timestamp)
            put("observedAt", event.observedAt); put("path", JSONArray(event.path))
            put("uncertainAttribution", event.uncertainAttribution)
            put("longestRouteEligible", event.longestRouteEligible)
            put("logicalDestinationUnavailable", event.logicalDestinationUnavailable)
            put("replyToSelected", event.replyToSelected)
            put("inferredLastHop", event.inferredLastHop)
            put("closestObservedReply", event.closestObservedReply)
            put("resolvedPath", JSONArray(event.resolvedPath))
            put("acceptedKeys", JSONArray(event.acceptedKeys.toList()))
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
            baseline = root.optJSONObject("baseline")?.let { values -> values.keys().asSequence().associateWith { values.optInt(it) } }.orEmpty(),
            routingRevision = root.optInt("routingRevision", 0),
            capturedPacketIds = root.optJSONArray("capturedPacketIds")?.let { values -> List(values.length()) { values.optString(it) }.toSet() }.orEmpty(),
            pendingRechecks = root.optJSONArray("pendingRechecks")?.let { values -> List(values.length()) { values.optString(it) }.toSet() }.orEmpty(),
            events = buildList { for (i in 0 until events.length()) events.optJSONObject(i)?.let { event ->
                val path = event.optJSONArray("path") ?: JSONArray()
                val eventPath = buildList { for (hop in 0 until path.length()) add(path.optString(hop)) }
                val eligible = if (event.has("longestRouteEligible")) event.optBoolean("longestRouteEligible")
                    else eventPath.any { hop -> hop.length >= 4 && sessionKey.startsWith(hop, true) }
                add(MapRouteEvent(event.optString("packetId"), event.optString("packetHash"),
                    event.optInt("payloadType"), event.optString("timestamp"), event.optLong("observedAt"),
                    eventPath, event.optBoolean("uncertainAttribution"), eligible,
                    event.optBoolean("logicalDestinationUnavailable"),
                    event.optBoolean("replyToSelected"), event.optBoolean("inferredLastHop"),
                    event.optBoolean("closestObservedReply"),
                    event.optJSONArray("resolvedPath")?.let { values -> List(values.length()) { values.optString(it) } }.orEmpty(),
                    event.optJSONArray("acceptedKeys")?.let { values -> List(values.length()) { values.optString(it) }.toSet() }.orEmpty()))
            } },
        )
    }.getOrElse { error -> if (raw.isBlank()) MapSession(fallbackKey) else throw IllegalArgumentException("Invalid saved map", error) }
}
