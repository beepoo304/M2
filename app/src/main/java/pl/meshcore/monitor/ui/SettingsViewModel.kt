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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import pl.meshcore.monitor.data.ConnectionConfig
import pl.meshcore.monitor.data.ConnectionConfigBus
import pl.meshcore.monitor.data.DEFAULT_OWN_PUBLIC_KEYS
import pl.meshcore.monitor.data.NetworkModule
import pl.meshcore.monitor.data.SecureChannelStore
import pl.meshcore.monitor.data.SharedLiveRepository
import pl.meshcore.monitor.data.TrackedMention
import pl.meshcore.monitor.data.ExportLocationStore
import java.time.Instant

data class ApiHealthEntry(
    val startedAtMs: Long,
    val lastCheckedAtMs: Long,
    val status: String,
    val responseMs: Long,
    val checks: Int = 1,
    val ongoing: Boolean = true,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = application.getSharedPreferences("connection_settings", 0)
    private val client = NetworkModule.client

    val initialConfig: ConnectionConfig = ConnectionConfig(
        coreScopeBaseUrl = prefs.getString("core_url", "https://live.meshcorekk.xyz")!!,
        ownPublicKeys = prefs.getStringSet("own_public_keys", DEFAULT_OWN_PUBLIC_KEYS) ?: DEFAULT_OWN_PUBLIC_KEYS,
        ownNodeNames = (prefs.getStringSet("own_public_keys", DEFAULT_OWN_PUBLIC_KEYS) ?: DEFAULT_OWN_PUBLIC_KEYS)
            .mapNotNull { prefs.getString("device_name_$it", null) }.map { it.trim().lowercase() }.toSet(),
        savedChannels = SecureChannelStore(application).load(),
    ).also(ConnectionConfigBus::update)

    data class DeviceEntry(
        val publicKey: String,
        val name: String,
        val counterStartedAt: Long,
        val packetCount: Long,
    )
    private val createdAt = System.currentTimeMillis()
    private val _devices = MutableStateFlow(initialConfig.ownPublicKeys.map {
        DeviceEntry(
            it,
            prefs.getString("device_name_$it", null) ?: "Looking up name…",
            prefs.getLong("packet_counter_start_$it", createdAt),
            prefs.getLong("packet_counter_count_$it", 0L),
        )
    })
    val devices = _devices.asStateFlow()
    private val _savedApis = MutableStateFlow(loadSavedApis(initialConfig.coreScopeBaseUrl))
    val savedApis = _savedApis.asStateFlow()
    private val _apiHealthLog = MutableStateFlow<List<ApiHealthEntry>>(emptyList())
    val apiHealthLog = _apiHealthLog.asStateFlow()
    private val _apiOnline = MutableStateFlow<Boolean?>(null)
    val apiOnline = _apiOnline.asStateFlow()
    private val _exportLocation = MutableStateFlow(ExportLocationStore.selectedUri(application))
    val exportLocation = _exportLocation.asStateFlow()

    init {
        prefs.edit().apply {
            _devices.value.forEach { if (!prefs.contains("packet_counter_start_${it.publicKey}"))
                putLong("packet_counter_start_${it.publicKey}", it.counterStartedAt) }
        }.apply()
        resolveNames(retries = 5)
        monitorApi()
        countPackets()
    }

    private fun countPackets() {
        viewModelScope.launch {
            SharedLiveRepository.state.collect { live ->
                var updated = _devices.value
                var changed = false
                updated = updated.map { device ->
                    val seenKey = "packet_counter_seen_${device.publicKey}"
                    val seen = prefs.getStringSet(seenKey, emptySet()).orEmpty().toMutableSet()
                    val matches = live.packets.filter { packet ->
                        packet.id !in seen && packet.packetEpochMillis() >= device.counterStartedAt &&
                            packet.matchesDevice(device)
                    }
                    if (matches.isEmpty()) device else {
                        changed = true
                        seen += matches.map { it.id }
                        val boundedSeen = seen.toList().takeLast(SEEN_PACKET_IDS).toSet()
                        val count = device.packetCount + matches.size
                        prefs.edit().putLong("packet_counter_count_${device.publicKey}", count)
                            .putStringSet(seenKey, boundedSeen).apply()
                        device.copy(packetCount = count)
                    }
                }
                if (changed) _devices.value = updated
            }
        }
    }

    private fun pl.meshcore.monitor.data.LivePacket.matchesDevice(device: DeviceEntry): Boolean {
        if (matchedOwnKeys.any { it.equals(device.publicKey, true) }) return true
        if (publicKey?.equals(device.publicKey, true) == true ||
            observerPublicKey.equals(device.publicKey, true)) return true
        val name = device.name.trim().lowercase()
        if (name.isBlank() || name.startsWith("looking up")) return false
        val decoded = decodedJson.takeIf { it.startsWith("{") }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        return decoded?.optString("sender").orEmpty().trim().equals(name, true) ||
            decoded?.optString("name").orEmpty().trim().equals(name, true) ||
            TrackedMention.contains(decoded?.optString("text").orEmpty(), setOf(name))
    }

    private fun pl.meshcore.monitor.data.LivePacket.packetEpochMillis(): Long =
        runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrDefault(0L)

    private fun monitorApi() {
        viewModelScope.launch {
            while (true) {
                val started = System.currentTimeMillis()
                val result = withContext(Dispatchers.IO) {
                    val base = ConnectionConfigBus.config.value.coreScopeBaseUrl.trim().let {
                        if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
                    }.trimEnd('/')
                    runCatching {
                        val request = Request.Builder().url("$base/api/packets?limit=1&_=$started")
                            .header("Cache-Control", "no-cache").build()
                        client.newCall(request).execute().use { response ->
                            response.isSuccessful to Pair(
                                "HTTP ${response.code} ${response.message}".trim(),
                                System.currentTimeMillis() - started,
                            )
                        }
                    }.getOrElse { error ->
                        false to Pair(
                            error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName,
                            System.currentTimeMillis() - started,
                        )
                    }
                }
                val online = result.first
                val status = result.second.first
                val responseMs = result.second.second
                _apiOnline.value = online
                val current = _apiHealthLog.value.firstOrNull()
                if (!online) {
                    _apiHealthLog.value = if (current?.ongoing == true) {
                        listOf(current.copy(lastCheckedAtMs = System.currentTimeMillis(), status = status,
                            responseMs = responseMs, checks = current.checks + 1)) + _apiHealthLog.value.drop(1)
                    } else {
                        (listOf(ApiHealthEntry(started, System.currentTimeMillis(), status, responseMs)) +
                            _apiHealthLog.value).take(API_LOG_LIMIT)
                    }
                } else if (current?.ongoing == true) {
                    _apiHealthLog.value = listOf(current.copy(
                        lastCheckedAtMs = System.currentTimeMillis(), ongoing = false,
                    )) + _apiHealthLog.value.drop(1)
                }
                delay((API_CHECK_INTERVAL_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0L))
            }
        }
    }

    fun addDevice(value: String): Boolean {
        val key = value.trim().lowercase()
        if (!key.matches(Regex("[0-9a-f]{64}"))) return false
        if (_devices.value.none { it.publicKey == key }) {
            val now = System.currentTimeMillis()
            _devices.value = _devices.value + DeviceEntry(
                key, prefs.getString("device_name_$key", null) ?: "Looking up name…", now, 0L)
            prefs.edit().putLong("packet_counter_start_$key", now)
                .putLong("packet_counter_count_$key", 0L).remove("packet_counter_seen_$key").apply()
            persistDeviceKeys()
            resolveNames(retries = 5)
        }
        return true
    }

    fun addApi(value: String): String? {
        val input = value.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val parsed = input.toHttpUrlOrNull() ?: return null
        if (parsed.host.isBlank()) return null
        val normalized = parsed.newBuilder().fragment(null).query(null).build().toString().trimEnd('/')
        _savedApis.value = (_savedApis.value + normalized).distinct()
        prefs.edit().putStringSet("saved_api_urls", _savedApis.value.toSet()).apply()
        return normalized
    }

    fun removeApi(value: String): Boolean {
        val normalized = value.trim().trimEnd('/')
        val active = ConnectionConfigBus.config.value.coreScopeBaseUrl.trim().trimEnd('/')
        if (normalized == active || normalized == DEFAULT_LIVE_API) return false
        _savedApis.value = _savedApis.value.filterNot { it.trimEnd('/') == normalized }
        prefs.edit().putStringSet("saved_api_urls", _savedApis.value.toSet()).apply()
        return true
    }

    fun saveExportLocation(uri: String?) {
        ExportLocationStore.saveSelection(app, uri)
        _exportLocation.value = uri
    }

    fun exportLocationLabel(uri: String?): String = ExportLocationStore.label(app, uri)

    private fun loadSavedApis(active: String): List<String> {
        val stored = prefs.getStringSet("saved_api_urls", emptySet()).orEmpty()
        return (listOf(active.trim().trimEnd('/')) + stored).filter(String::isNotBlank).distinct()
    }

    fun removeDevice(publicKey: String) {
        _devices.value = _devices.value.filterNot { it.publicKey == publicKey }
        prefs.edit().remove("packet_counter_start_$publicKey")
            .remove("packet_counter_count_$publicKey").remove("packet_counter_seen_$publicKey").apply()
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

    private companion object {
        const val DEFAULT_LIVE_API = "https://live.meshcorekk.xyz"
        const val API_LOG_LIMIT = 250
        const val API_CHECK_INTERVAL_MS = 5_000L
        const val SEEN_PACKET_IDS = 500
    }
}
