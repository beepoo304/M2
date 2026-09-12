package pl.meshcore.monitor.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectionConfig(
    val coreScopeBaseUrl: String = "https://live.meshcorekk.xyz",
    val ownPublicKeys: Set<String> = DEFAULT_OWN_PUBLIC_KEYS,
    val ownNodeNames: Set<String> = emptySet(),
    val ownKeyNames: Map<String, String> = emptyMap(),
    val savedChannels: List<SavedChannel> = emptyList(),
)

val DEFAULT_OWN_PUBLIC_KEYS: Set<String> = emptySet()

object ConnectionConfigBus {
    private val _config = MutableStateFlow(ConnectionConfig())
    val config = _config.asStateFlow()
    fun update(config: ConnectionConfig) { _config.value = config }
}
