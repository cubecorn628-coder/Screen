// File: app/src/test/java/com/example/QrEncoderTest/QrEncoderTest.kt
package com.example

import com.example.photostand.core.ConnectionType
import com.example.photostand.core.DisplayDevice
import com.example.photostand.p2p.qr.BooleanMatrix
import com.example.photostand.p2p.qr.QrEncoder
import com.example.photostand.p2p.qr.QrPayloadTooLargeException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the dependency-free QR encoder.
 *
 * Encoded symbols are validated by feeding them straight into ZXing's QR [Decoder],
 * which performs the full format/version/EC/data decoding on a raw [BitMatrix] —
 * no image binarization quirks involved. Robolectric is used because the pairing-token
 * round-trip test exercises the Android `org.json` implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrEncoderTest {

    private val zxingDecoder = Decoder()

    /** Decode a [BooleanMatrix] (without quiet zone) using ZXing's internal QR decoder. */
    private fun decode(matrix: BooleanMatrix): String {
        val bits = BitMatrix(matrix.size, matrix.size)
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix.get(y, x)) bits.set(x, y)
            }
        }
        return zxingDecoder.decode(bits).text
    }

    @Test
    fun `picks smallest version and rejects oversize payload`() {
        val small = QrEncoder.encode("hi")
        assertTrue("version 1 is 21x21, was ${small.size}", small.size == 21)

        val medium = QrEncoder.encode("A".repeat(60))
        assertTrue("60 chars need more than version 1, was ${medium.size}", medium.size > 21)

        // Version 8-L byte-mode capacity is 181 bytes; 231 bytes cannot fit at all.
        assertThrows(QrPayloadTooLargeException::class.java) {
            QrEncoder.encode(String(ByteArray(231) { 'k'.code.toByte() }))
        }
    }

    @Test
    fun `encodes and decodes ascii`() {
        val content = "local-send style test 0123456789 abcdefghij ".repeat(8)
        val matrix = QrEncoder.encode(content)
        assertEquals(content, decode(matrix))
    }

    @Test
    fun `encodes and decodes utf8`() {
        val content = "日本語 éèêë 🚀 QR テスト — pairing token".repeat(4)
        val matrix = QrEncoder.encode(content)
        // ZXing's raw decoder returns the byte-mode payload as ISO-8859-1; re-interpret UTF-8.
        val reparsed = String(decode(matrix).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        assertEquals(content, reparsed)
    }

    @Test
    fun `renders required finder and quiet-zone-free matrix correctly`() {
        val matrix = QrEncoder.encode("finders")
        // Top-left finder pattern: 7x7 dark border with a light ring.
        assertTrue(matrix.get(0, 0))
        assertTrue(matrix.get(6, 6))
        assertTrue(!matrix.get(7, 7))
        // Fixed module: top-right finder separated by a light separator.
        assertTrue(!matrix.get(7, matrix.size - 8))
    }

    @Test
    fun `encodes real pairing token and stays under version 8`() {
        val display = DisplayDevice(
            id = "display-aabbccddeeff",
            name = "PhotoStand Ruang Tamu",
            ipAddress = "192.168.1.42",
            port = 9876,
            sharedKey = "f9c8174a9b03d5e6f2148ab7c0d3e5f6a1b2c3d4e5f60718293a4b5c6d7e8f90",
            bleId = "aabbccddeeff",
            connectionType = ConnectionType.WIFI_MDNS,
            autoConnect = true
        )
        val token = display.toPairingToken()
        assertTrue("token must fit version 8-L (181 bytes), was ${token.toByteArray().size}",
            token.toByteArray().size <= 181)

        val matrix = QrEncoder.encode(token)
        assertTrue("must fit version 8 (49 modules), was ${matrix.size}", matrix.size <= 49)

        val reparsed = DisplayDevice.fromJson(decode(matrix))
        assertNotNull(reparsed)
        assertEquals(token, reparsed?.toPairingToken())
    }

    @Test
    fun `same input always produces identical matrix`() {
        val a = QrEncoder.encode("deterministic-123")
        val b = QrEncoder.encode("deterministic-123")
        for (y in 0 until a.size) for (x in 0 until a.size) {
            assertEquals(a.get(y, x), b.get(y, x))
        }
    }
}
