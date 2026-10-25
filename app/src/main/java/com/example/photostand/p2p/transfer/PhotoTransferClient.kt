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
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Result of a pairing handshake; carries the negotiated key back to the caller. */
data class HandshakeResult(
    val success: Boolean,
    val displayId: String = "",
    val displayName: String = "",
    val sharedKey: String = "",
    val bleId: String = ""
)

class PhotoTransferClient(private val context: Context) {
    private val tag = "PhotoTransferClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Real-world photo transfers on congested WiFi need headroom beyond 30s.
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun pingDisplay(display: DisplayDevice): Boolean = withContext(Dispatchers.IO) {
        if (display.ipAddress.isBlank()) return@withContext false
        val url = "http://${display.ipAddress}:${display.port}/api/v1/ping"
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Registers this controller with the display. The display replies with the negotiated
     * shared key and its BLE identity, which the caller persists for trusted auto-connect.
     */
    suspend fun performHandshake(
        display: DisplayDevice,
        controllerId: String,
        controllerLabel: String
    ): HandshakeResult = withContext(Dispatchers.IO) {
        if (display.ipAddress.isBlank()) {
            return@withContext HandshakeResult(false)
        }
        val url = "http://${display.ipAddress}:${display.port}/api/v1/handshake"
        try {
            val payload = JSONObject().apply {
                put("controllerId", controllerId)
                put("label", controllerLabel)
                put("key", display.sharedKey)
            }.toString()

            val body = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext HandshakeResult(false)
                val json = JSONObject(response.body?.string().orEmpty())
                HandshakeResult(
                    success = true,
                    displayId = json.optString("displayId", display.id),
                    displayName = json.optString("displayName", display.name),
                    sharedKey = json.optString("key", display.sharedKey),
                    bleId = json.optString("ble", display.bleId)
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Handshake failed", e)
            HandshakeResult(false)
        }
    }

    suspend fun sendPhoto(
        display: DisplayDevice,
        senderDeviceId: String,
        imageBytes: ByteArray
    ): TransferResult = withContext(Dispatchers.IO) {
        if (display.ipAddress.isBlank()) {
            return@withContext TransferResult.Failure(
                "No network address for ${display.name}. Pair it over WiFi or BLE first.",
                isTimeout = false
            )
        }

        val url = "http://${display.ipAddress}:${display.port}/api/v1/transfer"

        val optimizedBytes = compressIfLarge(imageBytes)
        val encryptedBytes = try {
            if (display.sharedKey.isNotBlank()) {
                PairingCrypto.encrypt(optimizedBytes, display.sharedKey)
            } else {
                optimizedBytes
            }
        } catch (e: Exception) {
            optimizedBytes
        }

        var lastError: String? = null
        var wasTimeout = false

        // Retry transient failures (connection reset, half-open socket, brief AP hiccup).
        repeat(Constants.TRANSFER_MAX_RETRIES) { attempt ->
            val timestamp = System.currentTimeMillis()
            val signature = if (display.sharedKey.isNotBlank()) {
                PairingCrypto.calculateHmac(
                    "$senderDeviceId:$timestamp:${encryptedBytes.size}",
                    display.sharedKey
                )
            } else ""

            val requestBody = encryptedBytes.toRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("X-Sender-Device-Id", senderDeviceId)
                .header("X-Timestamp", timestamp.toString())
                .header("X-Signature", signature)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext TransferResult.Success("Photo printed to ${display.name}!")
                    }
                    val errorBody = response.body?.string().orEmpty()
                    lastError = "Display rejected transfer (HTTP ${response.code}): $errorBody"
                    // 4xx (auth/protocol) errors will not improve on retry.
                    if (response.code in 400..499) {
                        return@withContext TransferResult.Failure(lastError!!, isTimeout = false)
                    }
                }
            } catch (e: SocketTimeoutException) {
                wasTimeout = true
                lastError = "Transfer timed out. Check that ${display.name} is awake and reachable."
                Log.e(tag, "Transfer timed out (attempt ${attempt + 1})", e)
            } catch (e: IOException) {
                lastError = "Connection failed: ${e.localizedMessage ?: "network error"}"
                Log.e(tag, "Transfer failed (attempt ${attempt + 1})", e)
            }
        }
        TransferResult.Failure(lastError ?: "Unknown transfer error", isTimeout = wasTimeout)
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
        if (rawBytes.size <= 2 * 1024 * 1024) return rawBytes
        return try {
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val compressed = stream.toByteArray()
            if (compressed.size < rawBytes.size) compressed else rawBytes
        } catch (e: Exception) {
            rawBytes
        }
    }
}
