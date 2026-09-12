package pl.meshcore.monitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    val refreshingChannels: Boolean = false,
)

class ChannelsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ChannelRepository(application)
    private var saved: List<SavedChannel> = repository.loadSaved()
    private val _state = MutableStateFlow(ChannelsState(myChannels = saved.map(ChannelRepository::summary)))
    val state = _state.asStateFlow()
    private var channelLoadJob: Job? = null
    private var detailsJob: Job? = null
    private var previewLoadJob: Job? = null

    init {
        ConnectionConfigBus.update(ConnectionConfigBus.config.value.copy(savedChannels = saved))
        loadChannelPreviews()
        viewModelScope.launch {
            while (isActive) { delay(CHANNEL_REFRESH_INTERVAL_MS); refreshAll() }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(LOCAL_REFRESH_INTERVAL_MS)
                if (_state.value.selected == null && previewLoadJob?.isActive != true) {
                    _state.value = _state.value.copy(myChannels = summariesWithCachedPreviews())
                }
            }
        }
    }

    fun open(channel: ChannelSummary) {
        val savedChannel = saved.firstOrNull {
            it.hash.equals(channel.hash, true) && it.name == channel.name
        } ?: return
        val readAt = repository.markRead(savedChannel)
        _state.value = _state.value.copy(myChannels = _state.value.myChannels.map {
            if (it.hash.equals(channel.hash, true) && it.name == channel.name) it.copy(lastReadAtMs = readAt) else it
        })
        channelLoadJob?.cancel()
        channelLoadJob = viewModelScope.launch {
            val cached = repository.cachedMessages(savedChannel)
            _state.value = _state.value.copy(selected = channel, messages = cached, loading = true, error = null)
            runCatching { repository.messages(savedChannel) }
                .onSuccess {
                    updatePreview(savedChannel, it)
                    if (_state.value.selected?.hash.equals(channel.hash, true)) {
                        _state.value = _state.value.copy(messages = it, loading = false)
                    }
                }
                .onFailure { if (_state.value.selected == channel) _state.value = _state.value.copy(
                    messages = cached, loading = false,
                    error = if (cached.isEmpty()) null else "Refresh failed — showing saved messages",
                ) }
        }
    }

    fun refresh() { _state.value.selected?.let(::open) }
    fun refreshAll() = loadChannelPreviews(showRefreshing = true)
    fun clearSelectedMessages() {
        val selected = _state.value.selected ?: return
        val channel = saved.firstOrNull { it.hash.equals(selected.hash, true) && it.name == selected.name } ?: return
        repository.clearMessages(channel)
        val readAt = repository.markRead(channel)
        _state.value = _state.value.copy(
            messages = emptyList(),
            myChannels = _state.value.myChannels.map {
                if (it.hash.equals(selected.hash, true) && it.name == selected.name)
                    it.copy(latestMessage = null, recentMessages = emptyList(), lastReadAtMs = readAt) else it
            },
        )
    }
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
        _state.value = _state.value.copy(myChannels = summariesWithCachedPreviews())
        loadChannelPreviews()
    }

    private fun summariesWithCachedPreviews(): List<ChannelSummary> = saved.map { channel ->
        val messages = repository.cachedMessages(channel)
        ChannelRepository.summary(channel).copy(
            latestMessage = messages.firstOrNull(), recentMessages = messages,
            lastReadAtMs = repository.lastReadAt(channel),
        )
    }

    private fun loadChannelPreviews(showRefreshing: Boolean = false) {
        previewLoadJob?.cancel()
        _state.value = _state.value.copy(myChannels = summariesWithCachedPreviews())
        if (showRefreshing) _state.value = _state.value.copy(refreshingChannels = true)
        previewLoadJob = viewModelScope.launch {
            saved.forEach { channel ->
                runCatching { repository.messages(channel) }
                    .onSuccess { updatePreview(channel, it) }
            }
            _state.value = _state.value.copy(refreshingChannels = false)
        }
    }

    private fun updatePreview(channel: SavedChannel, messages: List<ChannelMessage>) {
        _state.value = _state.value.copy(myChannels = _state.value.myChannels.map { summary ->
            if (summary.hash.equals(channel.hash, true) && summary.name == channel.name) {
                summary.copy(latestMessage = messages.firstOrNull(), recentMessages = messages)
            } else summary
        })
    }

    private companion object {
        const val MAX_CHANNELS = 50
        const val CHANNEL_REFRESH_INTERVAL_MS = 10 * 60_000L
        const val LOCAL_REFRESH_INTERVAL_MS = 2_000L
    }
}
