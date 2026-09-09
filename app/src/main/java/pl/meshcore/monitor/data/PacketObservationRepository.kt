package pl.meshcore.monitor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PacketObservationDetails(
    val routes: List<ObservedRoute> = emptyList(),
    val observationCount: Int = 0,
)

data class ObservedRoute(
    val path: List<String>,
    val observerName: String,
    val observerPublicKey: String = "",
    val rssi: Int? = null,
    val snr: Double? = null,
)

object PacketObservationRepository {
    suspend fun load(packetId: String): PacketObservationDetails = withContext(Dispatchers.IO) {
        runCatching {
            val base = ConnectionConfigBus.config.value.coreScopeBaseUrl.trimEnd('/')
            val request = Request.Builder().url("$base/api/packets/$packetId").build()
            NetworkModule.client.newCall(request).apply { timeout().timeout(15, TimeUnit.SECONDS) }
                .execute().use { response ->
                    if (!response.isSuccessful) return@use PacketObservationDetails()
                    parse(JSONObject(response.body?.string().orEmpty()))
                }
        }.getOrDefault(PacketObservationDetails())
    }

    internal fun parse(root: JSONObject): PacketObservationDetails {
        val observations = root.optJSONArray("observations") ?: JSONArray()
        val routes = buildList {
            for (index in 0 until observations.length()) {
                val observation = observations.optJSONObject(index) ?: continue
                val value = observation.optString("path_json")
                val array = runCatching { JSONArray(value) }.getOrNull() ?: continue
                val route = MeshPath.normalize(buildList {
                    for (hop in 0 until array.length()) add(array.optString(hop))
                }.filter(String::isNotBlank))
                add(ObservedRoute(
                    path = route,
                    observerName = observation.optString("observer_name").ifBlank { "Unknown observer" },
                    observerPublicKey = observation.optString("observer_id"),
                    rssi = observation.optNullableInt("rssi"),
                    snr = observation.optNullableDouble("snr"),
                ))
            }
        }.distinctBy { it.path }.sortedWith(compareBy<ObservedRoute> { it.path.size }.thenBy { it.path.joinToString() })
        return PacketObservationDetails(routes, root.optInt("observation_count", observations.length()))
    }
}

private fun JSONObject.optNullableInt(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
private fun JSONObject.optNullableDouble(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name) else null
