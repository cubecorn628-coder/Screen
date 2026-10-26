// File: app/src/main/java/com/example/photostand/p2p/transfer/DisplayReceiveServer.kt
package com.example.photostand.p2p.transfer

import android.content.Context
import android.util.Log
import com.example.photostand.core.Constants
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
    private data class PairingChallenge(val controllerId: String, val expiresAtMs: Long)
    private val pairingChallengeLock = Any()
    private val pairingChallenges = HashMap<String, PairingChallenge>()

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

                method.equals("POST", ignoreCase = true) && path == "/api/v1/pairing-challenge" -> {
                    if (contentLength <= 0 || contentLength > 16 * 1024) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Invalid content length"}""".toByteArray())
                        return
                    }
                    val bodyBytes = readExact(input, contentLength)
                    if (bodyBytes.size != contentLength) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Incomplete payload"}""".toByteArray())
                        return
                    }
                    val requestJson = try {
                        JSONObject(String(bodyBytes, Charsets.UTF_8))
                    } catch (e: Exception) {
                        null
                    }
                    if (requestJson == null || requestJson.optInt("v", -1) != Constants.PROTOCOL_VERSION) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Invalid pairing request"}""".toByteArray())
                        return
                    }
                    val controllerId = requestJson.optString("controllerId", "")
                    if (controllerId.isBlank() || controllerId.length > 128 || controllerId != controllerId.trim()) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Invalid controllerId"}""".toByteArray())
                        return
                    }
                    val challenge = issuePairingChallenge(controllerId)
                    if (challenge == null) {
                        sendHttpResponse(output, 503, "Service Unavailable", "application/json",
                            """{"error":"Pairing challenge capacity reached"}""".toByteArray())
                        return
                    }
                    val body = JSONObject().apply {
                        put("challenge", challenge)
                        put("displayId", pairingStore.getOrCreateDeviceId())
                        put("expiresInMs", Constants.PAIRING_CHALLENGE_TTL_MS)
                        put("v", Constants.PROTOCOL_VERSION)
                    }.toString()
                    sendHttpResponse(output, 200, "OK", "application/json", body.toByteArray(Charsets.UTF_8))
                }

                method.equals("POST", ignoreCase = true) && path == "/api/v1/handshake" -> {
                    if (contentLength <= 0 || contentLength > 16 * 1024) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Invalid content length"}""".toByteArray())
                        return
                    }
                    val bodyBytes = readExact(input, contentLength)
                    if (bodyBytes.size != contentLength) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Incomplete payload"}""".toByteArray())
                        return
                    }
                    val bodyJson = try { JSONObject(String(bodyBytes, Charsets.UTF_8)) } catch (e: Exception) { null }
                    if (bodyJson == null || bodyJson.optInt("v", -1) != Constants.PROTOCOL_VERSION) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Invalid handshake request"}""".toByteArray())
                        return
                    }

                    val controllerId = bodyJson.optString("controllerId", "")
                    val label = bodyJson.optString("label", "Controller").take(64)
                        .replace('\n', ' ').replace('\r', ' ')
                    val challenge = bodyJson.optString("challenge", "")
                    val proof = bodyJson.optString("proof", "")
                    if (controllerId.isBlank() || controllerId.length > 128 || controllerId != controllerId.trim() ||
                        !challenge.matches(Regex("[0-9a-f]{32}")) || proof.isBlank()
                    ) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Invalid handshake fields"}""".toByteArray())
                        return
                    }

                    val proofKey = pairingStore.getControllerKey(controllerId)
                        ?: pairingStore.getOrCreateDeviceSecret()
                    val proofMessage = PairingCrypto.pairingProofMessage(
                        pairingStore.getOrCreateDeviceId(), controllerId, challenge, label
                    )
                    if (!consumePairingChallenge(challenge, controllerId) ||
                        !PairingCrypto.verifyHmac(proofMessage, proof, proofKey) ||
                        !pairingStore.registerAuthenticatedController(controllerId, label, proofKey)
                    ) {
                        sendHttpResponse(output, 401, "Unauthorized", "application/json",
                            """{"error":"Pairing proof rejected"}""".toByteArray())
                        return
                    }
                    val displayId = pairingStore.getOrCreateDeviceId()
                    val displayName = pairingStore.getDeviceName()
                    val bleId = pairingStore.getOrCreateBleId()
                    val responseProof = PairingCrypto.calculateHmac(
                        PairingCrypto.pairingResponseMessage(displayId, controllerId, challenge, displayName, bleId),
                        proofKey
                    )
                    val resp = JSONObject().apply {
                        put("status", "paired")
                        put("displayId", displayId)
                        put("displayName", displayName)
                        put("ble", bleId)
                        put("proof", responseProof)
                        put("v", Constants.PROTOCOL_VERSION)
                    }.toString()
                    sendHttpResponse(output, 200, "OK", "application/json", resp.toByteArray(Charsets.UTF_8))
                }

                method.equals("POST", ignoreCase = true) && path == "/api/v1/transfer" -> {
                    val senderDeviceId = headers["x-sender-device-id"] ?: ""
                    val signature = headers["x-signature"] ?: ""
                    val timestamp = headers["x-timestamp"]?.toLongOrNull()
                    val nonce = headers["x-nonce"] ?: ""
                    val protocolVersion = headers["x-protocol-version"]

                    if (contentLength <= 0 || contentLength > Constants.MAX_IMAGE_BYTES) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Invalid content length"}""".toByteArray())
                        return
                    }

                    if (protocolVersion != Constants.PROTOCOL_VERSION.toString()) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Unsupported protocol version"}""".toByteArray())
                        return
                    }

                    if (!pairingStore.isControllerAuthorized(senderDeviceId)) {
                        Log.w(tag, "Rejected unauthorized transfer from $senderDeviceId")
                        sendHttpResponse(output, 403, "Forbidden", "application/json",
                            """{"error":"Unauthorized device"}""".toByteArray())
                        return
                    }

                    val now = System.currentTimeMillis()
                    if (timestamp == null || timestamp < now - Constants.AUTH_CLOCK_SKEW_MS ||
                        timestamp > now + Constants.AUTH_CLOCK_SKEW_MS || !nonce.matches(Regex("[0-9a-f]{32}"))
                    ) {
                        sendHttpResponse(output, 401, "Unauthorized", "application/json",
                            """{"error":"Invalid or stale transfer authentication"}""".toByteArray())
                        return
                    }

                    val sharedKey = pairingStore.getControllerKey(senderDeviceId)
                    if (sharedKey == null || !PairingCrypto.verifyHmac(
                            PairingCrypto.transferSignatureMessage(senderDeviceId, timestamp, nonce, contentLength),
                            signature,
                            sharedKey
                        )
                    ) {
                        Log.w(tag, "HMAC verification failed for $senderDeviceId")
                        sendHttpResponse(output, 401, "Unauthorized", "application/json",
                            """{"error":"Bad signature"}""".toByteArray())
                        return
                    }

                    if (!pairingStore.consumeTransferNonce(senderDeviceId, nonce, now, Constants.AUTH_CLOCK_SKEW_MS)) {
                        sendHttpResponse(output, 409, "Conflict", "application/json",
                            """{"error":"Transfer replay rejected"}""".toByteArray())
                        return
                    }

                    // Read the exact binary payload straight from the shared stream only after auth.
                    val payloadBytes = readExact(input, contentLength)
                    if (payloadBytes.size != contentLength) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Incomplete payload"}""".toByteArray())
                        return
                    }

                    val decryptedBytes = try {
                        PairingCrypto.decrypt(payloadBytes, sharedKey)
                    } catch (e: Exception) {
                        Log.e(tag, "Decryption failed", e)
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Decryption error"}""".toByteArray())
                        return
                    }
                    val mimeType = imageMimeType(decryptedBytes)
                    if (mimeType == null) {
                        sendHttpResponse(output, 400, "Bad Request", "application/json",
                            """{"error":"Unsupported image payload"}""".toByteArray())
                        return
                    }

                    val photoDir = File(context.filesDir, "photos").apply { mkdirs() }
                    val extension = when (mimeType) {
                        "image/png" -> "png"
                        "image/webp" -> "webp"
                        else -> "jpg"
                    }
                    val photoFile = File(photoDir, "latest_photo_${System.currentTimeMillis()}.$extension")
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
                        mimeType = mimeType
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

    private fun issuePairingChallenge(controllerId: String): String? = synchronized(pairingChallengeLock) {
        val now = System.currentTimeMillis()
        val iterator = pairingChallenges.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAtMs < now) iterator.remove()
        }
        if (pairingChallenges.size >= Constants.MAX_PENDING_PAIRING_CHALLENGES) return@synchronized null
        val challenge = PairingCrypto.generateNonce()
        pairingChallenges[challenge] = PairingChallenge(
            controllerId = controllerId,
            expiresAtMs = now + Constants.PAIRING_CHALLENGE_TTL_MS
        )
        challenge
    }

    private fun consumePairingChallenge(challenge: String, controllerId: String): Boolean =
        synchronized(pairingChallengeLock) {
            val state = pairingChallenges.remove(challenge) ?: return@synchronized false
            state.controllerId == controllerId && state.expiresAtMs >= System.currentTimeMillis()
    }

    private fun imageMimeType(data: ByteArray): String? {
        if (data.size < 4) return null
        val isJpeg = data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()
        val isPng = data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
        val isWebp = data.size >= 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() &&
            data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte()
        return when {
            isJpeg -> "image/jpeg"
            isPng -> "image/png"
            isWebp -> "image/webp"
            else -> null
        }
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
