// File: app/src/test/java/com/example/QrEncoderTest.kt
package com.example

import com.example.photostand.core.Constants
import com.example.photostand.core.DisplayDevice
import com.example.photostand.p2p.pairing.QrEncoder
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the pure-Kotlin QR encoder by decoding its output with the independent
 * ZXing implementation. Covers ASCII, UTF-8 and the real pairing-token payload.
 */
class QrEncoderTest {

    private class BooleanMatrixSource(
        matrix: QrEncoder.QrMatrix
    ) : LuminanceSource(matrix.size + QUIET * 2, matrix.size + QUIET * 2) {
        private val modules = Array(matrix.size) { y -> BooleanArray(matrix.size) { x -> matrix.isDark(y, x) } }
        private val moduleCount = matrix.size

        private fun luminanceAt(x: Int, y: Int): Byte {
            if (x < QUIET || y < QUIET || x >= QUIET + moduleCount || y >= QUIET + moduleCount) {
                return 255.toByte() // quiet zone is always white
            }
            return if (modules[y - QUIET][x - QUIET]) 0 else 255.toByte()
        }

        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val result = row ?: ByteArray(width)
            for (x in 0 until width) result[x] = luminanceAt(x, y)
            return result
        }

        override fun getMatrix(): ByteArray {
            val pixels = ByteArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = luminanceAt(x, y)
                }
            }
            return pixels
        }

        override fun isCropSupported(): Boolean = false
        override fun rotateCounterClockwise(): LuminanceSource =
            throw UnsupportedOperationException()

        companion object {
            const val QUIET = 4
        }
    }

    private fun decode(content: String): String {
        val matrix = QrEncoder.encode(content)
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.CHARACTER_SET to Charsets.UTF_8.name()
                )
            )
        }
        val bitmap = BinaryBitmap(HybridBinarizer(BooleanMatrixSource(matrix)))
        return reader.decode(bitmap).text
    }

    @Test
    fun encodesAndDecodesAscii() {
        val content = "HELLO PHOTOSTAND"
        assertEquals(content, decode(content))
    }

    @Test
    fun encodesAndDecodesUtf8() {
        val content = "日本語 éèêë 🚀 QR テスト"
        assertEquals(content, decode(content))
    }

    @Test
    fun encodesRealPairingToken() {
        val token = DisplayDevice(
            id = "ps-a1b2c3d4e5f6",
            name = "PhotoStand Pixel Tablet",
            ipAddress = "192.168.1.100",
            port = Constants.DEFAULT_HTTP_PORT,
            sharedKey = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXowMTIzNDU=",
            bleId = "a1b2c3d4e5f6"
        ).toPairingToken()

        assertTrue("token must fit the v9-L byte-mode envelope", token.toByteArray().size <= 230)
        val decoded = decode(token)
        val reparsed = DisplayDevice.fromJson(decoded)
        assertEquals(token, reparsed?.toPairingToken())
        assertEquals("a1b2c3d4e5f6", reparsed?.bleId)
    }

    @Test
    fun picksSmallestVersionAndRejectsOversize() {
        val small = QrEncoder.encode("1")
        assertEquals(21, small.size) // version 1 matrix

        val overflow = ByteArray(231) { 'k'.code.toByte() }
        var threw = false
        try {
            QrEncoder.encode(String(overflow))
        } catch (e: QrEncoder.PayloadTooLargeException) {
            threw = true
        }
        assertTrue(threw)
    }
}
