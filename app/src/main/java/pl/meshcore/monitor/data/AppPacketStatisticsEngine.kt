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

data class AppPacketStatistics(
    val startedAtMs: Long = System.currentTimeMillis(),
    val packetCount: Long = 0L,
    val accumulatedRunningMs: Long = 0L,
)

object AppPacketStatisticsEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AppPacketStatistics())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private lateinit var appContext: Context
    private var processStartedAt = 0L
    private var accumulatedBeforeProcess = 0L
    private val seen = LinkedHashSet<String>()

    @Synchronized fun start(context: Context) {
        appContext = context.applicationContext
        if (job?.isActive == true) return
        load()
        processStartedAt = System.currentTimeMillis()
        accumulatedBeforeProcess = _state.value.accumulatedRunningMs
        job = scope.launch {
            launch {
                SharedLiveRepository.state.collect { live ->
                    var added = 0
                    live.packets.asReversed().forEach { packet ->
                        val identity = packet.hash.trim().lowercase().takeIf(String::isNotBlank)
                            ?: "${packet.payloadType}:${packet.timestamp}:${packet.rawHex.lowercase()}"
                        if (seen.add(identity)) added++
                    }
                    trimSeen()
                    if (added > 0) {
                        _state.value = currentState().copy(packetCount = _state.value.packetCount + added)
                        save()
                    }
                }
            }
            while (isActive) {
                delay(PERSIST_RUNNING_TIME_MS)
                _state.value = currentState()
                save()
            }
        }
    }

    fun reset() {
        if (!::appContext.isInitialized) return
        val now = System.currentTimeMillis()
        seen.clear()
        processStartedAt = now
        accumulatedBeforeProcess = 0L
        _state.value = AppPacketStatistics(startedAtMs = now)
        save()
    }

    suspend fun stop() {
        if (!::appContext.isInitialized) return
        _state.value = currentState()
        save()
        job?.cancelAndJoin()
        job = null
    }

    private fun currentState(): AppPacketStatistics = _state.value.copy(
        accumulatedRunningMs = accumulatedBeforeProcess +
            (System.currentTimeMillis() - processStartedAt).coerceAtLeast(0L),
    )

    private fun load() {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        _state.value = AppPacketStatistics(
            startedAtMs = prefs.getLong("startedAt", now),
            packetCount = prefs.getLong("packetCount", 0L),
            accumulatedRunningMs = prefs.getLong("runningMs", 0L),
        )
        seen.clear()
        runCatching { JSONArray(prefs.getString("seen", "[]")) }.getOrNull()?.let { values ->
            for (index in 0 until values.length()) values.optString(index).takeIf(String::isNotBlank)?.let(seen::add)
        }
        trimSeen()
    }

    private fun save() {
        val value = _state.value
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("startedAt", value.startedAtMs)
            .putLong("packetCount", value.packetCount)
            .putLong("runningMs", value.accumulatedRunningMs)
            .putString("seen", JSONArray(seen.toList()).toString())
            .apply()
    }

    private fun trimSeen() {
        while (seen.size > MAX_SEEN_PACKET_IDS) seen.remove(seen.first())
    }

    private const val PREFS = "app_packet_statistics"
    private const val MAX_SEEN_PACKET_IDS = 10_000
    private const val PERSIST_RUNNING_TIME_MS = 60_000L
}
