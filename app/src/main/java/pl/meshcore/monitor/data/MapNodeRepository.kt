package pl.meshcore.monitor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs

data class LocatedNode(val publicKey: String, val lat: Double, val lon: Double)

object MapNodeRepository {
    suspend fun load(): List<LocatedNode> = withContext(Dispatchers.IO) {
        runCatching {
            val base = ConnectionConfigBus.config.value.coreScopeBaseUrl.trimEnd('/')
            NetworkModule.client.newCall(Request.Builder().url("$base/api/nodes?limit=3000").build())
                .execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val nodes = JSONObject(response.body?.string().orEmpty()).optJSONArray("nodes") ?: return@use emptyList()
                    buildList { for (i in 0 until nodes.length()) nodes.optJSONObject(i)?.let { node ->
                        val key = node.optString("public_key").uppercase()
                        if (key.length >= 4 && node.has("lat") && node.has("lon") && !node.isNull("lat") && !node.isNull("lon")) {
                            val lat = node.optDouble("lat")
                            val lon = node.optDouble("lon")
                            val valid = lat.isFinite() && lon.isFinite() &&
                                lat in -90.0..90.0 && lon in -180.0..180.0 &&
                                !(abs(lat) < 0.01 && abs(lon) < 0.01)
                            if (valid) add(LocatedNode(key, lat, lon))
                        }
                    } }
                }
        }.getOrDefault(emptyList())
    }
}
