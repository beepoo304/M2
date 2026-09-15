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
    val logicalDestinationUnavailable: Boolean = false,
)

class NetworkMapViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("map_tracking", 0)
    val lastSelectedKey: String get() = prefs.getString(LAST_SELECTED_KEY, "").orEmpty()
    private val _state = MutableStateFlow(NetworkMapState())
    val state = _state.asStateFlow()
    private var locatedNodes = emptyList<LocatedNode>()
    private var baselineCounts = emptyMap<String, Int>()
    private var generation = 0L
    private var activeBase = ConnectionConfigBus.config.value.coreScopeBaseUrl
    private val rechecksInFlight = mutableMapOf<String, Long>()
    private val processedCounts = mutableMapOf<String, Int>()

    init {
        viewModelScope.launch {
            launch { ConnectionConfigBus.config.collect { config ->
                if (config.coreScopeBaseUrl != activeBase) {
                    activeBase = config.coreScopeBaseUrl; generation++; processedCounts.clear()
                }
                MapNodeRepository.load()
            } }
            launch { MapNodeRepository.directory.collect { rebuild() } }
            MapNodeRepository.nodes.collect { nodes -> locatedNodes = nodes; rebuild(loading = false) }
        }
        viewModelScope.launch {
            SharedLiveRepository.state.collect { live ->
                val before = _state.value.session ?: return@collect
                val session = MapSessionReplayPolicy.migrate(before, live.packets)
                if (session != before) { save(session); _state.value = _state.value.copy(session = session) }
                val current = _state.value
                if (!session.running) return@collect
                live.packets.filter { packet ->
                    packet.stableIdentity in session.pendingRechecks ||
                        ((packet.stableIdentity !in baselineCounts || packet.observationCount > (baselineCounts[packet.stableIdentity] ?: 0)) &&
                            matchesSelected(packet, current, session.trackingMode))
                }.forEach { packet ->
                    val replay = packet.stableIdentity in session.pendingRechecks
                    if (rechecksInFlight[packet.stableIdentity] != generation &&
                        (replay || (processedCounts[packet.stableIdentity] ?: -1) != packet.observationCount)) collectPacket(packet)
                }
            }
        }
    }

    fun select(key: String, name: String) {
        if (_state.value.selectedKey == key) return
        val initialSelection = _state.value.selectedKey.isBlank()
        if (!initialSelection) _state.value.session?.takeIf { it.running }?.let { save(it.copy(running = false)) }
        val loaded = load(key).withoutUnrelatedRoutes()
        val restored = MapSessionReplayPolicy.migrate(if (initialSelection) loaded else loaded.copy(running = false),
            SharedLiveRepository.state.value.packets)
        generation++
        save(restored)
        baselineCounts = restored.baseline
        prefs.edit().putString(LAST_SELECTED_KEY, key).apply()
        _state.value = _state.value.copy(selectedKey = key, selectedName = name, session = restored, error = null)
        TrafficRefreshPolicy.mapTrackingActive = restored.running
        processedCounts.clear(); processedCounts.putAll(baselineCounts); rebuild()
    }

    fun setTrackingMode(mode: MapTrackingMode) {
        generation++
        val session = _state.value.session ?: return
        if (session.running || session.trackingMode == mode) return
        baselineCounts = SharedLiveRepository.state.value.packets.associate { it.stableIdentity to it.observationCount }
        processedCounts.clear()
        processedCounts.putAll(baselineCounts)
        val updated = MapSession(session.key, MapPacketFilter.ANY, mode, running = false, startedAt = 0L)
        save(updated)
        _state.value = _state.value.copy(session = updated, error = null)
        rebuild()
    }

    fun start() {
        generation++
        val session = _state.value.session ?: return
        baselineCounts = SharedLiveRepository.state.value.packets.associate { it.stableIdentity to it.observationCount }
        processedCounts.putAll(baselineCounts)
        val updated = session.copy(running = true, baseline = baselineCounts, startedAt = if (session.startedAt == 0L) System.currentTimeMillis() else session.startedAt)
        TrafficRefreshPolicy.mapTrackingActive = true
        save(updated); _state.value = _state.value.copy(session = updated, error = null)
    }

    fun stop() {
        generation++
        val session = _state.value.session ?: return
        if (!session.running) return
        val updated = session.copy(running = false); save(updated); _state.value = _state.value.copy(session = updated)
        TrafficRefreshPolicy.mapTrackingActive = false
        commitLongestRouteResult(session.key, _state.value.longestRouteKm)
    }

    fun restart() {
        generation++
        val current = _state.value; if (current.selectedKey.isBlank()) return
        baselineCounts = SharedLiveRepository.state.value.packets.associate { it.stableIdentity to it.observationCount }
        processedCounts.clear()
        processedCounts.putAll(baselineCounts)
        val fresh = MapSession(current.selectedKey, MapPacketFilter.ANY,
            current.session?.trackingMode ?: MapTrackingMode.ALL_FOR_SELECTED_KEY, running = false, startedAt = 0L)
        save(fresh); _state.value = current.copy(session = fresh, error = null); rebuild()
        TrafficRefreshPolicy.mapTrackingActive = false
    }

    fun importMap(raw: String) {
        generation++
        val imported = MapFileStore.decode(raw)
        val session = imported.session.copy(running = false).withoutUnrelatedRoutes()
        save(session)
        prefs.edit().putString(LAST_SELECTED_KEY, session.key).apply()
        TrafficRefreshPolicy.mapTrackingActive = false
        processedCounts.clear(); baselineCounts = session.baseline
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
        val match = selectedPacketMatch(packet, current)
        return when (mode) {
            MapTrackingMode.STARTS_AT_KEY -> match.starts
            MapTrackingMode.ENDS_AT_KEY -> match.ends
            MapTrackingMode.RELATED_TO_KEY -> match.starts || match.ends || match.inRoute || match.reply
            MapTrackingMode.REPORTED_BY_KEY -> match.reported
            MapTrackingMode.ALL_FOR_SELECTED_KEY -> match.starts || match.ends || match.inRoute || match.reported || match.reply
        }
    }

    private fun selectedPacketMatch(packet: LivePacket, current: NetworkMapState): SelectedPacketMatch {
        val key = current.selectedKey
        val r = packet.trackedRelations
        fun Set<String>.has() = any { it.equals(key, true) }
        return SelectedPacketMatch(starts = r.sourceKeys.has(), ends = r.routeEndKeys.has(),
            inRoute = r.routeKeys.has(), reported = r.observerKeys.has(), reply = r.replyKeys.has())
    }

    private fun collectPacket(packet: LivePacket) {
        val expectedGeneration = generation
        val expectedKey = _state.value.selectedKey
        val expectedApi = ConnectionConfigBus.config.value.coreScopeBaseUrl
        processedCounts[packet.stableIdentity] = packet.observationCount
        rechecksInFlight[packet.stableIdentity] = expectedGeneration
        viewModelScope.launch {
            try {
            val details = PacketObservationRepository.load(packet.id)
            if (generation != expectedGeneration || expectedKey != _state.value.selectedKey ||
                expectedApi != ConnectionConfigBus.config.value.coreScopeBaseUrl) return@launch
            if (!details.loadSucceeded) { processedCounts.remove(packet.stableIdentity); return@launch }
            val current = _state.value
            val session = current.session ?: return@launch
            if (!session.running || processedCounts[packet.stableIdentity] != packet.observationCount) return@launch
            val additions = details.routes.mapNotNull { route ->
                RouteTrackingPolicy.event(packet, route, ConnectionConfigBus.config.value.ownPublicKeys,
                    session.key, session.trackingMode)
            }.distinctBy { it.path to it.resolvedPath }
            val updated = session.copy(events = session.events.filterNot { it.packetId == packet.stableIdentity } + additions,
                baseline = session.baseline + (packet.stableIdentity to packet.observationCount),
                capturedPacketIds = session.capturedPacketIds + packet.stableIdentity,
                pendingRechecks = session.pendingRechecks - packet.stableIdentity)
            save(updated); _state.value = current.copy(session = updated); rebuild()
            } finally {
                if (rechecksInFlight[packet.stableIdentity] == expectedGeneration) rechecksInFlight.remove(packet.stableIdentity)
            }
        }
    }

    private fun rebuild(loading: Boolean = _state.value.loadingNodes) {
        // Build 40 could store name-only destinations as physical map routes.
        // They have no proven RF endpoint and must not be rendered or measured.
        val events = _state.value.session?.events.orEmpty()
            .filterNot { it.logicalDestinationUnavailable }
            .filterNot { it.inferredLastHop }
            .filter { event -> MeshPath.isTrackable(event.path) }
            .filter { event -> MapRouteScope.includes(event, _state.value.selectedKey) }
        val selectedHash = _state.value.selectedKey.take(4)
        val selected = (locatedNodes.firstOrNull { it.publicKey.equals(_state.value.selectedKey, true) }
            )?.let {
            MapNodePoint(it.publicKey.take(4), it.lat, it.lon)
        }
        val routedEvents = events
        val metrics = MapRouteMapper.metrics(routedEvents, locatedNodes)
        val longestSegments = metrics.longestRoute.zipWithNext().filter { (_, b) -> b.missingBefore.isEmpty() }.mapTo(mutableSetOf()) { (a, b) ->
            listOf("${a.lat}:${a.lon}", "${b.lat}:${b.lon}").sorted().joinToString("|")
        }
        val edges = MapRouteMapper.edges(routedEvents, locatedNodes).map { edge ->
            val segment = listOf("${edge.from.lat}:${edge.from.lon}", "${edge.to.lat}:${edge.to.lon}").sorted().joinToString("|")
            edge.copy(longestRoute = !edge.reply && !edge.missingGps && segment in longestSegments)
        }
        val nodes = (edges.flatMap { listOf(it.from, it.to) } + listOfNotNull(selected))
            .distinctBy { "${it.hash}:${it.lat}:${it.lon}" }
        _state.value = _state.value.copy(
            edges = edges, nodes = nodes, selectedNode = selected,
            exportRoutes = routedEvents.sortedBy { it.observedAt }.flatMap { MapRouteMapper.splitAtUnknown(MapRouteMapper.resolve(it.path, locatedNodes, it.resolvedPath)) }.filter { it.size > 1 },
            totalDistanceKm = metrics.totalUniqueKm, longestRouteKm = metrics.longestRouteKm,
            loadingNodes = loading, knownNodeCount = locatedNodes.size,
            allRepeaterCount = MapNodeRepository.lastAllRepeaterCount,
            longestRoute = metrics.longestRoute,
            longestRouteIsNewRecord = metrics.longestRouteKm > bestRecordedDistance(_state.value.selectedKey) ||
                (prefs.getBoolean(recordUnreadKey(_state.value.selectedKey), false) && metrics.longestRouteKm == bestRecordedDistance(_state.value.selectedKey)),
            logicalDestinationUnavailable = routedEvents.any { it.logicalDestinationUnavailable },
        )
    }

    private fun commitLongestRouteResult(key: String, distanceKm: Double) {
        if (key.isBlank() || distanceKm <= 0.0) return
        val records = loadLongestRouteRecords().toMutableList()
        val previousBest = bestRecordedDistance(key)
        records += LongestRouteRecord(key.lowercase(), distanceKm)
        while (records.size > MAX_LONGEST_ROUTE_RECORDS_PER_KEY) records.removeAt(0)
        val isRecord = distanceKm > previousBest
        prefs.edit()
            .putString(LONGEST_ROUTE_RECORDS, JSONArray().apply { records.forEach { record ->
                put(JSONObject().apply { put("key", record.key); put("km", record.distanceKm) })
            } }.toString())
            .putBoolean(recordUnreadKey(key), isRecord)
            .putFloat(bestKey(key), maxOf(previousBest, distanceKm).toFloat())
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

    private fun bestKey(key: String) = "longest_best_${key.lowercase()}_${_state.value.session?.trackingMode?.name}"
    private fun bestRecordedDistance(key: String): Double = prefs.getFloat(bestKey(key), 0f).toDouble()

    private fun recordUnreadKey(key: String) = "longest_route_unread_${key.lowercase()}"

    private fun prefKey(key: String) = "session_${key.lowercase()}"
    private fun load(key: String): MapSession = MapSessionJson.decode(prefs.getString(prefKey(key), "").orEmpty(), key)
    private fun save(session: MapSession) { prefs.edit().putString(prefKey(session.key), MapSessionJson.encode(session)).commit() }

    private fun MapSession.withoutUnrelatedRoutes(): MapSession =
        copy(events = MapReplySelection.keepLatestSaved(events.filterNot { it.inferredLastHop })
            .filter { MapRouteScope.includes(it, key) }
            .map { event -> event.copy(longestRouteEligible = event.longestRouteEligible &&
                MapRouteScope.includes(event, key)) })

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

private data class SelectedPacketMatch(
    val starts: Boolean = false,
    val ends: Boolean = false,
    val inRoute: Boolean = false,
    val reported: Boolean = false,
    val reply: Boolean = false,
    val uncertainStart: Boolean = false,
    val uncertainEnd: Boolean = false,
    val uncertainStartHash: String = "",
    val uncertainEndHash: String = "",
    val physicalStart: Boolean = false,
    val physicalEnd: Boolean = false,
    val logicalStartOnly: Boolean = false,
    val logicalEndOnly: Boolean = false,
)
