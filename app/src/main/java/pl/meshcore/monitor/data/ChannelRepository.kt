package pl.meshcore.monitor.data

import android.content.Context
import android.net.Uri
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class ChannelRepository(context: Context) {
    private val store = SecureChannelStore(context)
    private val client = NetworkModule.client

    fun loadSaved(): List<SavedChannel> = store.load()
    fun save(channels: List<SavedChannel>) = store.save(channels)
    fun cachedMessages(channel: SavedChannel): List<ChannelMessage> = sortMessages(store.loadMessages(channel))
    fun clearMessages(channel: SavedChannel) = store.clearMessages(channel)
    fun lastReadAt(channel: SavedChannel): Long = store.lastReadAt(channel)
    fun markRead(channel: SavedChannel): Long = store.markRead(channel)

    fun parseChannel(value: String): SavedChannel? {
        val input = value.trim()
        return when {
            input.equals("public", ignoreCase = true) -> SavedChannel("public", "public")
            input.startsWith("meshcore://channel/add", true) -> parseQr(input)
            input.startsWith("#") && input.length > 1 -> ChannelCrypto.deriveSecret(input).let {
                SavedChannel(input, ChannelCrypto.channelHash(it), ChannelCrypto.encodeHex(it))
            }
            input.matches(HEX_SECRET) -> ChannelCrypto.decodeHex(input)?.let {
                SavedChannel("Private channel", ChannelCrypto.channelHash(it), input.lowercase(), isPrivate = true)
            }
            else -> null
        }
    }

    suspend fun messages(channel: SavedChannel): List<ChannelMessage> = withContext(Dispatchers.IO) {
        val cached = store.loadMessages(channel)
        runCatching {
            // Named/key channels must be verified with their secret. The server's
            // name feed can contain packets sharing the same one-byte channel hash.
            val remote = if (channel.secret.isBlank()) loadRemoteMessages(channel.name) else emptyList()
            val local = if (channel.secret.isNotBlank()) {
                ChannelCrypto.decodeHex(channel.secret)?.let { decryptRecentPackets(channel, it) }.orEmpty()
            } else emptyList()
            val fresh = (remote + local).distinctBy { it.id }
            sortMessages(store.mergeMessages(channel, fresh))
        }.getOrDefault(sortMessages(cached))
    }

    private fun parseQr(input: String): SavedChannel? {
        val uri = runCatching { Uri.parse(input) }.getOrNull() ?: return null
        val text = uri.getQueryParameter("secret")?.trim().orEmpty()
        if (!text.matches(HEX_SECRET)) return null
        val secret = ChannelCrypto.decodeHex(text) ?: return null
        return SavedChannel(uri.getQueryParameter("name")?.takeIf(String::isNotBlank) ?: "Private channel",
            ChannelCrypto.channelHash(secret), text.lowercase(), isPrivate = true)
    }

    private fun loadRemoteMessages(name: String): List<ChannelMessage> {
        val base = ConnectionConfigBus.config.value.coreScopeBaseUrl.trimEnd('/')
        val request = Request.Builder().url("$base/api/channels/${URLEncoder.encode(name, "UTF-8")}/messages?limit=250").build()
        return runCatching { client.newCall(request).apply { timeout().timeout(15, TimeUnit.SECONDS) }.execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val array = JSONObject(response.body?.string().orEmpty()).optJSONArray("messages") ?: return@use emptyList()
            buildList { for (index in 0 until array.length()) array.optJSONObject(index)?.let { item ->
                add(ChannelMessage(
                    id = item.optString("packetId", "$name-$index"),
                    sender = item.optString("sender").ifBlank { "Anonymous" },
                    text = item.optString("text"), timestamp = item.optString("timestamp"),
                    hops = item.optInt("hops"),
                    observers = item.optJSONArray("observers")?.let { array ->
                        buildList { for (i in 0 until array.length()) add(array.optString(i)) }.filter(String::isNotBlank)
                    }.orEmpty(),
                    repeats = item.optInt("repeats", 1),
                    snr = if (item.has("snr") && !item.isNull("snr")) item.optDouble("snr") else null,
                    packetHash = item.optString("packetHash"),
                ))
            } }
        } }.getOrDefault(emptyList())
    }

    private fun decryptRecentPackets(channel: SavedChannel, secret: ByteArray): List<ChannelMessage> {
        val base = ConnectionConfigBus.config.value.coreScopeBaseUrl.trimEnd('/')
        val request = Request.Builder().url("$base/api/packets?limit=3000&_=${System.currentTimeMillis()}")
            .header("Cache-Control", "no-cache").build()
        return client.newCall(request).apply { timeout().timeout(15, TimeUnit.SECONDS) }.execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val packets = JSONObject(response.body?.string().orEmpty()).optJSONArray("packets") ?: return@use emptyList()
            buildList { for (index in 0 until packets.length()) {
                val packet = packets.optJSONObject(index) ?: continue
                if (packet.optInt("payload_type", -1) != 5) continue
                val decoded = packet.optString("decoded_json").takeIf { it.startsWith("{") }
                    ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
                if (!decoded.optString("channelHashHex").equals(channel.hash, true)) continue
                val mac = ChannelCrypto.decodeHex(decoded.optString("mac")) ?: continue
                val encrypted = ChannelCrypto.decodeHex(decoded.optString("encryptedData")) ?: continue
                if (!ChannelCrypto.validMac(secret, mac, encrypted)) continue
                val message = ChannelCrypto.decryptMessage(secret, encrypted) ?: continue
                add(ChannelMessage(
                    id = packet.optString("id", "${channel.hash}-$index"), sender = message.sender,
                    text = message.text, timestamp = message.timestamp,
                    hops = packet.optJSONArray("_parsedPath")?.length() ?: 0,
                    observers = listOf(packet.optString("observer_name")).filter(String::isNotBlank),
                    repeats = packet.optInt("observation_count", 1),
                    snr = if (packet.has("snr") && !packet.isNull("snr")) packet.optDouble("snr") else null,
                    packetHash = packet.optString("hash"),
                ))
            } }
        }
    }

    suspend fun messageDetails(message: ChannelMessage): ChannelMessageDetails = withContext(Dispatchers.IO) {
        val base = ConnectionConfigBus.config.value.coreScopeBaseUrl.trimEnd('/')
        val request = Request.Builder().url("$base/api/packets/${message.id}").build()
        runCatching {
            client.newCall(request).apply { timeout().timeout(15, TimeUnit.SECONDS) }.execute().use { response ->
                if (!response.isSuccessful) return@use ChannelMessageDetails(message)
                val packet = JSONObject(response.body?.string().orEmpty()).optJSONObject("packet")
                    ?: return@use ChannelMessageDetails(message)
                val pathArray = packet.optJSONArray("_parsedPath")
                    ?: runCatching { JSONArray(packet.optString("path_json", "[]")) }.getOrNull()
                val path = MeshPath.normalize(pathArray?.let { array ->
                    buildList { for (i in 0 until array.length()) add(array.optString(i)) }.filter(String::isNotBlank)
                }.orEmpty())
                ChannelMessageDetails(
                    message = message, path = path,
                    rssi = if (packet.has("rssi") && !packet.isNull("rssi")) packet.optInt("rssi") else null,
                    routeType = if (packet.has("route_type") && !packet.isNull("route_type")) packet.optInt("route_type") else null,
                    observationCount = packet.optInt("observation_count", message.repeats),
                )
            }
        }.getOrDefault(ChannelMessageDetails(message))
    }

    companion object {
        private val HEX_SECRET = Regex("(?i)^[0-9a-f]{32}([0-9a-f]{32})?$")
        fun summary(value: SavedChannel) = ChannelSummary(value.name, value.hash, isPrivate = value.isPrivate)
        private fun sortMessages(values: List<ChannelMessage>) = values.distinctBy { it.id }
            .sortedByDescending { runCatching { Instant.parse(it.timestamp) }.getOrElse { Instant.EPOCH } }
    }
}
