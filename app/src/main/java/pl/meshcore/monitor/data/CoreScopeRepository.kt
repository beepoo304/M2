package pl.meshcore.monitor.data

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

data class LivePacket(
    val id: String,
    val hash: String,
    val time: String,
    val payloadType: Int,
    val typeLabel: String,
    val observerName: String,
    val observerPublicKey: String = "",
    val nodeName: String?,
    val nodeRole: String? = null,
    val detail: String,
    val rawHex: String,
    val publicKey: String?,
    val ownTraffic: Boolean = false,
    val possibleOwnTraffic: Boolean = false,
    val timestamp: String = "",
    val decodedJson: String = "",
    val path: List<String> = emptyList(),
    val routeType: Int? = null,
    val rssi: Int? = null,
    val snr: Double? = null,
    val observationCount: Int = 1,
    val firstSeen: String = "",
    val matchedOwnKeys: Set<String> = emptySet(),
    val trackedRelations: TrackedKeyRelations = TrackedKeyRelations(),
)

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

data class LiveState(
    val packets: List<LivePacket> = emptyList(),
    val connection: ConnectionState = ConnectionState.CONNECTING,
    val error: String? = null,
    val revision: Long = 0,
)

interface LiveSource {
    val state: StateFlow<LiveState>
    suspend fun run()
    suspend fun refresh()
    fun close()
}

class CoreScopeRepository(
    baseUrl: String = "https://live.meshcorekk.xyz",
    private val ownPublicKeys: Set<String> = emptySet(),
    private val ownNodeNames: Set<String> = emptySet(),
    private val savedChannels: List<SavedChannel> = emptyList(),
) : LiveSource {
    private val httpBase = baseUrl.trim().let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
    }.trimEnd('/')
    private val socketUrl = httpBase.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
    private val client = NetworkModule.client
    private val _state = MutableStateFlow(LiveState())
    override val state: StateFlow<LiveState> = _state.asStateFlow()
    private val refreshRequested = AtomicBoolean(false)
    private val reconnectRequested = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var socket: WebSocket? = null
    @Volatile private var lastSuccessfulFetch = 0L
    private val observationMatchCache = ConcurrentHashMap<String, ObservationMatch>()

    override suspend fun run() {
        stopped.set(false)
        refreshPackets()
        connectWebSocket()
        var lastPoll = System.currentTimeMillis()
        var lastReconnect = System.currentTimeMillis()
        var failureBackoff = 0L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            if (refreshRequested.getAndSet(false)) {
                failureBackoff = if (refreshPackets()) 0L else nextBackoff(failureBackoff)
                lastPoll = System.currentTimeMillis()
            }
            if (reconnectRequested.get() && System.currentTimeMillis() - lastReconnect >= 5_000) {
                reconnectRequested.set(false)
                connectWebSocket()
                lastReconnect = System.currentTimeMillis()
            }
            val pollInterval = if (failureBackoff > 0L) failureBackoff else TrafficRefreshPolicy.liveIntervalMs()
            if (System.currentTimeMillis() - lastPoll >= pollInterval) {
                failureBackoff = if (refreshPackets()) 0L else nextBackoff(failureBackoff)
                lastPoll = System.currentTimeMillis()
            }
            delay(750)
        }
    }

    override suspend fun refresh() { refreshPackets() }

    private fun nextBackoff(previous: Long): Long = when {
        previous <= 0L -> TrafficRefreshPolicy.FOREGROUND_INTERVAL_MS * 2
        else -> minOf(previous * 2, 30_000L)
    }
    override fun close() {
        stopped.set(true)
        socket?.close(1000, "App closed")
        socket = null
    }

    private fun connectWebSocket() {
        if (stopped.get()) return
        if (System.currentTimeMillis() - lastSuccessfulFetch >= 6_000) _state.value = _state.value.copy(connection = ConnectionState.CONNECTING, error = null)
        val request = Request.Builder().url(socketUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (System.currentTimeMillis() - lastSuccessfulFetch < 6_000) _state.value = _state.value.copy(connection = ConnectionState.CONNECTED, error = null)
            }
            override fun onMessage(webSocket: WebSocket, text: String) { refreshRequested.set(true) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                socket = null
                if (System.currentTimeMillis() - lastSuccessfulFetch >= 6_000) _state.value = _state.value.copy(connection = ConnectionState.DISCONNECTED)
                if (!stopped.get()) reconnectRequested.set(true)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                socket = null
                if (System.currentTimeMillis() - lastSuccessfulFetch >= 6_000) _state.value = _state.value.copy(connection = ConnectionState.DISCONNECTED, error = null)
                if (!stopped.get()) reconnectRequested.set(true)
            }
        })
    }

    private suspend fun refreshPackets(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$httpBase/api/packets?limit=250&_=${System.currentTimeMillis()}")
                .header("Cache-Control", "no-cache").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val source = JSONObject(response.body?.string().orEmpty()).optJSONArray("packets") ?: return@use
                val parsedPackets = buildList {
                    for (index in 0 until minOf(source.length(), LIVE_LOG_LIMIT)) parsePacket(source.optJSONObject(index))?.let(::add)
                }.distinctBy { it.id }.take(LIVE_LOG_LIMIT)
                val packets = enrichOwnTraffic(parsedPackets)
                lastSuccessfulFetch = System.currentTimeMillis()
                _state.value = _state.value.copy(packets = packets, connection = ConnectionState.CONNECTED, error = null, revision = _state.value.revision + 1)
            }
            true
        } catch (error: Exception) {
            Log.e("M2Live", "Packet refresh failed", error)
            if (System.currentTimeMillis() - lastSuccessfulFetch >= 6_000) _state.value = _state.value.copy(connection = ConnectionState.ERROR, error = "Live API unavailable — retrying")
            false
        }
    }

    private suspend fun enrichOwnTraffic(packets: List<LivePacket>): List<LivePacket> {
        if (ownPublicKeys.isEmpty()) return packets

        val activeIds = packets.mapTo(mutableSetOf(), LivePacket::id)
        observationMatchCache.keys.retainAll(activeIds)
        val now = System.currentTimeMillis()
        val semaphore = Semaphore(MAX_OBSERVATION_REQUESTS)

        return coroutineScope {
            packets.map { packet ->
                async {
                    if (packet.observationCount <= 1) return@async packet

                    val cached = observationMatchCache[packet.id]
                    val cacheIsFresh = cached != null &&
                        cached.observationCount == packet.observationCount &&
                        (cached.match.confirmed || now - cached.checkedAt < NEGATIVE_MATCH_TTL_MS)
                    val match = if (cacheIsFresh) {
                        cached.match
                    } else {
                        semaphore.withPermit {
                            val details = PacketObservationRepository.load(packet.id)
                            OwnTrafficClassifier.classify(details, ownPublicKeys).also { result ->
                                observationMatchCache[packet.id] = ObservationMatch(
                                    observationCount = packet.observationCount,
                                    match = result,
                                    checkedAt = now,
                                )
                            }
                        }
                    }
                    packet.copy(
                        ownTraffic = packet.ownTraffic || match.relations.hasConfirmed,
                        possibleOwnTraffic = !packet.ownTraffic && !match.relations.hasConfirmed && match.relations.possibleKeys.isNotEmpty(),
                        matchedOwnKeys = packet.matchedOwnKeys + match.relations.confirmedKeys,
                        trackedRelations = packet.trackedRelations.merge(match.relations),
                    )
                }
            }.awaitAll()
        }
    }

    private fun parsePacket(json: JSONObject?): LivePacket? {
        json ?: return null
        val type = json.optInt("payload_type", -1)
        var decoded = json.optString("decoded_json").takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (type == 5 && decoded?.optString("decryptionStatus") == "decryption_failed") {
            decoded = decryptSavedChannel(decoded) ?: decoded
        }
        val nodeName = decoded?.optString("name")?.takeIf(String::isNotBlank)
            ?: decoded?.optString("sender")?.takeIf(String::isNotBlank)
        val publicKey = decoded?.optString("pubKey")?.takeIf(String::isNotBlank)
        val observer = json.optString("observer_name").ifBlank { "Unknown observer" }
        val flags = decoded?.optJSONObject("flags")
        val role = when {
            flags?.optBoolean("repeater") == true -> "Repeater"
            flags?.optBoolean("chat") == true -> "Companion"
            flags?.optBoolean("room") == true -> "Room"
            flags?.optBoolean("sensor") == true -> "Sensor"
            else -> null
        }
        val rawPath = runCatching {
            val array = JSONArray(json.optString("path_json", "[]"))
            buildList { for (index in 0 until array.length()) add(array.optString(index)) }.filter(String::isNotBlank)
        }.getOrDefault(emptyList())
        val path = if (type == 9) MeshPath.normalizeTrace(rawPath) else MeshPath.normalize(rawPath)
        val traceOwnTraffic = type == 9 && path.any { hop ->
            hop.length >= 4 && ownPublicKeys.any { it.startsWith(hop, ignoreCase = true) }
        }
        val decodedSource = TrackedKeyMatcher.exactFull(publicKey, ownPublicKeys)
        val decodedSourceHash = TrackedKeyMatcher.reliableHash(decoded?.optString("srcHash"), ownPublicKeys)
        val decodedDestination = TrackedKeyMatcher.exactFull(decoded?.optString("destKey"), ownPublicKeys) +
            TrackedKeyMatcher.reliableHash(decoded?.optString("destHash"), ownPublicKeys)
        val directRoute = TrackedKeyMatcher.resolvedRoute(path, emptyList(), ownPublicKeys)
        val directObserver = TrackedKeyMatcher.observer(json.optString("observer_id"), ownPublicKeys)
        val directRelations = directRoute.merge(directObserver).merge(TrackedKeyRelations(
            sourceKeys = decodedSource + decodedSourceHash,
            destinationKeys = decodedDestination,
        ))
        return LivePacket(
            id = json.optString("id"), hash = json.optString("hash"), time = WarsawTimeFormatter.time(json.optString("timestamp")),
            payloadType = type, typeLabel = payloadTypeName(type), observerName = observer,
            observerPublicKey = json.optString("observer_id"), nodeName = nodeName, nodeRole = role,
            detail = packetDetail(type, decoded, json.optString("raw_hex")), rawHex = json.optString("raw_hex"), publicKey = publicKey,
            ownTraffic = directRelations.hasConfirmed || traceOwnTraffic ||
                decoded?.optString("sender").orEmpty().trim().lowercase() in ownNodeNames ||
                decoded?.optString("name").orEmpty().trim().lowercase() in ownNodeNames ||
                TrackedMention.contains(decoded?.optString("text").orEmpty(), ownNodeNames),
            possibleOwnTraffic = path.lastOrNull()?.length == 2 &&
                MeshPath.endingKeys(path, ownPublicKeys).isNotEmpty(),
            timestamp = json.optString("timestamp"), decodedJson = decoded?.toString().orEmpty(), path = path,
            routeType = json.optNullableInt("route_type"), rssi = json.optNullableInt("rssi"), snr = json.optNullableDouble("snr"),
            observationCount = json.optInt("observation_count", 1), firstSeen = json.optString("first_seen"),
            matchedOwnKeys = directRelations.confirmedKeys,
            trackedRelations = directRelations,
        )
    }

    private fun decryptSavedChannel(decoded: JSONObject): JSONObject? {
        val hash = decoded.optString("channelHashHex")
        val mac = ChannelCrypto.decodeHex(decoded.optString("mac")) ?: return null
        val encrypted = ChannelCrypto.decodeHex(decoded.optString("encryptedData")) ?: return null
        savedChannels.asSequence()
            .filter { it.hash.equals(hash, true) && it.secret.isNotBlank() }
            .forEach { channel ->
                val secret = ChannelCrypto.decodeHex(channel.secret) ?: return@forEach
                if (!ChannelCrypto.validMac(secret, mac, encrypted)) return@forEach
                val message = ChannelCrypto.decryptMessage(secret, encrypted) ?: return@forEach
                return JSONObject(decoded.toString()).apply {
                    put("decryptionStatus", "decrypted_locally")
                    put("channel", channel.name)
                    put("sender", message.sender)
                    put("text", "${message.sender}: ${message.text}")
                }
            }
        return null
    }

    private fun packetDetail(type: Int, decoded: JSONObject?, raw: String): String = when (type) {
        4 -> decoded?.optString("name")?.takeIf(String::isNotBlank) ?: "Node advertisement"
        5 -> {
            val channel = decoded?.optString("channel")?.takeIf(String::isNotBlank) ?: "Private channel"
            val text = decoded?.optString("text")?.takeIf(String::isNotBlank)
            if (text != null) "$channel · $text" else "$channel · Message content unavailable"
        }
        2 -> "Encrypted direct message"; 8 -> "Path information"; 0 -> "Request"; 1 -> "Response"
        3 -> "Acknowledgement"; 11 -> "Control packet"; else -> "${raw.length / 2} B"
    }

    private fun payloadTypeName(type: Int): String = when (type) {
        0 -> "REQUEST"; 1 -> "RESPONSE"; 2 -> "DIRECT MSG"; 3 -> "ACK"; 4 -> "ADVERT"; 5 -> "CHANNEL MSG"
        6 -> "GROUP DATA"; 7 -> "ANON REQ"; 8 -> "PATH"; 9 -> "TRACE"; 10 -> "MULTIPART"; 11 -> "CONTROL"
        15 -> "RAW CUSTOM"; else -> "UNKNOWN $type"
    }

    private data class ObservationMatch(
        val observationCount: Int,
        val match: OwnTrafficMatch,
        val checkedAt: Long,
    )

    private companion object {
        const val LIVE_LOG_LIMIT = 250
        const val MAX_OBSERVATION_REQUESTS = 2
        const val NEGATIVE_MATCH_TTL_MS = 5 * 60_000L
    }
}

internal object OwnTrafficClassifier {
    fun classify(details: PacketObservationDetails, ownPublicKeys: Set<String>): OwnTrafficMatch {
        val relations = details.routes.fold(TrackedKeyRelations()) { result, route ->
            result.merge(TrackedKeyMatcher.resolvedRoute(route.path, route.resolvedPath, ownPublicKeys))
                .merge(TrackedKeyMatcher.observer(route.observerPublicKey, ownPublicKeys))
        }
        return OwnTrafficMatch(relations)
    }

    fun matches(details: PacketObservationDetails, ownPublicKeys: Set<String>): Boolean =
        classify(details, ownPublicKeys).relations.hasConfirmed
}

internal data class OwnTrafficMatch(
    val relations: TrackedKeyRelations,
) {
    val confirmed: Boolean get() = relations.hasConfirmed
    val possible: Boolean get() = relations.possibleKeys.isNotEmpty()
    val confirmedKeys: Set<String> get() = relations.confirmedKeys
}

internal object TrackedMention {
    fun contains(text: String, nodeNames: Set<String>): Boolean {
        val normalized = text.lowercase()
        return nodeNames.any { rawName ->
            val name = rawName.trim().lowercase()
            name.isNotBlank() && (normalized.contains("@[$name]") || normalized.contains("@$name"))
        }
    }
}

private fun JSONObject.optNullableInt(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
private fun JSONObject.optNullableDouble(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name) else null
