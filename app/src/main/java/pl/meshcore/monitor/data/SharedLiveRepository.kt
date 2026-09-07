package pl.meshcore.monitor.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin

/** Single application-wide source of live packets for every screen. */
object SharedLiveRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LiveState())
    val state = _state.asStateFlow()
    private var source: LiveSource? = null
    private var listenerJob: Job? = null

    @Synchronized fun start() {
        if (listenerJob?.isActive == true) return
        listenerJob = scope.launch {
            while (isActive) {
                try {
                    ConnectionConfigBus.config.collectLatest { config ->
                        source?.close()
                        val next = CoreScopeRepository(config.coreScopeBaseUrl, config.ownPublicKeys, config.ownNodeNames, config.savedChannels)
                        source = next
                        try {
                            launch { next.state.collect { _state.value = it } }
                            next.run()
                        } finally {
                            next.close()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _state.value = _state.value.copy(connection = ConnectionState.ERROR, error = "Live listener stopped — restarting")
                    delay(2_000)
                }
            }
        }
    }

    suspend fun stop() {
        listenerJob?.cancelAndJoin()
        listenerJob = null
        source?.close()
        source = null
        _state.value = LiveState(connection = ConnectionState.DISCONNECTED)
    }

    suspend fun refresh() = source?.refresh() ?: Unit
}
