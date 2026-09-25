package pl.meshcore.monitor.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** The same endpoint, timeout and validity check for Live, monitoring and failover. */
internal object BrokerApi {
    private val client = NetworkModule.client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun packets(base: String, limit: Int): JSONArray {
        val started = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("${base.trim().trimEnd('/')}/api/packets?limit=$limit&_=$started")
                .header("Cache-Control", "no-cache").build()
            val result = suspendCancellableCoroutine<JSONArray> { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val parsed = runCatching {
                            response.use {
                                check(it.isSuccessful) { "HTTP ${it.code}" }
                                JSONObject(it.body?.string().orEmpty()).optJSONArray("packets")
                                    ?: error("Missing packets array")
                            }
                        }
                        if (continuation.isActive) parsed.fold(continuation::resume, continuation::resumeWithException)
                    }
                })
            }
            ApiHealthMonitor.record(base, true, started)
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ApiHealthMonitor.record(base, false, started)
            throw error
        }
    }
}
