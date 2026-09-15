package pl.meshcore.monitor.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class AppPacketStatistics(
    val startedAtMs: Long = System.currentTimeMillis(),
    val packetCount: Long = 0L,
    val accumulatedRunningMs: Long = 0L,
    val brokerRunningMs: Map<String, Long> = emptyMap(),
)

object AppPacketStatisticsEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AppPacketStatistics())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private lateinit var appContext: Context
    private var processStartedAt = 0L
    private var accumulatedBeforeProcess = 0L
    private var activeBroker = ""
    private var brokerStartedAt = 0L
    private var brokerTotals = mutableMapOf<String, Long>()
    private val seen = LinkedHashSet<String>()

    @Synchronized fun start(context: Context) {
        appContext = context.applicationContext
        if (job?.isActive == true) return
        load()
        processStartedAt = android.os.SystemClock.elapsedRealtime()
        accumulatedBeforeProcess = _state.value.accumulatedRunningMs
        activeBroker = ConnectionConfigBus.config.value.coreScopeBaseUrl
        brokerStartedAt = processStartedAt
        brokerTotals = _state.value.brokerRunningMs.toMutableMap()
        job = scope.launch {
            launch { ConnectionConfigBus.config.collect { config -> switchBroker(config.coreScopeBaseUrl) } }
            launch {
                SharedLiveRepository.state.collect { live ->
                    acceptPackets(live.packets)
                }
            }
            while (isActive) {
                delay(PERSIST_RUNNING_TIME_MS)
                persistCurrent()
            }
        }
    }

    @Synchronized private fun acceptPackets(packets: List<LivePacket>) {
        val added = packets.count { packet ->
            seen.add(packet.hash.trim().lowercase().ifBlank { "${packet.payloadType}:${packet.timestamp}:${packet.rawHex.lowercase()}" })
        }
        if (added > 0) {
            _state.value = currentState().copy(packetCount = _state.value.packetCount + added)
            save()
        }
    }

    @Synchronized fun reset() {
        if (!::appContext.isInitialized) return
        val now = System.currentTimeMillis()
        seen.clear()
        SharedLiveRepository.state.value.packets.forEach { packet -> seen += packet.hash.trim().lowercase().ifBlank { "${packet.payloadType}:${packet.timestamp}:${packet.rawHex.lowercase()}" } }
        processStartedAt = android.os.SystemClock.elapsedRealtime()
        brokerTotals.clear()
        brokerStartedAt = processStartedAt
        accumulatedBeforeProcess = 0L
        _state.value = AppPacketStatistics(startedAtMs = now)
        save()
    }

    suspend fun stop() {
        if (!::appContext.isInitialized) return
        job?.cancelAndJoin()
        persistCurrent()
        job = null
    }

    @Synchronized private fun persistCurrent() { _state.value = currentState(); save() }

    @Synchronized private fun switchBroker(base: String) {
        if (base == activeBroker) return
        val now = android.os.SystemClock.elapsedRealtime()
        brokerTotals[activeBroker] = (brokerTotals[activeBroker] ?: 0L) + (now - brokerStartedAt).coerceAtLeast(0L)
        activeBroker = base
        brokerStartedAt = now
        persistCurrent()
    }

    @Synchronized private fun currentState(): AppPacketStatistics = _state.value.copy(
        brokerRunningMs = brokerTotals.toMutableMap().apply {
            this[activeBroker] = (this[activeBroker] ?: 0L) + (android.os.SystemClock.elapsedRealtime() - brokerStartedAt).coerceAtLeast(0L)
        },
        accumulatedRunningMs = accumulatedBeforeProcess +
            (android.os.SystemClock.elapsedRealtime() - processStartedAt).coerceAtLeast(0L),
    )

    private fun load() {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        _state.value = AppPacketStatistics(
            startedAtMs = prefs.getLong("startedAt", now),
            packetCount = prefs.getLong("packetCount", 0L),
            accumulatedRunningMs = prefs.getLong("runningMs", 0L),
            brokerRunningMs = runCatching {
                val values = JSONObject(prefs.getString("brokerRunningMs", "{}").orEmpty())
                values.keys().asSequence().associateWith { values.optLong(it) }
            }.getOrDefault(emptyMap()),
        )
        seen.clear()
        runCatching { JSONArray(prefs.getString("seen", "[]")) }.getOrNull()?.let { values ->
            for (index in 0 until values.length()) values.optString(index).takeIf(String::isNotBlank)?.let(seen::add)
        }
    }

    @Synchronized private fun save() {
        val value = _state.value
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("startedAt", value.startedAtMs)
            .putLong("packetCount", value.packetCount)
            .putLong("runningMs", value.accumulatedRunningMs)
            .putString("brokerRunningMs", JSONObject(value.brokerRunningMs).toString())
            .putString("seen", JSONArray(seen.toList()).toString())
            .apply()
    }

    private const val PREFS = "app_packet_statistics"
    private const val PERSIST_RUNNING_TIME_MS = 60_000L
}
