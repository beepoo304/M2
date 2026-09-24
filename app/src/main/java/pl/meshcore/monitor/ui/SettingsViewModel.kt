package pl.meshcore.monitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import pl.meshcore.monitor.data.ApiHealthEntry
import pl.meshcore.monitor.data.ApiHealthLogStore
import pl.meshcore.monitor.data.ConnectionConfig
import pl.meshcore.monitor.data.ConnectionConfigBus
import pl.meshcore.monitor.data.DEFAULT_OWN_PUBLIC_KEYS
import pl.meshcore.monitor.data.NetworkModule
import pl.meshcore.monitor.data.SecureChannelStore
import pl.meshcore.monitor.data.SharedLiveRepository
import pl.meshcore.monitor.data.TrackedMention
import pl.meshcore.monitor.data.ExportLocationStore
import java.time.Instant
import kotlinx.coroutines.Job

data class DeviceNeighbour(val hash: String, val name: String, val count: Int)
data class DeviceRptInfo(
    val role: String = "",
    val lastHeard: String = "",
    val usefulness: Double? = null,
    val usefulnessGrade: String? = null,
    val trafficShare: Double? = null,
    val bridgeScore: Double? = null,
    val coverage: Double? = null,
    val redundancy: Double? = null,
    val firstSeen: String = "",
    val totalPackets: Int? = null,
    val totalObservations: Int? = null,
    val packetsToday: Int? = null,
    val averageSnr: Double? = null,
    val averageHops: Double? = null,
)
data class DeviceNeighboursState(
    val deviceKey: String = "",
    val deviceName: String = "",
    val sourceApi: String = "",
    val updatedAtMs: Long = 0L,
    val rptInfo: DeviceRptInfo? = null,
    val neighbours: List<DeviceNeighbour> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = application.getSharedPreferences("connection_settings", 0)
    private val client = NetworkModule.client

    val initialConfig: ConnectionConfig = ConnectionConfig(
        coreScopeBaseUrl = prefs.getString("core_url", "https://live.meshcorekk.xyz")!!,
        ownPublicKeys = prefs.getStringSet("own_public_keys", DEFAULT_OWN_PUBLIC_KEYS) ?: DEFAULT_OWN_PUBLIC_KEYS,
        ownNodeNames = (prefs.getStringSet("own_public_keys", DEFAULT_OWN_PUBLIC_KEYS) ?: DEFAULT_OWN_PUBLIC_KEYS)
            .mapNotNull { prefs.getString("device_name_$it", null) }.map { it.trim().lowercase() }.toSet(),
        ownKeyNames = (prefs.getStringSet("own_public_keys", DEFAULT_OWN_PUBLIC_KEYS) ?: DEFAULT_OWN_PUBLIC_KEYS)
            .mapNotNull { key -> prefs.getString("device_name_$key", null)?.let { key to it.trim().lowercase() } }.toMap(),
        savedChannels = SecureChannelStore(application).load(),
    ).also(ConnectionConfigBus::update)

    data class DeviceEntry(
        val publicKey: String,
        val name: String,
        val counterStartedAt: Long,
        val packetCount: Long,
    )
    private val createdAt = System.currentTimeMillis()
    private val _devices = MutableStateFlow(initialConfig.ownPublicKeys.map {
        DeviceEntry(
            it,
            prefs.getString("device_name_$it", null) ?: "Looking up name…",
            prefs.getLong("packet_counter_start_$it", createdAt),
            prefs.getLong("packet_counter_count_$it", 0L),
        )
    })
    val devices = _devices.asStateFlow()
    private val _savedApis = MutableStateFlow(loadSavedApis(initialConfig.coreScopeBaseUrl))
    val savedApis = _savedApis.asStateFlow()
    private val apiHistoryStore = ApiHealthLogStore(application)
    private val _apiHealthLog = MutableStateFlow(apiHistoryStore.load(initialConfig.coreScopeBaseUrl))
    val apiHealthLog = _apiHealthLog.asStateFlow()
    private val _apiOnline = MutableStateFlow<Boolean?>(null)
    val apiOnline = _apiOnline.asStateFlow()
    private val _exportLocation = MutableStateFlow(ExportLocationStore.selectedUri(application))
    val exportLocation = _exportLocation.asStateFlow()
    private val _neighbours = MutableStateFlow<DeviceNeighboursState?>(null)
    val neighbours = _neighbours.asStateFlow()
    private var neighbourRefreshJob: Job? = null

    init {
        migratePacketCounters()
        prefs.edit().apply {
            _devices.value.forEach { if (!prefs.contains("packet_counter_start_${it.publicKey}"))
                putLong("packet_counter_start_${it.publicKey}", it.counterStartedAt) }
        }.apply()
        resolveNames(retries = 5)
        monitorApi()
        countPackets()
    }

    private fun countPackets() {
        viewModelScope.launch {
            SharedLiveRepository.state.collect { live ->
                var updated = _devices.value
                var changed = false
                updated = updated.map { device ->
                    val seenKey = "packet_counter_seen_ordered_${device.publicKey}"
                    val seen = loadOrderedPacketIds(seenKey)
                    val matches = live.packets.filter { packet ->
                        packet.counterIdentity() !in seen && packet.packetEpochMillis() >= device.counterStartedAt &&
                            packet.matchesDevice(device)
                    }
                    if (matches.isEmpty()) device else {
                        changed = true
                        matches.forEach { seen += it.counterIdentity() }
                        // Keep identities for the lifetime of this counter; API history must not count twice.
                        val count = device.packetCount + matches.size
                        prefs.edit().putLong("packet_counter_count_${device.publicKey}", count)
                            .putString(seenKey, JSONArray(seen.toList()).toString()).apply()
                        device.copy(packetCount = count)
                    }
                }
                if (changed) _devices.value = updated
            }
        }
    }

    private fun pl.meshcore.monitor.data.LivePacket.matchesDevice(device: DeviceEntry): Boolean {
        return trackedRelations.confirmedKeys.any { it.equals(device.publicKey, true) }
    }

    private fun pl.meshcore.monitor.data.LivePacket.packetEpochMillis(): Long =
        pl.meshcore.monitor.data.WarsawTimeFormatter.epochMillis(timestamp) ?: 0L

    private fun pl.meshcore.monitor.data.LivePacket.counterIdentity(): String =
        hash.trim().lowercase().takeIf(String::isNotBlank)?.let { "hash#$it" }
            ?: "raw#$payloadType#$timestamp#${rawHex.trim().lowercase()}"

    private fun loadOrderedPacketIds(key: String): LinkedHashSet<String> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        LinkedHashSet<String>().apply {
            for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }.getOrDefault(linkedSetOf())

    private fun migratePacketCounters() {
        if (prefs.getInt(PACKET_COUNTER_SCHEMA_KEY, 0) >= PACKET_COUNTER_SCHEMA) return
        val now = System.currentTimeMillis()
        val editor = prefs.edit().putInt(PACKET_COUNTER_SCHEMA_KEY, PACKET_COUNTER_SCHEMA)
        _devices.value.forEach { device ->
            editor.putLong("packet_counter_start_${device.publicKey}", now)
                .putLong("packet_counter_count_${device.publicKey}", 0L)
                .remove("packet_counter_seen_${device.publicKey}")
                .remove("packet_counter_seen_ordered_${device.publicKey}")
        }
        editor.commit()
        _devices.value = _devices.value.map { it.copy(counterStartedAt = now, packetCount = 0L) }
    }

    private fun monitorApi() {
        pl.meshcore.monitor.data.ApiHealthMonitor.start(app)
        viewModelScope.launch { pl.meshcore.monitor.data.ApiHealthMonitor.state.collect { values ->
            val value = values[ConnectionConfigBus.config.value.coreScopeBaseUrl.trim().trimEnd('/')]
            _apiHealthLog.value = value?.entries.orEmpty()
            _apiOnline.value = value?.online
        } }
        viewModelScope.launch { ConnectionConfigBus.config.collect { config ->
            val value = pl.meshcore.monitor.data.ApiHealthMonitor.state.value[config.coreScopeBaseUrl.trim().trimEnd('/')]
            _apiHealthLog.value = value?.entries.orEmpty()
            _apiOnline.value = value?.online
        } }
    }

    fun resetApiLog() {
        val base = ConnectionConfigBus.config.value.coreScopeBaseUrl
        pl.meshcore.monitor.data.ApiHealthMonitor.clear(app, base)
        _apiHealthLog.value = emptyList()
    }

    fun addDevice(value: String): Boolean {
        val key = value.trim().lowercase()
        if (!key.matches(Regex("[0-9a-f]{64}"))) return false
        if (_devices.value.none { it.publicKey == key }) {
            val now = System.currentTimeMillis()
            _devices.value = _devices.value + DeviceEntry(
                key, prefs.getString("device_name_$key", null) ?: "Looking up name…", now, 0L)
            prefs.edit().putLong("packet_counter_start_$key", now)
                .putLong("packet_counter_count_$key", 0L)
                .remove("packet_counter_seen_$key").remove("packet_counter_seen_ordered_$key").apply()
            persistDeviceKeys()
            resolveNames(retries = 5)
        }
        return true
    }

    fun addApi(value: String): String? {
        val input = value.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val parsed = input.toHttpUrlOrNull() ?: return null
        if (parsed.host.isBlank()) return null
        val normalized = parsed.newBuilder().fragment(null).query(null).build().toString().trimEnd('/')
        _savedApis.value = (_savedApis.value + normalized).distinct()
        prefs.edit().putStringSet("saved_api_urls", _savedApis.value.toSet()).apply()
        return normalized
    }

    fun removeApi(value: String): Boolean {
        val normalized = value.trim().trimEnd('/')
        val active = ConnectionConfigBus.config.value.coreScopeBaseUrl.trim().trimEnd('/')
        if (normalized == active || normalized == DEFAULT_LIVE_API) return false
        _savedApis.value = _savedApis.value.filterNot { it.trimEnd('/') == normalized }
        prefs.edit().putStringSet("saved_api_urls", _savedApis.value.toSet()).apply()
        return true
    }

    fun saveExportLocation(uri: String?) {
        ExportLocationStore.saveSelection(app, uri)
        _exportLocation.value = uri
    }

    fun exportLocationLabel(uri: String?): String = ExportLocationStore.label(app, uri)

    private fun loadSavedApis(active: String): List<String> {
        val stored = prefs.getStringSet("saved_api_urls", emptySet()).orEmpty()
        return (listOf(active.trim().trimEnd('/')) + stored).filter(String::isNotBlank).distinct()
    }

    fun removeDevice(publicKey: String) {
        _devices.value = _devices.value.filterNot { it.publicKey == publicKey }
        prefs.edit().remove("packet_counter_start_$publicKey")
            .remove("packet_counter_count_$publicKey").remove("packet_counter_seen_$publicKey")
            .remove("packet_counter_seen_ordered_$publicKey").apply()
        persistDeviceKeys()
    }

    fun openNeighbours(device: DeviceEntry) {
        neighbourRefreshJob?.cancel()
        val api = ConnectionConfigBus.config.value.coreScopeBaseUrl.trimEnd('/')
        val cached = loadNeighboursCache(api, device.publicKey)
        _neighbours.value = cached ?: DeviceNeighboursState(
            deviceKey = device.publicKey, deviceName = device.name, sourceApi = api,
        )
        if (cached == null || cached.rptInfo == null ||
            System.currentTimeMillis() - cached.updatedAtMs >= NEIGHBOUR_REFRESH_INTERVAL_MS) {
            refreshNeighbours()
        }
        neighbourRefreshJob = viewModelScope.launch {
            while (true) {
                delay(NEIGHBOUR_REFRESH_INTERVAL_MS)
                refreshNeighbours()
            }
        }
    }

    fun closeNeighbours() {
        neighbourRefreshJob?.cancel()
        neighbourRefreshJob = null
        _neighbours.value = null
    }

    fun refreshNeighbours() {
        val current = _neighbours.value ?: return
        if (current.loading) return
        val api = ConnectionConfigBus.config.value.coreScopeBaseUrl.trimEnd('/')
        _neighbours.value = current.copy(sourceApi = api, loading = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { loadNeighbours(api, current.deviceKey, current.deviceName) }
            result.onSuccess { fresh ->
                saveNeighboursCache(fresh)
                _neighbours.value = fresh
            }.onFailure { error ->
                _neighbours.value = _neighbours.value?.copy(
                    loading = false,
                    error = error.message?.takeIf(String::isNotBlank) ?: "Cannot load neighbours",
                )
            }
        }
    }

    private fun loadNeighbours(api: String, key: String, fallbackName: String): Result<DeviceNeighboursState> = runCatching {
        val detailRequest = Request.Builder().url("$api/api/nodes/${key.lowercase()}").build()
        val healthRequest = Request.Builder().url("$api/api/nodes/${key.lowercase()}/health").build()
        val nodeRequest = Request.Builder().url("$api/api/nodes?limit=3000").build()
        val detail = client.newCall(detailRequest).execute().use { response ->
            if (!response.isSuccessful) error("API returned HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
        val health = runCatching {
            client.newCall(healthRequest).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty())
            }
        }.getOrNull()
        val nodes = client.newCall(nodeRequest).execute().use { response ->
            if (!response.isSuccessful) error("Node list returned HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty()).optJSONArray("nodes") ?: JSONArray()
        }
        val nodeByHash = mutableMapOf<String, MutableList<Pair<String, String>>>()
        for (index in 0 until nodes.length()) nodes.optJSONObject(index)?.let { node ->
            val publicKey = node.optString("public_key").lowercase()
            if (publicKey.length == 64) nodeByHash.getOrPut(publicKey.take(4).uppercase()) { mutableListOf() }
                .add(publicKey to node.optString("name").ifBlank { "Unknown RPT" })
        }
        val counts = linkedMapOf<String, Int>()
        val adverts = detail.optJSONArray("recentAdverts") ?: JSONArray()
        for (advertIndex in 0 until adverts.length()) {
            val observations = adverts.optJSONObject(advertIndex)?.optJSONArray("observations") ?: continue
            for (observationIndex in 0 until observations.length()) {
                val rawPath = observations.optJSONObject(observationIndex)?.optString("path_json").orEmpty()
                val path = runCatching { JSONArray(rawPath) }.getOrNull() ?: continue
                val firstHop = path.optString(0).trim().uppercase()
                if (firstHop.length >= 4) counts[firstHop.take(4)] = (counts[firstHop.take(4)] ?: 0) + 1
            }
        }
        val neighbours = counts.map { (hash, count) ->
            val candidates = nodeByHash[hash].orEmpty()
            DeviceNeighbour(hash, candidates.singleOrNull()?.second ?: if (candidates.isEmpty()) "Unknown RPT" else "Ambiguous RPT", count)
        }.sortedByDescending(DeviceNeighbour::count)
        val node = detail.optJSONObject("node") ?: JSONObject()
        val stats = health?.optJSONObject("stats")
        val rptInfo = DeviceRptInfo(
            role = node.optString("role"),
            lastHeard = node.optString("last_heard").ifBlank { node.optString("last_seen") },
            usefulness = node.optionalDouble("usefulness_score"),
            usefulnessGrade = node.optString("usefulness_grade").ifBlank { null },
            trafficShare = node.optionalDouble("traffic_share_score"),
            bridgeScore = node.optionalDouble("bridge_score"),
            coverage = node.optionalDouble("coverage_score"),
            redundancy = node.optionalDouble("redundancy_score"),
            firstSeen = node.optString("first_seen"),
            totalPackets = stats?.optionalInt("totalPackets"),
            totalObservations = stats?.optionalInt("totalObservations"),
            packetsToday = stats?.optionalInt("packetsToday"),
            averageSnr = stats?.optionalDouble("avgSnr"),
            averageHops = stats?.optionalDouble("avgHops"),
        )
        DeviceNeighboursState(
            deviceKey = key,
            deviceName = node.optString("name").ifBlank { fallbackName },
            sourceApi = api,
            updatedAtMs = System.currentTimeMillis(),
            rptInfo = rptInfo,
            neighbours = neighbours,
        )
    }

    private fun neighbourCacheKey(api: String, key: String) =
        "neighbours_${api.lowercase().hashCode()}_${key.lowercase()}"

    private fun saveNeighboursCache(value: DeviceNeighboursState) {
        prefs.edit().putString(neighbourCacheKey(value.sourceApi, value.deviceKey), JSONObject().apply {
            put("deviceKey", value.deviceKey); put("deviceName", value.deviceName)
            put("sourceApi", value.sourceApi); put("updatedAtMs", value.updatedAtMs)
            value.rptInfo?.let { info -> put("rptInfo", JSONObject().apply {
                put("role", info.role); put("lastHeard", info.lastHeard)
                putNullable("usefulness", info.usefulness); putNullable("usefulnessGrade", info.usefulnessGrade)
                putNullable("trafficShare", info.trafficShare); putNullable("bridgeScore", info.bridgeScore)
                putNullable("coverage", info.coverage); putNullable("redundancy", info.redundancy)
                put("firstSeen", info.firstSeen); putNullable("totalPackets", info.totalPackets)
                putNullable("totalObservations", info.totalObservations); putNullable("packetsToday", info.packetsToday)
                putNullable("averageSnr", info.averageSnr); putNullable("averageHops", info.averageHops)
            }) }
            put("neighbours", JSONArray().apply { value.neighbours.forEach { neighbour -> put(JSONObject().apply {
                put("hash", neighbour.hash); put("name", neighbour.name); put("count", neighbour.count)
            }) } })
        }.toString()).apply()
    }

    private fun loadNeighboursCache(api: String, key: String): DeviceNeighboursState? = runCatching {
        val root = JSONObject(prefs.getString(neighbourCacheKey(api, key), null) ?: return null)
        val values = root.optJSONArray("neighbours") ?: JSONArray()
        val info = root.optJSONObject("rptInfo")?.let { item -> DeviceRptInfo(
            role = item.optString("role"), lastHeard = item.optString("lastHeard"),
            usefulness = item.optionalDouble("usefulness"), usefulnessGrade = item.optString("usefulnessGrade").ifBlank { null },
            trafficShare = item.optionalDouble("trafficShare"), bridgeScore = item.optionalDouble("bridgeScore"),
            coverage = item.optionalDouble("coverage"), redundancy = item.optionalDouble("redundancy"),
            firstSeen = item.optString("firstSeen"), totalPackets = item.optionalInt("totalPackets"),
            totalObservations = item.optionalInt("totalObservations"), packetsToday = item.optionalInt("packetsToday"),
            averageSnr = item.optionalDouble("averageSnr"), averageHops = item.optionalDouble("averageHops"),
        ) }
        DeviceNeighboursState(
            deviceKey = root.optString("deviceKey", key), deviceName = root.optString("deviceName"),
            sourceApi = root.optString("sourceApi", api), updatedAtMs = root.optLong("updatedAtMs"),
            rptInfo = info,
            neighbours = buildList { for (index in 0 until values.length()) values.optJSONObject(index)?.let { item ->
                add(DeviceNeighbour(item.optString("hash"), item.optString("name"), item.optInt("count")))
            } },
        )
    }.getOrNull()

    private fun JSONObject.optionalDouble(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name).takeUnless(Double::isNaN) else null

    private fun JSONObject.optionalInt(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun JSONObject.putNullable(name: String, value: Any?) {
        if (value != null) put(name, value)
    }

    private fun persistDeviceKeys() {
        val keys = _devices.value.map { it.publicKey }.toSet()
        prefs.edit().putStringSet("own_public_keys", keys).apply()
        ConnectionConfigBus.update(ConnectionConfigBus.config.value.copy(
            ownPublicKeys = keys,
            ownNodeNames = _devices.value.map { it.name.trim().lowercase() }.filterNot { it.startsWith("looking up") }.toSet(),
            ownKeyNames = _devices.value.filterNot { it.name.startsWith("Looking up") }
                .associate { it.publicKey to it.name.trim().lowercase() },
        ))
    }

    private fun resolveNames(retries: Int = 1) {
        viewModelScope.launch {
            repeat(retries) { attempt ->
                val names = withContext(Dispatchers.IO) {
                    runCatching {
                    val base = ConnectionConfigBus.config.value.coreScopeBaseUrl.trimEnd('/')
                    val request = Request.Builder().url("$base/api/nodes?limit=3000").build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use emptyMap<String, String>()
                        val array = JSONObject(response.body?.string().orEmpty()).optJSONArray("nodes")
                            ?: return@use emptyMap<String, String>()
                        buildMap {
                            for (i in 0 until array.length()) array.optJSONObject(i)?.let {
                                val key = it.optString("public_key").lowercase()
                                val name = it.optString("name")
                                if (key.isNotBlank() && name.isNotBlank()) put(key, name)
                            }
                        }
                    }
                    }.getOrDefault(emptyMap())
                }
                if (names.isNotEmpty()) {
                    _devices.value = _devices.value.map { it.copy(name = names[it.publicKey] ?: it.name) }
                    prefs.edit().apply {
                        _devices.value.forEach { if (!it.name.startsWith("Looking up")) putString("device_name_${it.publicKey}", it.name) }
                    }.apply()
                    ConnectionConfigBus.update(ConnectionConfigBus.config.value.copy(
                        ownNodeNames = _devices.value.map { it.name.trim().lowercase() }
                            .filterNot { it.startsWith("looking up") }.toSet(),
                        ownKeyNames = _devices.value.filterNot { it.name.startsWith("Looking up") }
                            .associate { it.publicKey to it.name.trim().lowercase() },
                    ))
                    return@launch
                }
                if (attempt < retries - 1) delay(3_000)
            }
        }
    }

    fun save(config: ConnectionConfig) {
        val previousApi = ConnectionConfigBus.config.value.coreScopeBaseUrl.trimEnd('/')
        prefs.edit()
            .putString("core_url", config.coreScopeBaseUrl)
            .putStringSet("own_public_keys", config.ownPublicKeys)
            .apply()
        ConnectionConfigBus.update(config.copy(
            ownNodeNames = _devices.value.map { it.name.trim().lowercase() }
                .filterNot { it.startsWith("looking up") }.toSet(),
            ownKeyNames = _devices.value.filterNot { it.name.startsWith("Looking up") }
                .associate { it.publicKey to it.name.trim().lowercase() },
            savedChannels = ConnectionConfigBus.config.value.savedChannels,
        ))
        if (_neighbours.value != null && !previousApi.equals(config.coreScopeBaseUrl.trimEnd('/'), true)) {
            _neighbours.value = _neighbours.value?.copy(sourceApi = config.coreScopeBaseUrl.trimEnd('/'), updatedAtMs = 0L)
            refreshNeighbours()
        }
    }

    private companion object {
        const val DEFAULT_LIVE_API = "https://live.meshcorekk.xyz"
        const val API_LOG_LIMIT = 250
        const val API_CHECK_ONLINE_INTERVAL_MS = 60_000L
        const val API_CHECK_DOWN_INTERVAL_MS = 15_000L
        const val SEEN_PACKET_IDS = 2_000
        const val PACKET_COUNTER_SCHEMA_KEY = "packet_counter_schema"
        const val PACKET_COUNTER_SCHEMA = 3
        const val NEIGHBOUR_REFRESH_INTERVAL_MS = 60 * 60_000L
    }
}
