package pl.meshcore.monitor.data

import android.content.Context
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class ChannelStatEvent(
    val id: String, val channel: String, val publicKey: String, val timestamp: String,
    val sent: Boolean, val mention: Boolean,
)

object ChannelStatisticsEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableStateFlow<List<ChannelStatEvent>>(emptyList())
    val events = _events.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private var job: Job? = null
    private lateinit var appContext: Context

    @Synchronized fun start(context: Context) {
        appContext = context.applicationContext
        if (job?.isActive == true) return
        _events.value = load()
        job = scope.launch {
            while (isActive) {
                refresh()
                delay(REFRESH_MS)
            }
        }
    }

    fun reset(publicKey: String) {
        _events.value = _events.value.filterNot { it.publicKey.equals(publicKey, true) }
        save(_events.value)
    }

    fun refreshNow() {
        if (!::appContext.isInitialized || _refreshing.value) return
        scope.launch { refresh() }
    }

    private suspend fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        try {
        val prefs = appContext.getSharedPreferences("connection_settings", Context.MODE_PRIVATE)
        val channels = SecureChannelStore(appContext).load()
        val devices = ConnectionConfigBus.config.value.ownPublicKeys.map { key ->
            key to prefs.getString("device_name_$key", "").orEmpty().trim().lowercase()
        }.filter { it.second.isNotBlank() }
        val cutoff = System.currentTimeMillis() - DAY_MS
        val fresh = buildList {
            val repository = ChannelRepository(appContext)
            channels.forEach { channel ->
                repository.messages(channel).forEach { message ->
                    if (message.epochMillis() < cutoff) return@forEach
                    devices.forEach { (key, name) ->
                        val sender = message.sender.trim()
                        val sent = sender.equals(name, true) || sender.startsWith("$name ", true)
                        val mention = TrackedMention.contains(message.text, setOf(name))
                        if (sent || mention) add(ChannelStatEvent(message.id, channel.name, key,
                            message.timestamp, sent, mention))
                    }
                }
            }
        }
        val activeKeys = devices.mapTo(mutableSetOf()) { it.first.lowercase() }
        _events.value = (fresh + _events.value)
            .filter { it.epochMillis() >= cutoff && it.publicKey.lowercase() in activeKeys }
            .distinctBy { "${it.publicKey.lowercase()}:${it.channel.lowercase()}:${it.id}" }
            .sortedByDescending { it.timestamp }
        save(_events.value)
        } finally {
            _refreshing.value = false
        }
    }

    private fun load(): List<ChannelStatEvent> = runCatching {
        val array = JSONArray(appContext.getSharedPreferences(PREFS, 0).getString("events", "[]"))
        buildList { for (i in 0 until array.length()) array.optJSONObject(i)?.let { item ->
            add(ChannelStatEvent(item.optString("id"), item.optString("channel"), item.optString("key"),
                item.optString("timestamp"), item.optBoolean("sent"), item.optBoolean("mention")))
        } }
    }.getOrDefault(emptyList())

    private fun save(events: List<ChannelStatEvent>) {
        val array = JSONArray().apply { events.forEach { event -> put(JSONObject().apply {
            put("id", event.id); put("channel", event.channel); put("key", event.publicKey)
            put("timestamp", event.timestamp); put("sent", event.sent); put("mention", event.mention)
        }) } }
        appContext.getSharedPreferences(PREFS, 0).edit().putString("events", array.toString()).apply()
    }

    private fun ChannelMessage.epochMillis() = runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrDefault(0L)
    private fun ChannelStatEvent.epochMillis() = runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrDefault(0L)

    private const val PREFS = "channel_statistics"
    private const val REFRESH_MS = 10 * 60 * 1_000L
    private const val DAY_MS = 24 * 60 * 60 * 1_000L
}
