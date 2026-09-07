package pl.meshcore.monitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.Request
import org.json.JSONObject
import pl.meshcore.monitor.data.ConnectionConfig
import pl.meshcore.monitor.data.ConnectionConfigBus
import pl.meshcore.monitor.data.DEFAULT_OWN_PUBLIC_KEYS
import pl.meshcore.monitor.data.NetworkModule
import pl.meshcore.monitor.data.SecureChannelStore

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("connection_settings", 0)
    private val client = NetworkModule.client

    val initialConfig: ConnectionConfig = ConnectionConfig(
        coreScopeBaseUrl = prefs.getString("core_url", "https://live.meshcorekk.xyz")!!,
        ownPublicKeys = prefs.getStringSet("own_public_keys", DEFAULT_OWN_PUBLIC_KEYS) ?: DEFAULT_OWN_PUBLIC_KEYS,
        ownNodeNames = (prefs.getStringSet("own_public_keys", DEFAULT_OWN_PUBLIC_KEYS) ?: DEFAULT_OWN_PUBLIC_KEYS)
            .mapNotNull { prefs.getString("device_name_$it", null) }.map { it.trim().lowercase() }.toSet(),
        savedChannels = SecureChannelStore(application).load(),
    ).also(ConnectionConfigBus::update)

    data class DeviceEntry(val publicKey: String, val name: String)
    private val _devices = MutableStateFlow(initialConfig.ownPublicKeys.map {
        DeviceEntry(it, prefs.getString("device_name_$it", null) ?: "Looking up name…")
    })
    val devices = _devices.asStateFlow()

    init { resolveNames(retries = 5) }

    fun addDevice(value: String): Boolean {
        val key = value.trim().lowercase()
        if (!key.matches(Regex("[0-9a-f]{64}"))) return false
        if (_devices.value.none { it.publicKey == key }) {
            _devices.value = _devices.value + DeviceEntry(key, prefs.getString("device_name_$key", null) ?: "Looking up name…")
            persistDeviceKeys()
            resolveNames(retries = 5)
        }
        return true
    }

    fun removeDevice(publicKey: String) {
        _devices.value = _devices.value.filterNot { it.publicKey == publicKey }
        persistDeviceKeys()
    }

    private fun persistDeviceKeys() {
        val keys = _devices.value.map { it.publicKey }.toSet()
        prefs.edit().putStringSet("own_public_keys", keys).apply()
        ConnectionConfigBus.update(ConnectionConfigBus.config.value.copy(
            ownPublicKeys = keys,
            ownNodeNames = _devices.value.map { it.name.trim().lowercase() }.filterNot { it.startsWith("looking up") }.toSet(),
        ))
    }

    private fun resolveNames(retries: Int = 1) {
        viewModelScope.launch {
            repeat(retries) { attempt ->
                val names = withContext(Dispatchers.IO) {
                    runCatching {
                    val base = ConnectionConfigBus.config.value.coreScopeBaseUrl.trimEnd('/')
                    val request = Request.Builder().url("$base/api/nodes?limit=3000").build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use emptyMap<String, String>()
                        val array = JSONObject(response.body?.string().orEmpty()).optJSONArray("nodes")
                            ?: return@use emptyMap<String, String>()
                        buildMap {
                            for (i in 0 until array.length()) array.optJSONObject(i)?.let {
                                val key = it.optString("public_key").lowercase()
                                val name = it.optString("name")
                                if (key.isNotBlank() && name.isNotBlank()) put(key, name)
                            }
                        }
                    }
                    }.getOrDefault(emptyMap())
                }
                if (names.isNotEmpty()) {
                    _devices.value = _devices.value.map { it.copy(name = names[it.publicKey] ?: it.name) }
                    prefs.edit().apply {
                        _devices.value.forEach { if (!it.name.startsWith("Looking up")) putString("device_name_${it.publicKey}", it.name) }
                    }.apply()
                    ConnectionConfigBus.update(ConnectionConfigBus.config.value.copy(
                        ownNodeNames = _devices.value.map { it.name.trim().lowercase() }
                            .filterNot { it.startsWith("looking up") }.toSet(),
                    ))
                    return@launch
                }
                if (attempt < retries - 1) delay(3_000)
            }
        }
    }

    fun save(config: ConnectionConfig) {
        prefs.edit()
            .putString("core_url", config.coreScopeBaseUrl)
            .putStringSet("own_public_keys", config.ownPublicKeys)
            .apply()
        ConnectionConfigBus.update(config.copy(
            ownNodeNames = _devices.value.map { it.name.trim().lowercase() }
                .filterNot { it.startsWith("looking up") }.toSet(),
            savedChannels = ConnectionConfigBus.config.value.savedChannels,
        ))
    }

}
