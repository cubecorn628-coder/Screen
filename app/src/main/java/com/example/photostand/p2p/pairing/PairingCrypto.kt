// File: app/src/main/java/com/example/photostand/p2p/pairing/PairingCrypto.kt
package com.example.photostand.p2p.pairing

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object PairingCrypto {
    private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val HMAC_ALGORITHM = "HmacSHA256"

    private val secureRandom = SecureRandom()

    // region Key Generation
    fun generateRandomKey(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun generateDeviceId(): String {
        val bytes = ByteArray(6)
        secureRandom.nextBytes(bytes)
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun generateNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun pairingProofMessage(displayId: String, controllerId: String, challenge: String, label: String): String =
        "photostand-pair-v3|${displayId.length}:$displayId|${controllerId.length}:$controllerId|${challenge.length}:$challenge|${label.length}:$label"

    fun pairingResponseMessage(
        displayId: String,
        controllerId: String,
        challenge: String,
        displayName: String,
        bleId: String
    ): String =
        "photostand-pair-response-v3|paired|${displayId.length}:$displayId|${controllerId.length}:$controllerId|${challenge.length}:$challenge|${displayName.length}:$displayName|${bleId.length}:$bleId"

    fun transferSignatureMessage(senderDeviceId: String, timestamp: Long, nonce: String, contentLength: Int): String =
        "photostand-transfer-v3|${senderDeviceId.length}:$senderDeviceId|$timestamp|$nonce|$contentLength"
    // endregion

    // region AES-GCM Encryption / Decryption
    /**
     * Encrypts plaintext bytes using AES-256-GCM.
     * Output format: [12 bytes IV] + [Ciphertext + 16 bytes GCM Tag]
     */
    fun encrypt(data: ByteArray, base64Key: String): ByteArray {
        val keyBytes = deriveKeyBytes(base64Key)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val cipherText = cipher.doFinal(data)
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        return combined
    }

    /**
     * Decrypts combined IV + ciphertext using AES-256-GCM.
     */
    fun decrypt(encryptedData: ByteArray, base64Key: String): ByteArray {
        if (encryptedData.size < GCM_IV_LENGTH_BYTES) {
            throw IllegalArgumentException("Data too short for AES-GCM IV")
        }

        val keyBytes = deriveKeyBytes(base64Key)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        System.arraycopy(encryptedData, 0, iv, 0, iv.size)

        val cipherTextSize = encryptedData.size - GCM_IV_LENGTH_BYTES
        val cipherText = ByteArray(cipherTextSize)
        System.arraycopy(encryptedData, GCM_IV_LENGTH_BYTES, cipherText, 0, cipherTextSize)

        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return cipher.doFinal(cipherText)
    }
    // endregion

    // region HMAC Verification
    fun calculateHmac(message: String, base64Key: String): String {
        val keyBytes = deriveKeyBytes(base64Key)
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(keyBytes, HMAC_ALGORITHM))
        val hmacBytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hmacBytes, Base64.NO_WRAP)
    }

    fun verifyHmac(message: String, signature: String, base64Key: String): Boolean {
        val calculated = calculateHmac(message, base64Key)
        return MessageDigest.isEqual(
            calculated.toByteArray(Charsets.US_ASCII),
            signature.toByteArray(Charsets.US_ASCII)
        )
    }

    fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(
            left.toByteArray(Charsets.UTF_8),
            right.toByteArray(Charsets.UTF_8)
        )
    // endregion

    private fun deriveKeyBytes(base64Key: String): ByteArray {
        val raw = try {
            Base64.decode(base64Key, Base64.DEFAULT)
        } catch (e: Exception) {
            base64Key.toByteArray(Charsets.UTF_8)
        }
        val target = ByteArray(32)
        if (raw.size >= 32) {
            System.arraycopy(raw, 0, target, 0, 32)
        } else {
            System.arraycopy(raw, 0, target, 0, raw.size)
        }
        return target
    }
}
