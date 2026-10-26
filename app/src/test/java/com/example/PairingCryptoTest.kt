// File: app/src/test/java/com/example/PairingCryptoTest.kt
package com.example

import com.example.photostand.core.DisplayDevice
import com.example.photostand.p2p.pairing.PairingCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingCryptoTest {

    @Test
    fun testAesGcmEncryptionDecryption() {
        val originalText = "PhotoStand secure peer-to-peer payload test"
        val data = originalText.toByteArray(Charsets.UTF_8)
        val key = PairingCrypto.generateRandomKey()

        val encrypted = PairingCrypto.encrypt(data, key)
        assertTrue(encrypted.size > data.size)

        val decrypted = PairingCrypto.decrypt(encrypted, key)
        val decryptedText = String(decrypted, Charsets.UTF_8)

        assertEquals(originalText, decryptedText)
        assertArrayEquals(data, decrypted)
    }

    @Test
    fun testHmacVerification() {
        val message = "device-123:1700000000:4096"
        val key = PairingCrypto.generateRandomKey()

        val signature = PairingCrypto.calculateHmac(message, key)
        assertTrue(signature.isNotBlank())
        assertTrue(PairingCrypto.verifyHmac(message, signature, key))
        assertTrue(!PairingCrypto.verifyHmac("$message:changed", signature, key))
        assertTrue(!PairingCrypto.verifyHmac(message, signature, PairingCrypto.generateRandomKey()))
    }

    @Test
    fun testNonceIsRandomAndHasExpectedWireFormat() {
        val first = PairingCrypto.generateNonce()
        val second = PairingCrypto.generateNonce()

        assertTrue(first.matches(Regex("[0-9a-f]{32}")))
        assertTrue(second.matches(Regex("[0-9a-f]{32}")))
        assertTrue(first != second)
    }

    @Test
    fun testDisplayDeviceSerialization() {
        val original = DisplayDevice(
            id = "disp-abc123",
            name = "Living Room Display",
            ipAddress = "192.168.1.150",
            port = 9876,
            sharedKey = "test-key-base64"
        )

        val json = original.toJson()
        val parsed = DisplayDevice.fromJson(json)

        assertNotNull(parsed)
        assertEquals(original.id, parsed?.id)
        assertEquals(original.name, parsed?.name)
        assertEquals(original.ipAddress, parsed?.ipAddress)
        assertEquals(original.port, parsed?.port)
        assertEquals(original.sharedKey, parsed?.sharedKey)
    }
}
