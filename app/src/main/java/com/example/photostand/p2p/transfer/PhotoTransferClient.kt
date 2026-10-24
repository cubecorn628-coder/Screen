// File: app/src/main/java/com/example/photostand/p2p/transfer/PhotoTransferClient.kt
package com.example.photostand.p2p.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.photostand.core.Constants
import com.example.photostand.core.DisplayDevice
import com.example.photostand.core.TransferResult
import com.example.photostand.p2p.pairing.PairingCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class PhotoTransferClient(private val context: Context) {
    private val tag = "PhotoTransferClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS) // 30s timeout per specification
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // region Check Online Status
    suspend fun pingDisplay(display: DisplayDevice): Boolean = withContext(Dispatchers.IO) {
        if (display.ipAddress.isBlank()) return@withContext false
        val url = "http://${display.ipAddress}:${display.port}/api/v1/ping"
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            return@withContext false
        }
    }
    // endregion

    // region Handshake
    suspend fun performHandshake(
        display: DisplayDevice,
        controllerId: String,
        controllerLabel: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (display.ipAddress.isBlank()) return@withContext false
        val url = "http://${display.ipAddress}:${display.port}/api/v1/handshake"

        try {
            val payload = JSONObject().apply {
                put("controllerId", controllerId)
                put("label", controllerLabel)
                put("key", display.sharedKey)
            }.toString()

            val body = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(tag, "Handshake failed", e)
            return@withContext false
        }
    }
    // endregion

    // region Photo Transfer
    suspend fun sendPhoto(
        display: DisplayDevice,
        senderDeviceId: String,
        imageBytes: ByteArray
    ): TransferResult = withContext(Dispatchers.IO) {
        if (display.ipAddress.isBlank()) {
            return@withContext TransferResult.Failure("Target display IP is missing", isTimeout = false)
        }

        val url = "http://${display.ipAddress}:${display.port}/api/v1/transfer"

        try {
            // Compress image if larger than 2MB as specified in PROJECT.MD
            val optimizedBytes = compressIfLarge(imageBytes)

            // Encrypt using AES-GCM
            val encryptedBytes = try {
                if (display.sharedKey.isNotBlank()) {
                    PairingCrypto.encrypt(optimizedBytes, display.sharedKey)
                } else {
                    optimizedBytes
                }
            } catch (e: Exception) {
                optimizedBytes
            }

            val timestamp = System.currentTimeMillis()
            val signature = if (display.sharedKey.isNotBlank()) {
                PairingCrypto.calculateHmac("$senderDeviceId:$timestamp:${encryptedBytes.size}", display.sharedKey)
            } else {
                ""
            }

            val body = encryptedBytes.toRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("X-Sender-Device-Id", senderDeviceId)
                .header("X-Timestamp", timestamp.toString())
                .header("X-Signature", signature)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    TransferResult.Success("Photo printed to display successfully!")
                } else {
                    val errorBody = response.body?.string() ?: ""
                    TransferResult.Failure("Display rejected transfer (HTTP ${response.code}): $errorBody", isTimeout = false)
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.e(tag, "Transfer timed out", e)
            TransferResult.Failure("Transfer timed out after 30 seconds. Check display WiFi connection.", isTimeout = true)
        } catch (e: Exception) {
            Log.e(tag, "Transfer failed", e)
            TransferResult.Failure("Connection failed: ${e.localizedMessage ?: "Unknown network error"}", isTimeout = false)
        }
    }

    suspend fun readBytesFromUri(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var read: Int
                while (inputStream.read(chunk).also { read = it } != -1) {
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error reading image URI", e)
            null
        }
    }

    private fun compressIfLarge(rawBytes: ByteArray): ByteArray {
        if (rawBytes.size <= 2 * 1024 * 1024) {
            return rawBytes // Already under 2MB
        }
        return try {
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
            val stream = ByteArrayOutputStream()
            // Compress with high quality WebP/JPEG
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val compressed = stream.toByteArray()
            if (compressed.size < rawBytes.size) compressed else rawBytes
        } catch (e: Exception) {
            rawBytes
        }
    }
    // endregion
}
