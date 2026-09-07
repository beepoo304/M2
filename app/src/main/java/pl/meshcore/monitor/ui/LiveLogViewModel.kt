package pl.meshcore.monitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pl.meshcore.monitor.data.LiveState
import pl.meshcore.monitor.data.SharedLiveRepository

class LiveLogViewModel : ViewModel() {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    init {
        viewModelScope.launch {
            SharedLiveRepository.state.collect { _state.value = it }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try { SharedLiveRepository.refresh() } finally { _refreshing.value = false }
        }
    }
}
