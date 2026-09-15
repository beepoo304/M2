package pl.meshcore.monitor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocatedNode(val publicKey: String, val lat: Double, val lon: Double)

object MapNodeRepository {
    private val _nodes = MutableStateFlow<List<LocatedNode>>(emptyList())
    val nodes = _nodes.asStateFlow()
    private val _directory = MutableStateFlow<List<NodeGpsInfo>>(emptyList())
    val directory = _directory.asStateFlow()
    private var loadedBase = ""
    private var loadedAt = 0L
    private val mutex = kotlinx.coroutines.sync.Mutex()
    @Volatile var lastAllRepeaterCount: Int = 0
        private set
    suspend fun load(): List<LocatedNode> = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
        val base = ConnectionConfigBus.config.value.coreScopeBaseUrl.trimEnd('/')
        if (loadedBase == base && System.currentTimeMillis() - loadedAt < 3_600_000L) return@withContext _nodes.value
        if (loadedBase != base) { _nodes.value = emptyList(); _directory.value = emptyList(); loadedBase = base }
        val directory = mutableListOf<NodeGpsInfo>()
        val loaded = runCatching {
            NetworkModule.client.newCall(Request.Builder().url("$base/api/nodes?limit=3000").build())
                .execute().use { response ->
                    check(response.isSuccessful) { "GPS API HTTP ${response.code}" }
                    val nodes = JSONObject(response.body?.string().orEmpty()).optJSONArray("nodes") ?: error("Missing nodes")
                    lastAllRepeaterCount = nodes.length()
                    buildList { for (i in 0 until nodes.length()) nodes.optJSONObject(i)?.let { node ->
                        val key = node.optString("public_key").uppercase()
                        if (key.length == 64 && MeshPath.isReliableHop(key)) {
                            val lat = node.optDouble("lat", Double.NaN)
                            val lon = node.optDouble("lon", Double.NaN)
                            val hasGps = !node.isNull("lat") && !node.isNull("lon") &&
                                lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
                                !(abs(lat) < 0.01 && abs(lon) < 0.01)
                            directory += NodeGpsInfo(key, hasGps, node.optString("role").equals("repeater", true))
                        }
                        if (key.length == 64 && MeshPath.isReliableHop(key) && node.has("lat") && node.has("lon") && !node.isNull("lat") && !node.isNull("lon")) {
                            val lat = node.optDouble("lat")
                            val lon = node.optDouble("lon")
                            val valid = lat.isFinite() && lon.isFinite() &&
                                lat in -90.0..90.0 && lon in -180.0..180.0 &&
                                !(abs(lat) < 0.01 && abs(lon) < 0.01)
                            if (valid) add(LocatedNode(key, lat, lon))
                        }
                    } }
                }
        }.getOrNull()
        if (loaded != null && loadedBase == base) {
            _directory.value = directory.toList(); _nodes.value = loaded; loadedAt = System.currentTimeMillis()
        }
        _nodes.value
        } finally { mutex.unlock() }
    }
}
