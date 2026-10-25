// File: app/src/main/java/com/example/photostand/p2p/transfer/DisplayReceiveServer.kt
package com.example.photostand.p2p.transfer

import android.content.Context
import android.util.Log
import com.example.photostand.core.Constants
import com.example.photostand.core.PairedController
import com.example.photostand.core.db.SharedPhotoEntity
import com.example.photostand.core.db.SharedPhotoRepository
import com.example.photostand.p2p.pairing.PairingCrypto
import com.example.photostand.p2p.pairing.PairingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal HTTP/1.1 server running on the Display device.
 *
 * IMPORTANT: request headers and the binary photo body are read from the SAME raw input
 * stream. The previous implementation wrapped the stream in a BufferedReader for headers
 * and then read the body from the underlying InputStream — the reader's internal buffer
 * had already swallowed part of the photo, so every non-trivial transfer failed with
 * "Incomplete payload". All parsing here is byte-based with explicit Content-Length reads.
 */
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
                serverSocket = ServerSocket(port).apply { reuseAddress = true }
                Log.d(tag, "PhotoStand HTTP Server listening on port $port")

                while (isActive && serverSocket != null && !(serverSocket?.isClosed ?: true)) {
                    val clientSocket = try {
                        serverSocket!!.accept()
                    } catch (e: Exception) {
                        break
                    }
                    launch(Dispatchers.IO) { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error in server socket loop", e)
            } finally {
                stop()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = Constants.TRANSFER_TIMEOUT_MS.toInt()
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            // --- Request line + headers, all read as raw bytes (ISO-8859-1) ---
            val requestLine = readLineAscii(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLineAscii(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

            when {
                method.equals("GET", ignoreCase = true) && path == "/api/v1/ping" -> {
                    val body = JSONObject().apply {
                        put("status", "ok")
                        put("deviceId", pairingStore.getOrCreateDeviceId())
                        put("deviceName", pairingStore.getDeviceName())
                        put("ble", pairingStore.getOrCreateBleId())
                        put("app", "PhotoStand")
                        put("v", Constants.PROTOCOL_VERSION)
                    }.toString()
                    sendHttpResponse(output, 200, "OK", "application/json", body.toByteArray(Charsets.UTF_8))
                }

                method.equals("GET", ignoreCase = true) && path == "/api/v1/info" -> {
                    val body = JSONObject().apply {
                        put("id", pairingStore.getOrCreateDeviceId())
                        put("name", pairingStore.getDeviceName())
                        put("ble", pairingStore.getOrCreateBleId())
                        put("port", Constants.DEFAULT_HTTP_PORT)
                        put("v", Constants.PROTOCOL_VERSION)
                    }.toString()
                    sendHttpResponse(output, 200, "OK", "application/json", body.toByteArray(Charsets.UTF_8))
                }

                method.equals("POST", ignoreCase = true) && path == "/api/v1/handshake" -> {
                    if (contentLength <= 0 || contentLength > 64 * 1024) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Invalid content length"}""".toByteArray())
                        return
                    }
                    val bodyBytes = readExact(input, contentLength)
                    val bodyJson = try {
                        JSONObject(String(bodyBytes, Charsets.UTF_8))
                    } catch (e: Exception) {
                        JSONObject()
                    }

                    val controllerId = bodyJson.optString("controllerId", "")
                    val label = bodyJson.optString("label", "Controller")
                    // Blank key means "use the display's own secret" — optString would otherwise
                    // happily store an empty string and break every later encrypted transfer.
                    val sharedKey = bodyJson.optString("key", "")
                        .ifBlank { pairingStore.getOrCreateDeviceSecret() }

                    if (controllerId.isBlank()) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Missing controllerId"}""".toByteArray())
                        return
                    }

                    pairingStore.addOrUpdateController(PairedController(controllerId, label, sharedKey))
                    val resp = JSONObject().apply {
                        put("status", "paired")
                        put("displayId", pairingStore.getOrCreateDeviceId())
                        put("displayName", pairingStore.getDeviceName())
                        // Echo the negotiated key so the controller persists the exact secret.
                        put("key", sharedKey)
                        put("ble", pairingStore.getOrCreateBleId())
                    }.toString()
                    sendHttpResponse(output, 200, "OK", "application/json", resp.toByteArray(Charsets.UTF_8))
                }

                method.equals("POST", ignoreCase = true) && path == "/api/v1/transfer" -> {
                    val senderDeviceId = headers["x-sender-device-id"] ?: ""
                    val signature = headers["x-signature"] ?: ""
                    val timestamp = headers["x-timestamp"] ?: ""

                    if (contentLength <= 0 || contentLength > Constants.MAX_IMAGE_BYTES) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Invalid content length"}""".toByteArray())
                        return
                    }

                    if (!pairingStore.isControllerAuthorized(senderDeviceId)) {
                        Log.w(tag, "Rejected unauthorized transfer from $senderDeviceId")
                        sendHttpResponse(output, 403, "Forbidden", "application/json",
                            """{"error":"Unauthorized device"}""".toByteArray())
                        return
                    }

                    // Read the exact binary payload straight from the shared stream.
                    val payloadBytes = readExact(input, contentLength)
                    if (payloadBytes.size != contentLength) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Incomplete payload"}""".toByteArray())
                        return
                    }

                    val sharedKey = pairingStore.getControllerKey(senderDeviceId)
                        ?: pairingStore.getOrCreateDeviceSecret()

                    // Verify HMAC when the sender provided one.
                    if (signature.isNotBlank() && timestamp.isNotBlank()) {
                        val expected = PairingCrypto.calculateHmac(
                            "$senderDeviceId:$timestamp:${payloadBytes.size}", sharedKey
                        )
                        if (!constantTimeEquals(expected, signature)) {
                            Log.w(tag, "HMAC verification failed for $senderDeviceId")
                            sendHttpResponse(output, 401, "Unauthorized", "application/json",
                                """{"error":"Bad signature"}""".toByteArray())
                            return
                        }
                    }

                    val decryptedBytes = try {
                        PairingCrypto.decrypt(payloadBytes, sharedKey)
                    } catch (e: Exception) {
                        // Backwards compatibility: accept a plain image only when no controller
                        // has ever been paired (first-run open pairing).
                        if (pairingStore.pairedControllersSnapshot().isEmpty() && isImageBytes(payloadBytes)) {
                            payloadBytes
                        } else {
                            Log.e(tag, "Decryption failed", e)
                            sendHttpResponse(output, 400, "Bad Request", "application/json",
                                """{"error":"Decryption error"}""".toByteArray())
                            return
                        }
                    }

                    val photoDir = File(context.filesDir, "photos").apply { mkdirs() }
                    val photoFile = File(photoDir, "latest_photo_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(photoFile).use { fos ->
                        fos.write(decryptedBytes)
                        fos.flush()
                    }

                    val photoEntity = SharedPhotoEntity(
                        filePath = photoFile.absolutePath,
                        senderDeviceId = senderDeviceId,
                        senderDeviceName = pairingStore.getDeviceName(),
                        timestamp = System.currentTimeMillis(),
                        fileSizeBytes = decryptedBytes.size.toLong(),
                        mimeType = "image/jpeg"
                    )
                    // This socket handler is already on Dispatchers.IO; bridge the suspend DAO.
                    runBlocking { photoRepository.savePhoto(photoEntity) }

                    val resp = JSONObject().apply {
                        put("status", "received")
                        put("bytes", decryptedBytes.size)
                    }.toString()
                    sendHttpResponse(output, 200, "OK", "application/json", resp.toByteArray(Charsets.UTF_8))

                    onPhotoReceived(photoFile.absolutePath)
                }

                else -> {
                    sendHttpResponse(output, 404, "Not Found", "text/plain", "Not Found".toByteArray())
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(tag, "Error handling client", e)
        } finally {
            try { socket.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    /** Reads a CRLF (or LF) terminated line as raw ASCII bytes, without consuming body bytes. */
    private fun readLineAscii(stream: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        var previousWasCr = false
        while (true) {
            val read = stream.read()
            if (read == -1) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (previousWasCr) {
                // Consumed CR already; LF ends the line and is skipped.
                if (read == '\n'.code) return buffer.toString("ISO-8859-1")
                buffer.write('\r'.code)
                buffer.write(read)
                previousWasCr = false
            } else if (read == '\r'.code) {
                previousWasCr = true
            } else if (read == '\n'.code) {
                return buffer.toString("ISO-8859-1")
            } else {
                buffer.write(read)
            }
        }
    }

    private fun readExact(stream: BufferedInputStream, length: Int): ByteArray {
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

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    private fun isImageBytes(data: ByteArray): Boolean {
        if (data.size < 4) return false
        val isJpeg = data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()
        val isPng = data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
        val isWebp = data.size > 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte()
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
