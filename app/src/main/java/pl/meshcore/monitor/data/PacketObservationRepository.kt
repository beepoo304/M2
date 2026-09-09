package pl.meshcore.monitor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PacketObservationDetails(
    val routes: List<List<String>> = emptyList(),
    val observationCount: Int = 0,
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
                val value = observations.optJSONObject(index)?.optString("path_json") ?: continue
                val array = runCatching { JSONArray(value) }.getOrNull() ?: continue
                val route = MeshPath.normalize(buildList {
                    for (hop in 0 until array.length()) add(array.optString(hop))
                }.filter(String::isNotBlank))
                add(route)
            }
        }.distinct().sortedWith(compareBy<List<String>> { it.size }.thenBy { it.joinToString() })
        return PacketObservationDetails(routes, root.optInt("observation_count", observations.length()))
    }
}
