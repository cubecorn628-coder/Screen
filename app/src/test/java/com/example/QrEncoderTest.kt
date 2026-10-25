// File: app/src/test/java/com/example/QrEncoderTest.kt
package com.example

import com.example.photostand.core.ConnectionType
import com.example.photostand.core.DisplayDevice
import com.example.photostand.p2p.pairing.QrEncoder
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.assertArrayEquals
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

    /** Decode a [QrEncoder.QrMatrix] (without quiet zone) using ZXing's internal QR decoder. */
    private fun decode(matrix: QrEncoder.QrMatrix): com.google.zxing.common.DecoderResult {
        val bits = BitMatrix(matrix.size, matrix.size)
        for (row in 0 until matrix.size) {
            for (col in 0 until matrix.size) {
                if (matrix.isDark(row, col)) bits.set(col, row)
            }
        }
        return zxingDecoder.decode(bits)
    }

    @Test
    fun `picks smallest version and rejects oversize payload`() {
        val small = QrEncoder.encode("hi")
        assertTrue("version 1 is 21x21, was ${small.size}", small.size == 21)

        val medium = QrEncoder.encode("A".repeat(60))
        assertTrue("60 chars need more than version 1, was ${medium.size}", medium.size > 21)

        // Envelope ceiling is version 9-L = 230 byte-mode bytes.
        assertThrows(QrEncoder.PayloadTooLargeException::class.java) {
            QrEncoder.encode(String(ByteArray(231) { 'k'.code.toByte() }))
        }
    }

    @Test
    fun `encodes and decodes ascii`() {
        // 44 chars x 5 = 220 bytes, inside the 230-byte version 9-L envelope.
        val content = "local-send style test 0123456789 abcdefghij ".repeat(5)
        val matrix = QrEncoder.encode(content)
        assertEquals(content, decode(matrix).text)
    }

    /**
     * Extract the byte-mode payload from ZXing's raw data codewords: the stream starts
     * with a 4-bit mode indicator and an 8-bit character count (versions 1-9), i.e.
     * 12 header bits, followed by the payload bytes and optional padding.
     */
    private fun byteModePayload(rawCodewords: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        for (i in 0 until length) {
            var value = 0
            for (bit in 0 until 8) {
                val globalBit = 12 + i * 8 + bit
                val sourceBit = (rawCodewords[globalBit / 8].toInt() ushr (7 - globalBit % 8)) and 1
                value = (value shl 1) or sourceBit
            }
            out[i] = value.toByte()
        }
        return out
    }

    @Test
    fun `encodes and decodes utf8`() {
        val content = "日本語 éèêë 🚀 QR テスト — pairing token".repeat(4)
        val expectedBytes = content.toByteArray(Charsets.UTF_8)
        val matrix = QrEncoder.encode(content)
        // Compare raw payload bytes: ZXing's text conversion depends on the platform
        // default charset, but byte-mode payload bytes must match our UTF-8 input exactly.
        assertArrayEquals(expectedBytes, byteModePayload(decode(matrix).rawBytes, expectedBytes.size))
    }

    @Test
    fun `renders required finder patterns and separators`() {
        val matrix = QrEncoder.encode("finders")
        // Top-left finder: dark outer border.
        assertTrue(matrix.isDark(0, 0))
        assertTrue(matrix.isDark(6, 6))
        // Light separator ring around the top-left finder.
        assertTrue(!matrix.isDark(7, 7))
        // Light separator next to the top-right finder.
        assertTrue(!matrix.isDark(7, matrix.size - 8))
        // Fixed dark module beside the timing patterns.
        assertTrue(matrix.isDark(matrix.size - 8, 8))
    }

    @Test
    fun `encodes real pairing token and stays inside the envelope`() {
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
        assertTrue("token must fit version 9-L (230 bytes), was ${token.toByteArray().size}",
            token.toByteArray().size <= 230)

        val matrix = QrEncoder.encode(token)
        assertTrue("must fit version 9 (53 modules), was ${matrix.size}", matrix.size <= 53)

        val reparsed = DisplayDevice.fromJson(decode(matrix).text)
        assertNotNull(reparsed)
        assertEquals(token, reparsed?.toPairingToken())
    }

    @Test
    fun `same input always produces identical matrix`() {
        val a = QrEncoder.encode("deterministic-123")
        val b = QrEncoder.encode("deterministic-123")
        assertEquals(a.size, b.size)
        for (row in 0 until a.size) for (col in 0 until a.size) {
            assertEquals(a.isDark(row, col), b.isDark(row, col))
        }
    }
}
