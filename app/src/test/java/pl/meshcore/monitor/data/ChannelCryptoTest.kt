package pl.meshcore.monitor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCryptoTest {
    @Test fun testChannelUsesProtocolHash() {
        val secret = ChannelCrypto.deriveSecret("#test")
        assertEquals(16, secret.size)
        assertEquals("D9", ChannelCrypto.channelHash(secret))
    }

    @Test fun invalidHexIsRejected() {
        assertNull(ChannelCrypto.decodeHex("abc"))
        assertNull(ChannelCrypto.decodeHex("zz"))
    }

    @Test fun privateChannelMacAndPayloadAreDecoded() {
        val secret = ChannelCrypto.decodeHex(
            "8b3387e9c5cdea6ac9e5edbaa115cd72" +
                "7b47419961fa3d436e5a887739100d42",
        )!!
        val plain = byteArrayOf(0x00, 0xF1.toByte(), 0x53, 0x65, 0x00) +
            "Alice: private message".toByteArray()
        val padded = plain.copyOf(((plain.size + 15) / 16) * 16)
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(secret.copyOf(16), "AES"))
            doFinal(padded)
        }
        val hmacKey = ByteArray(32).also { secret.copyInto(it) }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(hmacKey, "HmacSHA256")); doFinal(cipher).copyOf(2)
        }

        assertTrue(ChannelCrypto.validMac(secret, mac, cipher))
        val decoded = ChannelCrypto.decryptMessage(secret, cipher)!!
        assertEquals("Alice", decoded.sender)
        assertEquals("private message", decoded.text)
    }
}
