package pl.meshcore.monitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.meshcore.monitor.data.*
import org.json.JSONObject
import org.json.JSONArray

data class NetworkMapState(
    val selectedKey: String = "",
    val selectedName: String = "",
    val session: MapSession? = null,
    val edges: List<MapEdge> = emptyList(),
    val nodes: List<MapNodePoint> = emptyList(),
    val selectedNode: MapNodePoint? = null,
    val loadingNodes: Boolean = true,
    val knownNodeCount: Int = 0,
    val allRepeaterCount: Int = 0,
    val error: String? = null,
    val exportRoutes: List<List<MapNodePoint>> = emptyList(),
    val totalDistanceKm: Double = 0.0,
    val longestRouteKm: Double = 0.0,
    val longestRoute: List<MapNodePoint> = emptyList(),
    val longestRouteIsNewRecord: Boolean = false,
)

class NetworkMapViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("map_tracking", 0)
    val lastSelectedKey: String get() = prefs.getString(LAST_SELECTED_KEY, "").orEmpty()
    private val _state = MutableStateFlow(NetworkMapState())
    val state = _state.asStateFlow()
    private var locatedNodes = emptyList<LocatedNode>()
    private var baselineCounts = emptyMap<String, Int>()
    private val processedCounts = mutableMapOf<String, Int>()

    init {
        viewModelScope.launch {
            locatedNodes = MapNodeRepository.load()
            rebuild(loading = false)
        }
        viewModelScope.launch {
            SharedLiveRepository.state.collect { live ->
                val current = _state.value; val session = current.session ?: return@collect
                if (!session.running) return@collect
                live.packets.filter { packet ->
                    (packet.id !in baselineCounts || packet.observationCount > (baselineCounts[packet.id] ?: 0)) &&
                        matchesSelected(packet, current, session.trackingMode)
                }.forEach { packet ->
                    if ((processedCounts[packet.id] ?: -1) != packet.observationCount) collectPacket(packet)
                }
            }
        }
    }

    fun select(key: String, name: String) {
        if (_state.value.selectedKey == key) return
        val initialSelection = _state.value.selectedKey.isBlank()
        if (!initialSelection) _state.value.session?.takeIf { it.running }?.let { save(it.copy(running = false)) }
        val loaded = load(key)
        val restored = if (initialSelection) loaded else loaded.copy(running = false)
        prefs.edit().putString(LAST_SELECTED_KEY, key).apply()
        _state.value = _state.value.copy(selectedKey = key, selectedName = name, session = restored, error = null)
        TrafficRefreshPolicy.mapTrackingActive = restored.running
        processedCounts.clear(); rebuild()
    }

    fun setTrackingMode(mode: MapTrackingMode) {
        val session = _state.value.session ?: return
        if (session.running || session.trackingMode == mode) return
        baselineCounts = SharedLiveRepository.state.value.packets.associate { it.id to it.observationCount }
        processedCounts.clear()
        processedCounts.putAll(baselineCounts)
        val updated = MapSession(session.key, MapPacketFilter.ANY, mode, running = false, startedAt = 0L)
        save(updated)
        _state.value = _state.value.copy(session = updated, error = null)
        rebuild()
    }

    fun start() {
        val session = _state.value.session ?: return
        baselineCounts = SharedLiveRepository.state.value.packets.associate { it.id to it.observationCount }
        processedCounts.putAll(baselineCounts)
        val updated = session.copy(running = true, startedAt = if (session.startedAt == 0L) System.currentTimeMillis() else session.startedAt)
        TrafficRefreshPolicy.mapTrackingActive = true
        save(updated); _state.value = _state.value.copy(session = updated, error = null)
    }

    fun stop() {
        val session = _state.value.session ?: return
        if (!session.running) return
        val updated = session.copy(running = false); save(updated); _state.value = _state.value.copy(session = updated)
        TrafficRefreshPolicy.mapTrackingActive = false
        commitLongestRouteResult(session.key, _state.value.longestRouteKm)
    }

    fun restart() {
        val current = _state.value; if (current.selectedKey.isBlank()) return
        baselineCounts = SharedLiveRepository.state.value.packets.associate { it.id to it.observationCount }
        processedCounts.clear()
        processedCounts.putAll(baselineCounts)
        val fresh = MapSession(current.selectedKey, MapPacketFilter.ANY,
            current.session?.trackingMode ?: MapTrackingMode.ALL_FOR_SELECTED_KEY, running = false, startedAt = 0L)
        save(fresh); _state.value = current.copy(session = fresh, error = null); rebuild()
        TrafficRefreshPolicy.mapTrackingActive = false
    }

    fun importMap(raw: String) {
        val imported = MapFileStore.decode(raw)
        val session = imported.session.copy(running = false)
        save(session)
        processedCounts.clear(); baselineCounts = emptyMap()
        _state.value = _state.value.copy(selectedKey = session.key, selectedName = imported.name,
            session = session, error = null)
        rebuild()
    }

    fun markLongestRouteViewed() {
        val key = _state.value.selectedKey
        if (key.isBlank()) return
        prefs.edit().putBoolean(recordUnreadKey(key), false).apply()
        _state.value = _state.value.copy(longestRouteIsNewRecord = false)
    }

    private fun matchesSelected(packet: LivePacket, current: NetworkMapState, mode: MapTrackingMode): Boolean {
        val selected = current.selectedKey
        val relations = packet.trackedRelations
        val starts = relations.sourceKeys.any { it.equals(selected, true) }
        val ends = relations.destinationKeys.any { it.equals(selected, true) }
        val inRoute = relations.routeKeys.any { it.equals(selected, true) }
        val reported = relations.observerKeys.any { it.equals(selected, true) }
        if (when (mode) {
            MapTrackingMode.STARTS_AT_KEY -> starts
            MapTrackingMode.ENDS_AT_KEY -> ends
            MapTrackingMode.RELATED_TO_KEY -> starts || ends || inRoute
            MapTrackingMode.REPORTED_BY_KEY -> reported
            MapTrackingMode.ALL_FOR_SELECTED_KEY -> starts || ends || inRoute || reported
        }) return true
        val decoded = packet.decodedJson.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (packet.payloadType in 0..2 && mode != MapTrackingMode.REPORTED_BY_KEY) {
            val sourceHash = decoded?.optString("srcHash").orEmpty()
            val destinationHash = decoded?.optString("destHash").orEmpty()
            val ownKeys = ConnectionConfigBus.config.value.ownPublicKeys
            val confirmedByOwnRadio = packet.observerPublicKey.isNotBlank() && ownKeys.any {
                it.equals(packet.observerPublicKey, true)
            } || packet.path.any { hop ->
                hop.length >= 4 && ownKeys.any { it.startsWith(hop, true) }
            }
            val exactHash = sourceHash.length >= 4 && current.selectedKey.startsWith(sourceHash, true) ||
                destinationHash.length >= 4 && current.selectedKey.startsWith(destinationHash, true)
            val corroboratedOneByteHash = confirmedByOwnRadio && (
                sourceHash.length == 2 && current.selectedKey.startsWith(sourceHash, true) ||
                    destinationHash.length == 2 && current.selectedKey.startsWith(destinationHash, true)
                )
            if (exactHash || corroboratedOneByteHash) return true
        }
        val name = current.selectedName.trim()
        return mode != MapTrackingMode.REPORTED_BY_KEY && name.isNotBlank() && !name.startsWith("Looking up", true) &&
            (decoded?.optString("sender").equals(name, true) || decoded?.optString("name").equals(name, true))
    }

    private fun collectPacket(packet: LivePacket) {
        processedCounts[packet.id] = packet.observationCount
        viewModelScope.launch {
            val details = PacketObservationRepository.load(packet.id)
            val current = _state.value; val session = current.session ?: return@launch
            if (!session.running || session.key != current.selectedKey) return@launch
            val existing = session.events.mapTo(mutableSetOf()) { "${it.packetId}:${it.path.joinToString()}" }
            val decoded = packet.decodedJson.takeIf { it.startsWith("{") }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val selectedName = current.selectedName.trim()
            val hasUsableSelectedName = selectedName.isNotBlank() && !selectedName.startsWith("Looking up", true)
            val packetSourceMatch = packet.trackedRelations.sourceKeys.any { it.equals(current.selectedKey, true) } ||
                hasUsableSelectedName && (
                    decoded?.optString("sender").equals(selectedName, true) ||
                        decoded?.optString("name").equals(selectedName, true)
                    )
            val packetDestinationMatch = packet.trackedRelations.destinationKeys.any { it.equals(current.selectedKey, true) } ||
                hasUsableSelectedName && TrackedMention.contains(
                    decoded?.optString("text").orEmpty(), setOf(selectedName.lowercase())
                )
            val packetRouteMatch = packet.trackedRelations.routeKeys.any { it.equals(current.selectedKey, true) }
            val additions = details.routes.mapNotNull { route ->
                val selectedKey = current.selectedKey
                val selectedHash = selectedKey.take(4).uppercase()
                val routeRelations = TrackedKeyMatcher.resolvedRoute(route.path, route.resolvedPath, setOf(selectedKey))
                val observerMatch = TrackedKeyMatcher.observer(route.observerPublicKey, setOf(selectedKey)).observerKeys.isNotEmpty()
                val sourceMatch = packetSourceMatch
                val destinationMatch = packetDestinationMatch
                val accepted = when (session.trackingMode) {
                    MapTrackingMode.STARTS_AT_KEY -> sourceMatch
                    MapTrackingMode.ENDS_AT_KEY -> destinationMatch
                    MapTrackingMode.RELATED_TO_KEY -> sourceMatch || destinationMatch || packetRouteMatch
                    MapTrackingMode.REPORTED_BY_KEY -> observerMatch
                    MapTrackingMode.ALL_FOR_SELECTED_KEY -> sourceMatch || destinationMatch || packetRouteMatch || observerMatch
                }
                if (!accepted) return@mapNotNull null
                val directEmptyPath = packet.payloadType in 0..2 && route.path.isEmpty()
                var path = when {
                    directEmptyPath && sourceMatch && route.observerPublicKey.length >= 4 -> listOf(
                        selectedHash,
                        route.observerPublicKey.take(4).uppercase(),
                    ).distinct()
                    packet.payloadType == 9 -> canonicalizeTrackedTracePath(MeshPath.normalizeTrace(route.path))
                    else -> route.path
                }
                // A sender name identifies the selected device, but does not prove
                // that its public-key hash is an RF hop. Keep the API's physical
                // route intact instead of inventing selectedKey -> first hop.
                val exactKeySource = packet.trackedRelations.sourceKeys.any { it.equals(selectedKey, true) }
                if (exactKeySource && !path.firstOrNull().equals(selectedHash, true)) path = listOf(selectedHash) + path
                if ((destinationMatch || observerMatch) && !path.lastOrNull().equals(selectedHash, true)) path = path + selectedHash
                val id = "${packet.id}:${path.joinToString()}"
                if (path.distinct().size < 2 || id in existing || path.any { it.length !in setOf(2, 4, 6) }) null
                else MapRouteEvent(packet.id, packet.hash, packet.payloadType, packet.timestamp,
                    System.currentTimeMillis(), path,
                    uncertainAttribution = path.any { it.length == 2 })
            }.distinctBy { "${it.packetId}:${it.path.joinToString()}" }
            if (additions.isNotEmpty()) {
                val updated = session.copy(events = (session.events + additions).takeLast(5000))
                save(updated); _state.value = current.copy(session = updated); rebuild()
            }
        }
    }

    private fun rebuild(loading: Boolean = _state.value.loadingNodes) {
        val events = _state.value.session?.events.orEmpty()
        val selectedHash = _state.value.selectedKey.take(4)
        val selected = (locatedNodes.firstOrNull { it.publicKey.equals(_state.value.selectedKey, true) }
            ?: locatedNodes.firstOrNull {
                selectedHash.length == 4 && it.publicKey.startsWith(selectedHash, ignoreCase = true)
            })?.let {
            MapNodePoint(it.publicKey.take(4), it.lat, it.lon)
        }
        val routedEvents = events.map { original ->
            val event = if (original.payloadType == 9) original.copy(
                path = canonicalizeTrackedTracePath(original.path),
                uncertainAttribution = canonicalizeTrackedTracePath(original.path).any { it.length == 2 },
            ) else original
            event.copy(uncertainAttribution = event.path.any { it.length == 2 })
        }
        val metrics = MapRouteMapper.metrics(routedEvents, locatedNodes)
        val longestSegments = metrics.longestRoute.zipWithNext().mapTo(mutableSetOf()) { (a, b) ->
            listOf("${a.lat}:${a.lon}", "${b.lat}:${b.lon}").sorted().joinToString("|")
        }
        val edges = MapRouteMapper.edges(routedEvents, locatedNodes).map { edge ->
            val segment = listOf("${edge.from.lat}:${edge.from.lon}", "${edge.to.lat}:${edge.to.lon}").sorted().joinToString("|")
            edge.copy(longestRoute = segment in longestSegments)
        }
        val nodes = (edges.flatMap { listOf(it.from, it.to) } + listOfNotNull(selected))
            .distinctBy { "${it.hash}:${it.lat}:${it.lon}" }
        _state.value = _state.value.copy(
            edges = edges, nodes = nodes, selectedNode = selected,
            exportRoutes = routedEvents.sortedBy { it.observedAt }.map { MapRouteMapper.resolve(it.path, locatedNodes) }.filter { it.size > 1 },
            totalDistanceKm = metrics.totalUniqueKm, longestRouteKm = metrics.longestRouteKm,
            loadingNodes = loading, knownNodeCount = locatedNodes.size,
            allRepeaterCount = MapNodeRepository.lastAllRepeaterCount,
            longestRoute = metrics.longestRoute,
            longestRouteIsNewRecord = prefs.getBoolean(recordUnreadKey(_state.value.selectedKey), false),
        )
    }

    private fun commitLongestRouteResult(key: String, distanceKm: Double) {
        if (key.isBlank() || distanceKm <= 0.0) return
        val records = loadLongestRouteRecords().toMutableList()
        val previousBest = records.asSequence().filter { it.key.equals(key, true) }
            .maxOfOrNull(LongestRouteRecord::distanceKm) ?: 0.0
        records += LongestRouteRecord(key.lowercase(), distanceKm)
        while (records.count { it.key.equals(key, true) } > MAX_LONGEST_ROUTE_RECORDS_PER_KEY) {
            val oldestForKey = records.indexOfFirst { it.key.equals(key, true) }
            if (oldestForKey < 0) break else records.removeAt(oldestForKey)
        }
        val isRecord = distanceKm > previousBest
        prefs.edit()
            .putString(LONGEST_ROUTE_RECORDS, JSONArray().apply { records.forEach { record ->
                put(JSONObject().apply { put("key", record.key); put("km", record.distanceKm) })
            } }.toString())
            .putBoolean(recordUnreadKey(key), isRecord)
            .apply()
        _state.value = _state.value.copy(longestRouteIsNewRecord = isRecord)
    }

    private fun loadLongestRouteRecords(): List<LongestRouteRecord> = runCatching {
        val values = JSONArray(prefs.getString(LONGEST_ROUTE_RECORDS, "[]"))
        buildList { for (index in 0 until values.length()) values.optJSONObject(index)?.let { record ->
            val key = record.optString("key")
            val km = record.optDouble("km", 0.0)
            if (key.isNotBlank() && km > 0.0) add(LongestRouteRecord(key, km))
        } }
    }.getOrDefault(emptyList())

    private fun recordUnreadKey(key: String) = "longest_route_unread_${key.lowercase()}"

    private fun canonicalizeTrackedTracePath(path: List<String>): List<String> {
        val trackedKeys = ConnectionConfigBus.config.value.ownPublicKeys
        return path.map { hop ->
            if (hop.length != 2) hop else trackedKeys
                .filter { it.startsWith(hop, ignoreCase = true) }
                .singleOrNull()?.take(4)?.uppercase() ?: hop
        }
    }

    private fun prefKey(key: String) = "session_${key.lowercase()}"
    private fun load(key: String): MapSession = MapSessionJson.decode(prefs.getString(prefKey(key), "").orEmpty(), key)
    private fun save(session: MapSession) { prefs.edit().putString(prefKey(session.key), MapSessionJson.encode(session)).apply() }

    private companion object {
        const val LAST_SELECTED_KEY = "last_selected_key"
        const val LONGEST_ROUTE_RECORDS = "longest_route_records"
        const val MAX_LONGEST_ROUTE_RECORDS_PER_KEY = 1_000
    }

    override fun onCleared() {
        TrafficRefreshPolicy.mapTrackingActive = false
        super.onCleared()
    }
}

private data class LongestRouteRecord(val key: String, val distanceKm: Double)
