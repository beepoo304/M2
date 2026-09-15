package pl.meshcore.monitor.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import org.json.JSONObject

/** Application-wide outage history; never tied to a Settings screen lifecycle. */
object ApiHealthMonitor {
    data class BrokerHealth(val online: Boolean?, val entries: List<ApiHealthEntry>)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<Map<String, BrokerHealth>>(emptyMap())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    @Synchronized fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val store = ApiHealthLogStore(app)
        val prefs = app.getSharedPreferences("connection_settings", Context.MODE_PRIVATE)
        job = scope.launch {
            val due = mutableMapOf<String, Long>()
            val downSince = mutableMapOf<String, Long>()
            while (isActive) {
                val bases = (prefs.getStringSet("saved_api_urls", emptySet()).orEmpty() + ConnectionConfigBus.config.value.coreScopeBaseUrl)
                    .map { it.trim().trimEnd('/') }.distinct()
                coroutineScope { bases.map { base -> launch {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now < synchronized(due) { due[base] ?: 0L }) return@launch
                    val previous = mutableState.value[base]?.entries ?: store.load(base)
                    val started = System.currentTimeMillis()
                    val online = try {
                        val request = Request.Builder().url("$base/api/packets?limit=1").header("Cache-Control", "no-cache").build()
                        NetworkModule.client.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
                            .newCall(request).execute().use { response ->
                                response.isSuccessful && JSONObject(response.body?.string().orEmpty()).optJSONArray("packets") != null
                            }
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
                    val checked = System.currentTimeMillis()
                    val first = previous.firstOrNull()
                    val entries = when {
                        !online && first?.ongoing == true -> listOf(first.copy(lastCheckedAtMs = checked, checks = first.checks + 1)) + previous.drop(1)
                        !online -> (listOf(ApiHealthEntry(started, checked, "API down", checked - started)) + previous).take(ApiHealthLogStore.LIMIT)
                        first?.ongoing == true -> listOf(first.copy(lastCheckedAtMs = checked, ongoing = false)) + previous.drop(1)
                        else -> previous
                    }
                    try { store.save(base, entries) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { android.util.Log.e("M2ApiHealth", "Outage history could not be saved", error) }
                    synchronized(this@ApiHealthMonitor) { mutableState.value = mutableState.value + (base to BrokerHealth(online, entries)) }
                    val elapsed = android.os.SystemClock.elapsedRealtime()
                    synchronized(due) {
                        if (online) downSince.remove(base) else downSince.putIfAbsent(base, elapsed)
                        due[base] = elapsed + if (!online && elapsed - (downSince[base] ?: elapsed) >= 30_000L) 180_000L else 5_000L
                    }
                } }.joinAll() }
                delay(1_000L)
            }
        }
    }
    suspend fun stop() { job?.cancelAndJoin(); job = null }
}
