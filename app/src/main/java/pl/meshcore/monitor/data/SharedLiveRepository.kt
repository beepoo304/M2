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

    private var appContext: android.content.Context? = null

    @Synchronized fun start(context: android.content.Context) {
        appContext = context.applicationContext
        if (listenerJob?.isActive == true) return
        listenerJob = scope.launch {
            while (isActive) {
                try {
                    ConnectionConfigBus.config.collectLatest { config ->
                        source?.close()
                        val next = CoreScopeRepository(config.coreScopeBaseUrl, config.ownPublicKeys, config.ownNodeNames,
                            config.ownKeyNames, config.savedChannels, onBrokerUnavailable = { switchToAvailableBroker(config) })
                        source = next
                        try {
                            kotlinx.coroutines.coroutineScope {
                                launch { next.state.collect { _state.value = it } }
                                next.run()
                            }
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

    private suspend fun switchToAvailableBroker(failedConfig: ConnectionConfig) {
        val prefs = appContext?.getSharedPreferences("connection_settings", android.content.Context.MODE_PRIVATE) ?: return
        val alternatives = prefs.getStringSet("saved_api_urls", emptySet()).orEmpty().sorted()
            .filter { it.trimEnd('/') != failedConfig.coreScopeBaseUrl.trimEnd('/') }
        for (base in alternatives) {
            if (ConnectionConfigBus.config.value != failedConfig) return
            val available = try {
                val request = okhttp3.Request.Builder().url("${base.trimEnd('/')}/api/packets?limit=1")
                    .header("Cache-Control", "no-cache").build()
                NetworkModule.client.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(request).execute().use { response ->
                        response.isSuccessful && org.json.JSONObject(response.body?.string().orEmpty()).optJSONArray("packets") != null
                    }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { false }
            if (available && ConnectionConfigBus.config.value == failedConfig) {
                check(prefs.edit().putString("core_url", base).commit()) { "Cannot persist active broker" }
                ConnectionConfigBus.update(failedConfig.copy(coreScopeBaseUrl = base))
                return
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
