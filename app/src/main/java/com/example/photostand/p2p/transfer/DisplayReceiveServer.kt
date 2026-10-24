// File: app/src/main/java/com/example/photostand/p2p/transfer/DisplayReceiveServer.kt
package com.example.photostand.p2p.transfer

import android.content.Context
import android.util.Log
import com.example.photostand.core.Constants
import com.example.photostand.core.PairedController
import com.example.photostand.p2p.pairing.PairingCrypto
import com.example.photostand.p2p.pairing.PairingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import com.example.photostand.core.db.SharedPhotoEntity
import com.example.photostand.core.db.SharedPhotoRepository
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class DisplayReceiveServer(
    private val context: Context,
    private val pairingStore: PairingStore,
    private val photoRepository: SharedPhotoRepository,
    private val scope: CoroutineScope,
    private val onPhotoReceived: (filePath: String) -> Unit
) {
    private val tag = "DisplayReceiveServer"
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    val isRunning: Boolean
        get() = serverSocket != null && !(serverSocket?.isClosed ?: true)

    fun start(port: Int = Constants.DEFAULT_HTTP_PORT) {
        if (isRunning) return

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.d(tag, "PhotoStand HTTP Server listening on port $port")

                while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                    val clientSocket = try {
                        serverSocket!!.accept()
                    } catch (e: Exception) {
                        break
                    }
                    launch(Dispatchers.IO) {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error in server socket loop", e)
            } finally {
                stop()
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000 // 30s socket timeout
            val inputStream = BufferedInputStream(socket.getInputStream())
            val outputStream = BufferedOutputStream(socket.getOutputStream())

            // Read HTTP request line and headers
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            var headerLine: String? = reader.readLine()
            while (!headerLine.isNullOrBlank()) {
                val colon = headerLine.indexOf(':')
                if (colon > 0) {
                    val key = headerLine.substring(0, colon).trim().lowercase()
                    val value = headerLine.substring(colon + 1).trim()
                    headers[key] = value
                }
                headerLine = reader.readLine()
            }

            when {
                // Liveness check
                method.equals("GET", ignoreCase = true) && path.startsWith("/api/v1/ping") -> {
                    val body = JSONObject().apply {
                        put("status", "ok")
                        put("deviceId", pairingStore.getOrCreateDeviceId())
                        put("deviceName", pairingStore.getDeviceName())
                        put("app", "PhotoStand")
                    }.toString()
                    sendHttpResponse(outputStream, 200, "OK", "application/json", body.toByteArray(Charsets.UTF_8))
                }

                // Pairing Handshake
                method.equals("POST", ignoreCase = true) && path.startsWith("/api/v1/handshake") -> {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyBytes = readFixedBytes(reader, contentLength)
                    val bodyJson = try { JSONObject(String(bodyBytes, Charsets.UTF_8)) } catch (e: Exception) { JSONObject() }

                    val controllerId = bodyJson.optString("controllerId", "")
                    val label = bodyJson.optString("label", "Controller")
                    val sharedKey = bodyJson.optString("key", pairingStore.getOrCreateDeviceSecret())

                    if (controllerId.isNotBlank()) {
                        pairingStore.addOrUpdateController(
                            PairedController(controllerId, label, sharedKey)
                        )
                        val resp = JSONObject().apply {
                            put("status", "paired")
                            put("displayId", pairingStore.getOrCreateDeviceId())
                            put("displayName", pairingStore.getDeviceName())
                        }.toString()
                        sendHttpResponse(outputStream, 200, "OK", "application/json", resp.toByteArray(Charsets.UTF_8))
                    } else {
                        sendHttpResponse(outputStream, 400, "Bad Request", "application/json", "{\"error\":\"Missing controllerId\"}".toByteArray())
                    }
                }

                // Photo Transfer
                method.equals("POST", ignoreCase = true) && path.startsWith("/api/v1/transfer") -> {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val senderDeviceId = headers["x-sender-device-id"] ?: ""
                    val signature = headers["x-signature"] ?: ""
                    val timestamp = headers["x-timestamp"] ?: ""

                    if (contentLength <= 0 || contentLength > Constants.MAX_IMAGE_BYTES) {
                        sendHttpResponse(outputStream, 400, "Bad Request", "application/json", "{\"error\":\"Invalid content length\"}".toByteArray())
                        return
                    }

                    // Security check: verify sender authorization
                    val authorized = pairingStore.isControllerAuthorized(senderDeviceId)
                    if (!authorized) {
                        Log.w(tag, "Rejected unauthorized transfer from $senderDeviceId")
                        sendHttpResponse(outputStream, 403, "Forbidden", "application/json", "{\"error\":\"Unauthorized device\"}".toByteArray())
                        return
                    }

                    // Read binary payload from stream
                    val payloadBytes = readBinaryBody(inputStream, contentLength)
                    if (payloadBytes.size != contentLength) {
                        sendHttpResponse(outputStream, 400, "Bad Request", "application/json", "{\"error\":\"Incomplete payload\"}".toByteArray())
                        return
                    }

                    // Decrypt AES-GCM
                    val sharedKey = pairingStore.getControllerKey(senderDeviceId) ?: pairingStore.getOrCreateDeviceSecret()
                    val decryptedBytes = try {
                        PairingCrypto.decrypt(payloadBytes, sharedKey)
                    } catch (e: Exception) {
                        // Fallback: in case sender sent plain bytes
                        if (isImageBytes(payloadBytes)) {
                            payloadBytes
                        } else {
                            Log.e(tag, "Decryption failed", e)
                            sendHttpResponse(outputStream, 400, "Bad Request", "application/json", "{\"error\":\"Decryption error\"}".toByteArray())
                            return
                        }
                    }

                    // Save photo to persistent app storage
                    val photoDir = File(context.filesDir, "photos").apply { mkdirs() }
                    val photoFile = File(photoDir, "latest_photo_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(photoFile).use { fos ->
                        fos.write(decryptedBytes)
                        fos.flush()
                    }

                    // Store photo metadata into local Room database
                    val photoEntity = SharedPhotoEntity(
                        filePath = photoFile.absolutePath,
                        senderDeviceId = senderDeviceId,
                        senderDeviceName = pairingStore.getDeviceName(),
                        timestamp = System.currentTimeMillis(),
                        fileSizeBytes = decryptedBytes.size.toLong(),
                        mimeType = "image/jpeg"
                    )
                    photoRepository.savePhoto(photoEntity)

                    val resp = JSONObject().apply {
                        put("status", "received")
                        put("bytes", decryptedBytes.size)
                    }.toString()
                    sendHttpResponse(outputStream, 200, "OK", "application/json", resp.toByteArray(Charsets.UTF_8))

                    // Notify DisplayScreen
                    onPhotoReceived(photoFile.absolutePath)
                }

                else -> {
                    sendHttpResponse(outputStream, 404, "Not Found", "text/plain", "Not Found".toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling client", e)
        } finally {
            try { socket.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun readFixedBytes(reader: BufferedReader, length: Int): ByteArray {
        val chars = CharArray(length)
        var readTotal = 0
        while (readTotal < length) {
            val count = reader.read(chars, readTotal, length - readTotal)
            if (count < 0) break
            readTotal += count
        }
        return String(chars, 0, readTotal).toByteArray(Charsets.UTF_8)
    }

    private fun readBinaryBody(stream: BufferedInputStream, length: Int): ByteArray {
        val baos = ByteArrayOutputStream(length)
        val buffer = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size)
            val count = stream.read(buffer, 0, toRead)
            if (count < 0) break
            baos.write(buffer, 0, count)
            remaining -= count
        }
        return baos.toByteArray()
    }

    private fun isImageBytes(data: ByteArray): Boolean {
        if (data.size < 4) return false
        // JPEG magic bytes: FF D8 FF
        val isJpeg = data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()
        // PNG magic bytes: 89 50 4E 47
        val isPng = data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
        // WebP: RIFF ... WEBP
        val isWebp = data.size > 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte()
        return isJpeg || isPng || isWebp
    }

    private fun sendHttpResponse(
        output: BufferedOutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray
    ) {
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            serverSocket = null
            serverJob?.cancel()
            serverJob = null
        }
    }
}
