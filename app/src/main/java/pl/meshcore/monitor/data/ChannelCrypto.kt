package pl.meshcore.monitor.data

import android.annotation.SuppressLint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ChannelCrypto {
    fun deriveSecret(name: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(name.toByteArray()).copyOf(16)

    fun channelHash(secret: ByteArray): String = "%02X".format(
        MessageDigest.getInstance("SHA-256").digest(secret)[0].toInt() and 0xff,
    )

    fun validMac(secret: ByteArray, expected: ByteArray, encrypted: ByteArray): Boolean {
        if (expected.size < 2) return false
        val key = ByteArray(32).also { secret.copyInto(it) }
        val actual = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(encrypted)
        }
        return actual[0] == expected[0] && actual[1] == expected[1]
    }

    @SuppressLint("GetInstance") // MeshCore wire protocol requires AES-ECB without padding.
    fun decryptMessage(secret: ByteArray, encrypted: ByteArray): DecryptedMessage? = runCatching {
        val plain = Cipher.getInstance("AES/ECB/NoPadding").run {
            // MeshCore always encrypts with AES-128 (the first 16 bytes). A
            // 32-byte channel secret is used in full only by the HMAC.
            init(Cipher.DECRYPT_MODE, SecretKeySpec(secret.copyOf(16), "AES"))
            doFinal(encrypted)
        }
        if (plain.size < 6) return null
        val epoch = ByteBuffer.wrap(plain, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        val body = String(plain.copyOfRange(5, plain.size).takeWhile { it != 0.toByte() }.toByteArray(), Charsets.UTF_8)
        val split = body.indexOf(": ")
        DecryptedMessage(
            sender = if (split > 0) body.substring(0, split) else "Anonymous",
            text = if (split > 0) body.substring(split + 2) else body,
            timestamp = Instant.ofEpochSecond(epoch).toString(),
        )
    }.getOrNull()

    fun decodeHex(value: String): ByteArray? {
        if (value.length < 2 || value.length % 2 != 0) return null
        return runCatching { value.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
    }

    fun encodeHex(value: ByteArray): String = value.joinToString("") { "%02x".format(it) }
}

data class DecryptedMessage(val sender: String, val text: String, val timestamp: String)
