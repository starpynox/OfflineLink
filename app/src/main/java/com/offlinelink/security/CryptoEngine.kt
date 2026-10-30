package com.offlinelink.security

import android.util.Base64
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Base64Compat {
    fun encodeToString(bytes: ByteArray): String {
        return try {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (_: Throwable) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    fun decode(base64: String): ByteArray {
        return try {
            java.util.Base64.getDecoder().decode(base64)
        } catch (_: Throwable) {
            android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
        }
    }
}

data class EncryptedResult(
    val iv: ByteArray,
    val ciphertext: ByteArray
) {
    fun toBase64(): String {
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64Compat.encodeToString(combined)
    }

    companion object {
        fun fromBase64(base64: String, ivSize: Int = 12): EncryptedResult? {
            return try {
                val combined = Base64Compat.decode(base64)
                if (combined.size < ivSize) return null
                val iv = combined.copyOfRange(0, ivSize)
                val ciphertext = combined.copyOfRange(ivSize, combined.size)
                EncryptedResult(iv, ciphertext)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Robust cryptographic engine providing standard AES-256-GCM authenticated encryption,
 * HMAC-SHA256 origin authentication, and deterministic SAS (Short Authentication String) generation.
 */
object CryptoEngine {
    private const val GCM_IV_LENGTH = 12 // 96 bits recommended for GCM
    private const val GCM_TAG_LENGTH = 128 // 128 bits authentication tag
    private val secureRandom = SecureRandom()

    fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    fun computeSha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    fun computeSha256Hex(data: ByteArray): String {
        val hash = computeSha256(data)
        return hash.joinToString("") { "%02X".format(it) }
    }

    fun formatFingerprint(hexHash: String): String {
        return hexHash.chunked(2).take(16).joinToString(":")
    }

    fun computeHmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key, "HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(data)
    }

    /**
     * Authenticated AES-256-GCM encryption with random 96-bit IV.
     * Throws exception if encryption fails.
     */
    fun encryptAesGcm(key: ByteArray, plaintext: ByteArray): EncryptedResult {
        require(key.size == 32) { "AES-256 key must be exactly 32 bytes (got ${key.size})" }
        val iv = generateRandomBytes(GCM_IV_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedResult(iv, ciphertext)
    }

    /**
     * Authenticated AES-256-GCM decryption.
     * Returns null if authentication tag fails or data is corrupted (tamper detection).
     */
    fun decryptAesGcm(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (key.size != 32 || iv.size != GCM_IV_LENGTH || ciphertext.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            val keySpec = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            // Authentication tag mismatch or corrupted ciphertext
            null
        }
    }

    /**
     * Computes a user-friendly 6-digit Short Authentication String (SAS)
     * derived deterministically from the sorted peer fingerprints.
     */
    fun computeSasCode(fingerprintA: String, fingerprintB: String): String {
        val sorted = if (fingerprintA < fingerprintB) "$fingerprintA|$fingerprintB" else "$fingerprintB|$fingerprintA"
        val hash = computeSha256(sorted.toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.wrap(hash)
        val positiveInt = Math.abs(buffer.int)
        val sixDigit = positiveInt % 1000000
        return "%06d".format(sixDigit)
    }

    /**
     * Derives a stable 32-byte pairwise communication key between two node identities.
     * If an explicit shared secret is available, it is salted and stretched via HMAC-SHA256.
     */
    fun derivePairwiseKey(nodeIdA: String, nodeIdB: String, saltSecret: ByteArray? = null): ByteArray {
        val sortedNodes = if (nodeIdA < nodeIdB) "$nodeIdA:$nodeIdB" else "$nodeIdB:$nodeIdA"
        val baseSecret = saltSecret ?: "OfflineLink-Pairwise-Master-Key-Material-V1".toByteArray(Charsets.UTF_8)
        return computeHmacSha256(computeSha256(baseSecret), sortedNodes.toByteArray(Charsets.UTF_8))
    }
}
