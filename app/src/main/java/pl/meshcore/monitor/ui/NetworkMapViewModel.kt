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
        val match = selectedPacketMatch(packet, current)
        return when (mode) {
            MapTrackingMode.STARTS_AT_KEY -> match.starts
            MapTrackingMode.ENDS_AT_KEY -> match.ends
            MapTrackingMode.RELATED_TO_KEY -> match.starts || match.ends || match.inRoute
            MapTrackingMode.REPORTED_BY_KEY -> match.reported
            MapTrackingMode.ALL_FOR_SELECTED_KEY -> match.starts || match.ends || match.inRoute || match.reported
        }
    }

    private fun selectedPacketMatch(packet: LivePacket, current: NetworkMapState): SelectedPacketMatch {
        val selected = current.selectedKey
        val relations = packet.trackedRelations
        val decoded = packet.decodedJson.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        val sourceHash = decoded?.optString("srcHash").orEmpty()
        val destinationHash = decoded?.optString("destHash").orEmpty()
        val exactSourceHash = sourceHash.length >= 4 && selected.startsWith(sourceHash, true)
        val exactDestinationHash = destinationHash.length >= 4 && selected.startsWith(destinationHash, true)
        val uncertainSource = false
        val uncertainDestination = false
        val name = current.selectedName.trim()
        val usableName = name.isNotBlank() && !name.startsWith("Looking up", true)
        val namedSource = usableName && (decoded?.optString("sender").equals(name, true) ||
            decoded?.optString("name").equals(name, true))
        val namedDestination = usableName && TrackedMention.contains(decoded?.optString("text").orEmpty(), setOf(name.lowercase()))
        val physicalStart = exactSourceHash || uncertainSource ||
            relations.sourceKeys.any { it.equals(selected, true) } && !namedSource
        val physicalEnd = exactDestinationHash || uncertainDestination ||
            relations.destinationKeys.any { it.equals(selected, true) } && !namedDestination
        return SelectedPacketMatch(
            starts = relations.sourceKeys.any { it.equals(selected, true) } || exactSourceHash || uncertainSource || namedSource,
            ends = relations.destinationKeys.any { it.equals(selected, true) } || exactDestinationHash || uncertainDestination || namedDestination,
            inRoute = relations.routeKeys.any { it.equals(selected, true) },
            reported = relations.observerKeys.any { it.equals(selected, true) },
            uncertainStart = uncertainSource,
            uncertainEnd = uncertainDestination,
            uncertainStartHash = sourceHash.takeIf { uncertainSource }.orEmpty().uppercase(),
            uncertainEndHash = destinationHash.takeIf { uncertainDestination }.orEmpty().uppercase(),
            physicalStart = physicalStart,
            physicalEnd = physicalEnd,
            logicalStartOnly = namedSource && !physicalStart,
            logicalEndOnly = namedDestination && !physicalEnd,
        )
    }

    private fun collectPacket(packet: LivePacket) {
        processedCounts[packet.id] = packet.observationCount
        viewModelScope.launch {
            val details = PacketObservationRepository.load(packet.id)
            if (!details.loadSucceeded) {
                if (processedCounts[packet.id] == packet.observationCount) processedCounts.remove(packet.id)
                return@launch
            }
            val current = _state.value; val session = current.session ?: return@launch
            if (!session.running || session.key != current.selectedKey) return@launch
            val existing = session.events.mapTo(mutableSetOf()) { "${it.packetId}:${it.path.joinToString()}" }
            val packetMatch = selectedPacketMatch(packet, current)
            val additions = details.routes.mapNotNull { route ->
                if (route.path.any { it.length < 4 }) return@mapNotNull null
                val selectedKey = current.selectedKey
                val selectedHash = selectedKey.take(4).uppercase()
                val routeRelations = TrackedKeyMatcher.resolvedRoute(route.path, route.resolvedPath, setOf(selectedKey))
                val routeMatch = routeRelations.routeKeys.any { it.equals(selectedKey, true) }
                val observerMatch = TrackedKeyMatcher.observer(route.observerPublicKey, setOf(selectedKey)).observerKeys.isNotEmpty()
                val sourceMatch = packetMatch.starts
                val destinationMatch = packetMatch.ends
                val accepted = when (session.trackingMode) {
                    MapTrackingMode.STARTS_AT_KEY -> sourceMatch
                    MapTrackingMode.ENDS_AT_KEY -> destinationMatch && !packetMatch.logicalEndOnly
                    MapTrackingMode.RELATED_TO_KEY -> sourceMatch ||
                        destinationMatch && !packetMatch.logicalEndOnly || routeMatch
                    MapTrackingMode.REPORTED_BY_KEY -> observerMatch
                    MapTrackingMode.ALL_FOR_SELECTED_KEY -> sourceMatch ||
                        destinationMatch && !packetMatch.logicalEndOnly || routeMatch || observerMatch
                }
                if (!accepted) return@mapNotNull null
                val longestEligible = when (session.trackingMode) {
                    MapTrackingMode.STARTS_AT_KEY -> sourceMatch
                    MapTrackingMode.ENDS_AT_KEY -> destinationMatch && !packetMatch.logicalEndOnly
                    MapTrackingMode.RELATED_TO_KEY -> sourceMatch ||
                        destinationMatch && !packetMatch.logicalEndOnly || routeMatch
                    MapTrackingMode.REPORTED_BY_KEY -> observerMatch
                    MapTrackingMode.ALL_FOR_SELECTED_KEY -> sourceMatch || destinationMatch && !packetMatch.logicalEndOnly || routeMatch || observerMatch
                }
                val directEmptyPath = packet.payloadType in 0..2 && route.path.isEmpty()
                var path = when {
                    directEmptyPath && sourceMatch && route.observerPublicKey.length >= 4 -> listOf(
                        selectedHash,
                        route.observerPublicKey.take(4).uppercase(),
                    ).distinct()
                    packet.payloadType == 9 -> canonicalizeTrackedTracePath(MeshPath.normalizeTrace(route.path))
                    else -> route.path
                }
                // A decoded channel sender name is tied to a saved key. Include
                // that source before the API's first recorded repeater.
                val exactKeySource = packet.trackedRelations.sourceKeys.any { it.equals(selectedKey, true) }
                if (exactKeySource && !path.firstOrNull().equals(selectedHash, true)) path = listOf(selectedHash) + path
                if (packetMatch.uncertainStartHash.isNotBlank() &&
                    !path.firstOrNull().equals(packetMatch.uncertainStartHash, true)) {
                    path = listOf(packetMatch.uncertainStartHash) + path
                }
                if (packetMatch.physicalEnd || observerMatch) {
                    path = when {
                        path.lastOrNull().equals(selectedHash, true) -> path
                        observerMatch && path.lastOrNull()?.length == 2 && selectedHash.startsWith(path.last(), true) ->
                            path.dropLast(1) + selectedHash
                        else -> path + selectedHash
                    }
                }
                if (packetMatch.uncertainEndHash.isNotBlank() &&
                    !path.lastOrNull().equals(packetMatch.uncertainEndHash, true)) {
                    path = path + packetMatch.uncertainEndHash
                }
                path = path.fold(mutableListOf()) { result, hop ->
                    if (!result.lastOrNull().equals(hop, true)) result += hop
                    result
                }
                val id = "${packet.id}:${path.joinToString()}"
                if (path.distinct().size < 2 || id in existing || path.any { it.length !in setOf(2, 4, 6) }) null
                else MapRouteEvent(packet.id, packet.hash, packet.payloadType, packet.timestamp,
                    System.currentTimeMillis(), path,
                    uncertainAttribution = path.any { it.length == 2 } || packetMatch.uncertainStart || packetMatch.uncertainEnd,
                    longestRouteEligible = longestEligible,
                    logicalDestinationUnavailable = packetMatch.logicalEndOnly)
            }.distinctBy { "${it.packetId}:${it.path.joinToString()}" }
            if (additions.isNotEmpty()) {
                val updated = session.copy(events = (session.events + additions).takeLast(5000))
                save(updated); _state.value = current.copy(session = updated); rebuild()
            }
        }
    }

    private fun rebuild(loading: Boolean = _state.value.loadingNodes) {
        // Build 40 could store name-only destinations as physical map routes.
        // They have no proven RF endpoint and must not be rendered or measured.
        val events = _state.value.session?.events.orEmpty()
            .filterNot { it.logicalDestinationUnavailable }
            .filter { event -> event.path.all { it.length >= 4 } }
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
            longestRouteIsNewRecord = prefs.getBoolean(recordUnreadKey(_state.value.selectedKey), false) ||
                (_state.value.session?.running == true && metrics.longestRouteKm > bestRecordedDistance(_state.value.selectedKey)),
            logicalDestinationUnavailable = routedEvents.any { it.logicalDestinationUnavailable },
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

    private fun bestRecordedDistance(key: String): Double = loadLongestRouteRecords().asSequence()
        .filter { it.key.equals(key, true) }
        .maxOfOrNull(LongestRouteRecord::distanceKm) ?: 0.0

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

private data class SelectedPacketMatch(
    val starts: Boolean = false,
    val ends: Boolean = false,
    val inRoute: Boolean = false,
    val reported: Boolean = false,
    val uncertainStart: Boolean = false,
    val uncertainEnd: Boolean = false,
    val uncertainStartHash: String = "",
    val uncertainEndHash: String = "",
    val physicalStart: Boolean = false,
    val physicalEnd: Boolean = false,
    val logicalStartOnly: Boolean = false,
    val logicalEndOnly: Boolean = false,
)
