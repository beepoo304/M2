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
    init { OwnTrafficLogEngine.start(application) }
    val packets = OwnTrafficLogEngine.packets
    fun clear() = OwnTrafficLogEngine.clear()
}

object OwnTrafficLogEngine {
    private lateinit var store: OwnTrafficLogStore
    private var job: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val entries = LinkedHashMap<String, LivePacket>()
    private var clearedAt = 0L
    private val _packets = MutableStateFlow<List<LivePacket>>(emptyList())
    val packets = _packets.asStateFlow()

    @Synchronized fun start(context: android.content.Context) {
        if (job?.isActive == true) return
        store = OwnTrafficLogStore(context)
        clearedAt = store.clearedAt()
        entries.clear()
        store.load().forEach { entries[it.identity()] = it }
        _packets.value = entries.values.sortedByDescending { it.epochMillis() }
        job = scope.launch {
            SharedLiveRepository.state.collect { live -> update(live.packets) }
        }
    }

    @Synchronized private fun update(incoming: List<LivePacket>) {
        val keys = ConnectionConfigBus.config.value.ownPublicKeys
        incoming.filter { packet ->
            packet.epochMillis() > clearedAt && packet.trackedRelations.confirmedKeys.any { it in keys }
        }.forEach { packet -> entries[packet.identity()] = packet }
        val ordered = entries.values.sortedByDescending { it.epochMillis() }.take(250)
        entries.clear(); ordered.forEach { entries[it.identity()] = it }
        if (_packets.value != ordered) {
            runCatching { store.save(ordered) }.onSuccess { _packets.value = ordered }
        }
    }

    @Synchronized fun clear() {
        if (!::store.isInitialized) return
        clearedAt = System.currentTimeMillis()
        entries.clear(); _packets.value = emptyList(); store.clear(clearedAt)
    }
}

private fun LivePacket.identity(): String = hash.trim().lowercase().ifBlank { "$payloadType:$timestamp:$rawHex" }
private fun LivePacket.epochMillis(): Long = pl.meshcore.monitor.data.WarsawTimeFormatter.epochMillis(timestamp) ?: 0L
