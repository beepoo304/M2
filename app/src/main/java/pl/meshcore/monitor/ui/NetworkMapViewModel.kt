package pl.meshcore.monitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.meshcore.monitor.data.*
import org.json.JSONObject

data class NetworkMapState(
    val selectedKey: String = "",
    val selectedName: String = "",
    val session: MapSession? = null,
    val edges: List<MapEdge> = emptyList(),
    val nodes: List<MapNodePoint> = emptyList(),
    val selectedNode: MapNodePoint? = null,
    val loadingNodes: Boolean = true,
    val knownNodeCount: Int = 0,
    val error: String? = null,
    val exportRoutes: List<List<MapNodePoint>> = emptyList(),
)

class NetworkMapViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("map_tracking", 0)
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
                        session.filter.accepts(packet.payloadType) && matchesSelected(packet, current)
                }.forEach { packet ->
                    if ((processedCounts[packet.id] ?: -1) != packet.observationCount) collectPacket(packet)
                }
            }
        }
    }

    fun select(key: String, name: String) {
        if (_state.value.selectedKey == key) return
        _state.value.session?.takeIf { it.running }?.let { save(it.copy(running = false)) }
        val loaded = load(key)
        _state.value = _state.value.copy(selectedKey = key, selectedName = name, session = loaded.copy(running = false), error = null)
        processedCounts.clear(); rebuild()
    }

    fun setFilter(filter: MapPacketFilter) {
        val session = _state.value.session ?: return
        val updated = session.copy(filter = filter); save(updated)
        _state.value = _state.value.copy(session = updated)
    }

    fun start() {
        val session = _state.value.session ?: return
        baselineCounts = SharedLiveRepository.state.value.packets.associate { it.id to it.observationCount }
        processedCounts.putAll(baselineCounts)
        val updated = session.copy(running = true, startedAt = if (session.startedAt == 0L) System.currentTimeMillis() else session.startedAt)
        save(updated); _state.value = _state.value.copy(session = updated, error = null)
    }

    fun stop() {
        val session = _state.value.session ?: return
        val updated = session.copy(running = false); save(updated); _state.value = _state.value.copy(session = updated)
    }

    fun restart() {
        val current = _state.value; if (current.selectedKey.isBlank()) return
        baselineCounts = SharedLiveRepository.state.value.packets.associate { it.id to it.observationCount }
        processedCounts.clear()
        processedCounts.putAll(baselineCounts)
        val fresh = MapSession(current.selectedKey, current.session?.filter ?: MapPacketFilter.ANY, running = false, startedAt = 0L)
        save(fresh); _state.value = current.copy(session = fresh, error = null); rebuild()
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

    private fun matchesSelected(packet: LivePacket, current: NetworkMapState): Boolean {
        if (packet.publicKey.equals(current.selectedKey, true)) return true
        val decoded = packet.decodedJson.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        val name = current.selectedName.trim()
        return name.isNotBlank() && !name.startsWith("Looking up", true) &&
            (decoded?.optString("sender").equals(name, true) || decoded?.optString("name").equals(name, true))
    }

    private fun collectPacket(packet: LivePacket) {
        processedCounts[packet.id] = packet.observationCount
        viewModelScope.launch {
            val details = PacketObservationRepository.load(packet.id)
            val current = _state.value; val session = current.session ?: return@launch
            if (!session.running || session.key != current.selectedKey) return@launch
            val existing = session.events.mapTo(mutableSetOf()) { "${it.packetId}:${it.path.joinToString()}" }
            val additions = details.routes.mapNotNull { route ->
                val path = route.path
                val id = "${packet.id}:${path.joinToString()}"
                if (path.size < 2 || id in existing || path.any { it.length !in setOf(2, 4, 6) }) null
                else MapRouteEvent(packet.id, packet.hash, packet.payloadType, packet.timestamp, System.currentTimeMillis(), path)
            }
            if (additions.isNotEmpty()) {
                val updated = session.copy(events = (session.events + additions).takeLast(5000))
                save(updated); _state.value = current.copy(session = updated); rebuild()
            }
        }
    }

    private fun rebuild(loading: Boolean = _state.value.loadingNodes) {
        val events = _state.value.session?.events.orEmpty()
        val edges = MapRouteMapper.edges(events, locatedNodes)
        val selectedHash = _state.value.selectedKey.take(4)
        val selected = (locatedNodes.firstOrNull { it.publicKey.equals(_state.value.selectedKey, true) }
            ?: locatedNodes.firstOrNull {
                selectedHash.length == 4 && it.publicKey.startsWith(selectedHash, ignoreCase = true)
            })?.let {
            MapNodePoint(it.publicKey.take(4), it.lat, it.lon)
        }
        val nodes = (edges.flatMap { listOf(it.from, it.to) } + listOfNotNull(selected))
            .distinctBy { "${it.hash}:${it.lat}:${it.lon}" }
        _state.value = _state.value.copy(
            edges = edges, nodes = nodes, selectedNode = selected,
            exportRoutes = events.sortedBy { it.observedAt }.map { MapRouteMapper.resolve(it.path, locatedNodes) }.filter { it.size > 1 },
            loadingNodes = loading, knownNodeCount = locatedNodes.size,
        )
    }

    private fun prefKey(key: String) = "session_${key.lowercase()}"
    private fun load(key: String): MapSession = MapSessionJson.decode(prefs.getString(prefKey(key), "").orEmpty(), key)
    private fun save(session: MapSession) { prefs.edit().putString(prefKey(session.key), MapSessionJson.encode(session)).apply() }
}
