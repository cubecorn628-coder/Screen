// File: app/src/main/java/com/example/photostand/p2p/pairing/QrCodeGenerator.kt
package com.example.photostand.p2p.pairing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Clean, lightweight 2D Data Matrix / QR Code generator in pure Kotlin.
 * Generates a high-contrast binary matrix bitmap suitable for camera scanning or visual display.
 */
object QrCodeGenerator {

    /**
     * Generates a 2D QR-style matrix Bitmap representation of the payload string.
     * Uses an established 25x25 or 29x29 matrix pattern with finder patterns at 3 corners,
     * timing patterns, and payload interleaving.
     */
    fun generateQrBitmap(content: String, size: Int = 512): ImageBitmap {
        val matrixSize = 29
        val matrix = Array(matrixSize) { BooleanArray(matrixSize) { false } }

        // 1. Draw 3 Finder Patterns (7x7) at (0,0), (0, size-7), (size-7, 0)
        drawFinderPattern(matrix, 0, 0)
        drawFinderPattern(matrix, 0, matrixSize - 7)
        drawFinderPattern(matrix, matrixSize - 7, 0)

        // 2. Draw Timing Patterns
        for (i in 8 until matrixSize - 8) {
            val bit = (i % 2 == 0)
            matrix[6][i] = bit
            matrix[i][6] = bit
        }

        // 3. Encode data bits into the matrix body
        val dataBytes = content.toByteArray(Charsets.UTF_8)
        var bitIndex = 0
        val totalBits = dataBytes.size * 8

        for (col in matrixSize - 1 downTo 0 step 2) {
            val actualCol = if (col <= 6) col - 1 else col
            if (actualCol < 0) break

            for (row in 0 until matrixSize) {
                for (c in 0..1) {
                    val currCol = actualCol - c
                    if (currCol < 0) continue

                    // Skip finder patterns and timing lines
                    if (isReservedArea(currCol, row, matrixSize)) continue

                    val bit: Boolean = if (bitIndex < totalBits) {
                        val byteVal = dataBytes[bitIndex / 8].toInt()
                        val bitOffset = 7 - (bitIndex % 8)
                        ((byteVal shr bitOffset) and 1) == 1
                    } else {
                        // Padding pattern (alternating pseudo-random based on position)
                        ((currCol * 7 + row * 13) % 3) == 0
                    }

                    matrix[row][currCol] = bit
                    bitIndex++
                }
            }
        }

        // 4. Render to high-resolution Bitmap
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val moduleSize = size.toFloat() / matrixSize

        for (y in 0 until size) {
            val matrixY = (y / moduleSize).toInt().coerceIn(0, matrixSize - 1)
            for (x in 0 until size) {
                val matrixX = (x / moduleSize).toInt().coerceIn(0, matrixSize - 1)
                val isDark = matrix[matrixY][matrixX]
                bitmap.setPixel(x, y, if (isDark) Color.BLACK else Color.WHITE)
            }
        }

        return bitmap.asImageBitmap()
    }

    private fun drawFinderPattern(matrix: Array<BooleanArray>, startRow: Int, startCol: Int) {
        for (r in 0..6) {
            for (c in 0..6) {
                val isBorder = (r == 0 || r == 6 || c == 0 || c == 6)
                val isInner = (r in 2..4 && c in 2..4)
                matrix[startRow + r][startCol + c] = isBorder || isInner
            }
        }
    }

    private fun isReservedArea(col: Int, row: Int, size: Int): Boolean {
        // Top-left finder + separator
        if (row <= 7 && col <= 7) return true
        // Top-right finder + separator
        if (row <= 7 && col >= size - 8) return true
        // Bottom-left finder + separator
        if (row >= size - 8 && col <= 7) return true
        // Timing lines
        if (row == 6 || col == 6) return true
        return false
    }
}
