package pl.meshcore.monitor.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.MessageDigest
import java.time.Instant

data class SavedChannel(
    val name: String,
    val hash: String,
    val secret: String = "",
    val isPrivate: Boolean = false,
)

class SecureChannelStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_channels", Context.MODE_PRIVATE)

    @Synchronized fun load(): List<SavedChannel> = runCatching {
        val packed = prefs.getString("data", null) ?: return emptyList()
        val parts = packed.split(':', limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        val plain = String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        val array = JSONArray(plain)
        buildList {
            for (i in 0 until array.length()) array.optJSONObject(i)?.let {
                val name = it.optString("name")
                val legacyPrivate = !name.startsWith("#") && !name.equals("public", true) && it.optString("secret").isNotBlank()
                add(SavedChannel(name, it.optString("hash"), it.optString("secret"),
                    it.optBoolean("isPrivate", legacyPrivate)))
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized fun save(channels: List<SavedChannel>) {
        val bounded = channels.take(MAX_CHANNELS)
        val array = JSONArray().apply {
            bounded.forEach { put(JSONObject().put("name", it.name).put("hash", it.hash)
                .put("secret", it.secret).put("isPrivate", it.isPrivate)) }
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(array.toString().toByteArray())
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val activeMessageKeys = bounded.map { "messages_${fingerprint(it)}" }.toSet()
        val activeClearedKeys = bounded.map { "messages_cleared_${fingerprint(it)}" }.toSet()
        val activeReadKeys = bounded.map { "channel_read_${fingerprint(it)}" }.toSet()
        prefs.edit().apply {
            putString("data", packed)
            prefs.all.keys.filter {
                it.startsWith("messages_") && !it.startsWith("messages_cleared_") && it !in activeMessageKeys
            }.forEach(::remove)
            prefs.all.keys.filter { it.startsWith("messages_cleared_") && it !in activeClearedKeys }.forEach(::remove)
            prefs.all.keys.filter { it.startsWith("channel_read_") && it !in activeReadKeys }.forEach(::remove)
        }.commit()
    }

    @Synchronized fun loadMessages(channel: SavedChannel): List<ChannelMessage> = runCatching {
        val plain = decrypt(prefs.getString("messages_${fingerprint(channel)}", null) ?: return emptyList())
        val array = JSONArray(plain)
        val clearedAt = prefs.getLong("messages_cleared_${fingerprint(channel)}", 0L)
        buildList { for (i in 0 until minOf(array.length(), MAX_MESSAGES)) array.optJSONObject(i)?.let { item ->
            val timestamp = item.optString("timestamp")
            if (messageEpoch(timestamp) <= clearedAt) return@let
            val sender = item.optString("sender")
            val text = item.optString("text")
            if (channel.secret.isNotBlank() && sender.equals("Anonymous", true) && text.isBlank()) return@let
            add(ChannelMessage(
                id = item.optString("id"), sender = sender, text = text,
                timestamp = timestamp, hops = item.optInt("hops"),
                observers = item.optJSONArray("observers")?.let { values ->
                    buildList { for (j in 0 until values.length()) add(values.optString(j)) }
                }.orEmpty(), repeats = item.optInt("repeats", 1),
                snr = if (item.has("snr") && !item.isNull("snr")) item.optDouble("snr") else null,
                packetHash = item.optString("packetHash"),
            ))
        } }
    }.getOrDefault(emptyList())

    @Synchronized fun mergeMessages(channel: SavedChannel, incoming: List<ChannelMessage>): List<ChannelMessage> {
        val clearedAt = prefs.getLong("messages_cleared_${fingerprint(channel)}", 0L)
        val fresh = incoming.filter { messageEpoch(it.timestamp) > clearedAt }
        if (fresh.isEmpty()) return loadMessages(channel)
        val merged = (fresh + loadMessages(channel)).distinctBy { it.id }
            .sortedByDescending { it.timestamp }.take(MAX_MESSAGES)
        val array = JSONArray().apply { merged.forEach { message -> put(JSONObject().apply {
            put("id", message.id); put("sender", message.sender); put("text", message.text)
            put("timestamp", message.timestamp); put("hops", message.hops)
            put("observers", JSONArray(message.observers)); put("repeats", message.repeats)
            put("snr", message.snr); put("packetHash", message.packetHash)
        }) } }
        prefs.edit().putString("messages_${fingerprint(channel)}", encrypt(array.toString())).commit()
        return merged
    }

    @Synchronized fun clearMessages(channel: SavedChannel) {
        val fingerprint = fingerprint(channel)
        prefs.edit().remove("messages_$fingerprint")
            .putLong("messages_cleared_$fingerprint", System.currentTimeMillis()).commit()
    }

    @Synchronized fun lastReadAt(channel: SavedChannel): Long {
        val key = "channel_read_${fingerprint(channel)}"
        return prefs.getLong(key, 0L)
    }

    @Synchronized fun markRead(channel: SavedChannel): Long {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("channel_read_${fingerprint(channel)}", now).commit()
        return now
    }

    private fun messageEpoch(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    private fun fingerprint(channel: SavedChannel): String {
        val identity = channel.secret.ifBlank { channel.name.lowercase() }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

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
        const val ALIAS = "meshcore_channel_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_MESSAGES = 250
        const val MAX_CHANNELS = 50
    }
}
