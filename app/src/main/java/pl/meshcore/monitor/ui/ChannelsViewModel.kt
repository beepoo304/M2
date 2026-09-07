package pl.meshcore.monitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import pl.meshcore.monitor.data.ChannelMessage
import pl.meshcore.monitor.data.ChannelRepository
import pl.meshcore.monitor.data.ChannelSummary
import pl.meshcore.monitor.data.ChannelMessageDetails
import pl.meshcore.monitor.data.SavedChannel
import pl.meshcore.monitor.data.ConnectionConfigBus

data class ChannelsState(
    val myChannels: List<ChannelSummary> = emptyList(),
    val selected: ChannelSummary? = null,
    val messages: List<ChannelMessage> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val selectedMessage: ChannelMessageDetails? = null,
    val loadingDetails: Boolean = false,
)

class ChannelsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ChannelRepository(application)
    private var saved: List<SavedChannel> = repository.loadSaved()
    private val _state = MutableStateFlow(ChannelsState(myChannels = saved.map(ChannelRepository::summary)))
    val state = _state.asStateFlow()
    private var channelLoadJob: Job? = null
    private var detailsJob: Job? = null

    init {
        ConnectionConfigBus.update(ConnectionConfigBus.config.value.copy(savedChannels = saved))
    }

    fun open(channel: ChannelSummary) {
        val savedChannel = saved.firstOrNull {
            it.hash.equals(channel.hash, true) && it.name == channel.name
        } ?: return
        channelLoadJob?.cancel()
        channelLoadJob = viewModelScope.launch {
            val cached = repository.cachedMessages(savedChannel)
            _state.value = _state.value.copy(selected = channel, messages = cached, loading = true, error = null)
            runCatching { repository.messages(savedChannel) }
                .onSuccess { if (_state.value.selected == channel) _state.value = _state.value.copy(messages = it, loading = false) }
                .onFailure { if (_state.value.selected == channel) _state.value = _state.value.copy(
                    messages = cached, loading = false,
                    error = if (cached.isEmpty()) null else "Refresh failed — showing saved messages",
                ) }
        }
    }

    fun refresh() { _state.value.selected?.let(::open) }
    fun closeChannel() { channelLoadJob?.cancel(); _state.value = _state.value.copy(selected = null, messages = emptyList(), error = null) }

    fun showMessageDetails(message: ChannelMessage) {
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            _state.value = _state.value.copy(loadingDetails = true)
            val details = repository.messageDetails(message)
            _state.value = _state.value.copy(selectedMessage = details, loadingDetails = false)
        }
    }

    fun closeMessageDetails() { _state.value = _state.value.copy(selectedMessage = null, loadingDetails = false) }

    fun addCustom(value: String): Boolean {
        val channel = repository.parseChannel(value) ?: return false
        saved = (listOf(channel) + saved.filterNot { it.hash.equals(channel.hash, true) }).take(MAX_CHANNELS)
        persist()
        return true
    }

    fun removeSaved(channel: ChannelSummary) {
        saved = saved.filterNot { it.hash.equals(channel.hash, true) }
        persist()
    }

    fun renameSaved(channel: ChannelSummary, newName: String): Boolean {
        val name = newName.trim()
        if (name.isBlank()) return false
        val existing = saved.firstOrNull { it.hash.equals(channel.hash, true) && it.name == channel.name } ?: return false
        saved = saved.map { if (it === existing) it.copy(name = name) else it }
        persist()
        return true
    }

    private fun persist() {
        repository.save(saved)
        ConnectionConfigBus.update(ConnectionConfigBus.config.value.copy(savedChannels = saved))
        _state.value = _state.value.copy(myChannels = saved.map(ChannelRepository::summary))
    }

    private companion object { const val MAX_CHANNELS = 50 }
}
