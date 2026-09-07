package pl.meshcore.monitor.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Encrypted, strictly bounded persistence for the 100-entry own-device log. */
class OwnTrafficLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_own_log", Context.MODE_PRIVATE)

    fun load(): List<LivePacket> = runCatching {
        val packed = prefs.getString("data", null) ?: return emptyList()
        val parts = packed.split(':', limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        val array = JSONArray(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
        buildList {
            for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
                val item = array.optJSONObject(index) ?: continue
                add(LivePacket(
                    id = item.optString("id"), hash = item.optString("hash"), time = item.optString("time"),
                    payloadType = item.optInt("payloadType", -1), typeLabel = item.optString("typeLabel"),
                    observerName = item.optString("observerName"), observerPublicKey = item.optString("observerPublicKey"),
                    nodeName = item.optString("nodeName").takeIf(String::isNotBlank),
                    nodeRole = item.optString("nodeRole").takeIf(String::isNotBlank), detail = item.optString("detail"),
                    rawHex = item.optString("rawHex"), publicKey = item.optString("publicKey").takeIf(String::isNotBlank),
                    ownTraffic = true, timestamp = item.optString("timestamp"), decodedJson = item.optString("decodedJson"),
                    path = item.optJSONArray("path")?.let { path ->
                        buildList { for (i in 0 until path.length()) add(path.optString(i)) }
                    }.orEmpty(),
                    routeType = item.optNullableInt("routeType"), rssi = item.optNullableInt("rssi"),
                    snr = item.optNullableDouble("snr"), observationCount = item.optInt("observationCount", 1),
                    firstSeen = item.optString("firstSeen"),
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun save(packets: List<LivePacket>) {
        val array = JSONArray().apply { packets.take(MAX_ENTRIES).forEach { packet ->
            put(JSONObject().apply {
                put("id", packet.id); put("hash", packet.hash); put("time", packet.time)
                put("payloadType", packet.payloadType); put("typeLabel", packet.typeLabel)
                put("observerName", packet.observerName); put("observerPublicKey", packet.observerPublicKey)
                put("nodeName", packet.nodeName.orEmpty()); put("nodeRole", packet.nodeRole.orEmpty())
                put("detail", packet.detail); put("rawHex", packet.rawHex); put("publicKey", packet.publicKey.orEmpty())
                put("timestamp", packet.timestamp); put("decodedJson", packet.decodedJson)
                put("path", JSONArray(packet.path)); put("routeType", packet.routeType); put("rssi", packet.rssi)
                put("snr", packet.snr); put("observationCount", packet.observationCount); put("firstSeen", packet.firstSeen)
            })
        } }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(array.toString().toByteArray()), Base64.NO_WRAP)
        prefs.edit().putString("data", packed).apply()
    }

    fun clearedAt(): Long = prefs.getLong("cleared_at", 0L)
    fun clear(now: Long) { prefs.edit().remove("data").putLong("cleared_at", now).apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private companion object {
        const val MAX_ENTRIES = 100
        const val ALIAS = "m2_own_log_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

private fun JSONObject.optNullableInt(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
private fun JSONObject.optNullableDouble(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name) else null
