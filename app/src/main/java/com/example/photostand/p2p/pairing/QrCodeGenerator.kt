// File: app/src/main/java/com/example/photostand/p2p/pairing/QrCodeGenerator.kt
package com.example.photostand.p2p.pairing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Renders a real, camera-scannable QR code (ISO/IEC 18004) produced by [QrEncoder].
 * The previous implementation drew a QR-like decoration that no scanner could read;
 * this one emits a spec-compliant matrix with a 4-module quiet zone.
 */
object QrCodeGenerator {

    private const val QUIET_ZONE_MODULES = 4

    fun generateQrBitmap(content: String, size: Int = 512): ImageBitmap {
        val matrix = QrEncoder.encode(content)
        val moduleCount = matrix.size + QUIET_ZONE_MODULES * 2
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

        // White background first (also fills the quiet zone)
        bitmap.eraseColor(Color.WHITE)

        val moduleSize = size.toFloat() / moduleCount
        for (row in 0 until matrix.size) {
            for (col in 0 until matrix.size) {
                if (!matrix.isDark(row, col)) continue
                val left = (col + QUIET_ZONE_MODULES) * moduleSize
                val top = (row + QUIET_ZONE_MODULES) * moduleSize
                val right = (col + QUIET_ZONE_MODULES + 1) * moduleSize
                val bottom = (row + QUIET_ZONE_MODULES + 1) * moduleSize
                val x0 = left.toInt().coerceAtLeast(0)
                val y0 = top.toInt().coerceAtLeast(0)
                val x1 = right.toInt().coerceAtMost(size)
                val y1 = bottom.toInt().coerceAtMost(size)
                for (y in y0 until y1) {
                    for (x in x0 until x1) bitmap.setPixel(x, y, Color.BLACK)
                }
            }
        }
        return bitmap.asImageBitmap()
    }
}
