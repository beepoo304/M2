package pl.meshcore.monitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.meshcore.monitor.data.LivePacket
import pl.meshcore.monitor.data.SharedLiveRepository
import pl.meshcore.monitor.data.OwnTrafficLogStore
import pl.meshcore.monitor.data.ConnectionConfigBus
import pl.meshcore.monitor.data.TrackedMention
import org.json.JSONObject
import java.time.Instant

class OwnTrafficLogViewModel(application: Application) : AndroidViewModel(application) {
    private val store = OwnTrafficLogStore(application)
    private val entries = LinkedHashMap<String, LivePacket>()
    private val ignoredIds = mutableSetOf<String>()
    private var clearedAt = store.clearedAt()
    private var lastPersistedAt = 0L
    private val _packets = MutableStateFlow(store.load().also { saved ->
        saved.asReversed().forEach { entries[it.id] = it }
    })
    val packets = _packets.asStateFlow()

    init {
        viewModelScope.launch {
            SharedLiveRepository.state.collect { state ->
                var changed = false
                var containsNewEntry = false
                val trackedNames = ConnectionConfigBus.config.value.ownNodeNames
                state.packets.asReversed().filter { packet ->
                    packet.belongsInMyLog(trackedNames) && packet.id !in ignoredIds && packet.epochMillis() > clearedAt
                }.forEach { packet ->
                    if (entries[packet.id] != packet) {
                        containsNewEntry = containsNewEntry || packet.id !in entries
                        entries[packet.id] = packet
                        changed = true
                    }
                }
                while (entries.size > MAX_ENTRIES) { entries.remove(entries.keys.first()); changed = true }
                _packets.value = entries.values.toList().asReversed()
                val now = System.currentTimeMillis()
                if (changed && (containsNewEntry || lastPersistedAt == 0L || now - lastPersistedAt >= PERSIST_INTERVAL_MS)) {
                    store.save(_packets.value)
                    lastPersistedAt = now
                }
            }
        }
    }

    fun clear() {
        ignoredIds.clear()
        ignoredIds += SharedLiveRepository.state.value.packets.map { it.id }
        entries.clear()
        _packets.value = emptyList()
        clearedAt = System.currentTimeMillis()
        store.clear(clearedAt)
    }

    private companion object {
        const val MAX_ENTRIES = 250
        const val PERSIST_INTERVAL_MS = 10_000L
    }
}

private fun LivePacket.epochMillis(): Long = runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrDefault(Long.MAX_VALUE)

private fun LivePacket.belongsInMyLog(trackedNames: Set<String>): Boolean {
    val relations = trackedRelations
    if (relations.sourceKeys.isNotEmpty() || relations.destinationKeys.isNotEmpty() || relations.routeKeys.isNotEmpty()) return true
    if (possibleOwnTraffic) return false
    val decoded = decodedJson.takeIf { it.startsWith("{") }
        ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return false
    val sender = decoded.optString("sender").trim()
    val name = decoded.optString("name").trim()
    val text = decoded.optString("text")
    return trackedNames.any { it.equals(sender, true) || it.equals(name, true) } ||
        TrackedMention.contains(text, trackedNames)
}
