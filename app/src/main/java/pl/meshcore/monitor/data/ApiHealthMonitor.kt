package pl.meshcore.monitor.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application-wide outage history; never tied to a Settings screen lifecycle. */
object ApiHealthMonitor {
    data class BrokerHealth(val online: Boolean?, val entries: List<ApiHealthEntry>)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<Map<String, BrokerHealth>>(emptyMap())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var store: ApiHealthLogStore? = null
    private val lastRequest = mutableMapOf<String, Long>()
    @Volatile internal var recoveryVersion = 0L
        private set

    /** Ignore late results of older requests; Live and probes publish into the same history. */
    @Synchronized internal fun record(baseUrl: String, online: Boolean, started: Long) {
        val base = baseUrl.trim().trimEnd('/')
        if (started < (lastRequest[base] ?: Long.MIN_VALUE)) return
        lastRequest[base] = started
        val current = mutableState.value[base]
        val previous = current?.entries ?: store?.load(base).orEmpty()
        val checked = System.currentTimeMillis()
        val first = previous.firstOrNull()
        val entries = when {
            !online && first?.ongoing == true -> listOf(first.copy(lastCheckedAtMs = checked, checks = first.checks + 1)) + previous.drop(1)
            !online -> (listOf(ApiHealthEntry(started, checked, "API down", checked - started)) + previous).take(ApiHealthLogStore.LIMIT)
            first?.ongoing == true -> listOf(first.copy(lastCheckedAtMs = checked, ongoing = false)) + previous.drop(1)
            else -> previous
        }
        if (entries != previous) runCatching { store?.save(base, entries) }
            .onFailure { android.util.Log.e("M2ApiHealth", "Outage history could not be saved", it) }
        mutableState.value = mutableState.value + (base to BrokerHealth(online, entries))
        if (online && current?.online != true) recoveryVersion++
    }
    @Synchronized fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        store = ApiHealthLogStore(app)
        val prefs = app.getSharedPreferences("connection_settings", Context.MODE_PRIVATE)
        job = scope.launch {
            val due = mutableMapOf<String, Long>()
            while (isActive) {
                val bases = (prefs.getStringSet("saved_api_urls", emptySet()).orEmpty() + ConnectionConfigBus.config.value.coreScopeBaseUrl)
                    .map { it.trim().trimEnd('/') }.distinct()
                coroutineScope { bases.map { base -> launch {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now < synchronized(due) { due[base] ?: 0L }) return@launch
                    try {
                        BrokerApi.packets(base, 1)
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* recorded by BrokerApi */ }
                    val elapsed = android.os.SystemClock.elapsedRealtime()
                    synchronized(due) {
                        val health = mutableState.value[base]
                        val outage = health?.entries?.firstOrNull()?.takeIf { it.ongoing }
                        val prolonged = health?.online == false && outage != null &&
                            System.currentTimeMillis() - outage.startedAtMs >= 30_000L
                        due[base] = elapsed + if (prolonged) 180_000L else 5_000L
                    }
                } }.joinAll() }
                delay(1_000L)
            }
        }
    }

    @Synchronized fun clear(context: Context, baseUrl: String) {
        val base = baseUrl.trim().trimEnd('/')
        ApiHealthLogStore(context.applicationContext).save(base, emptyList())
        val current = mutableState.value[base]
        mutableState.value = mutableState.value + (base to BrokerHealth(current?.online, emptyList()))
    }

    suspend fun stop() { job?.cancelAndJoin(); job = null }
}
