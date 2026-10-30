package com.offlinelink.security

import org.junit.Assert.*
import org.junit.Test

class CryptoEngineTest {

    @Test
    fun testAesGcmEncryptionAndDecryptionRoundTrip() {
        val key = CryptoEngine.generateRandomBytes(32) // 256-bit AES key
        val originalPlaintext = "Secure emergency coordination message: Grid Down in Sector 4".toByteArray(Charsets.UTF_8)

        val encrypted = CryptoEngine.encryptAesGcm(key, originalPlaintext)
        assertNotNull(encrypted)
        assertEquals(12, encrypted.iv.size)
        assertTrue(encrypted.ciphertext.isNotEmpty())

        val decrypted = CryptoEngine.decryptAesGcm(key, encrypted.iv, encrypted.ciphertext)
        assertNotNull(decrypted)
        assertArrayEquals(originalPlaintext, decrypted)
    }

    @Test
    fun testTamperedCiphertextFailsDecryption() {
        val key = CryptoEngine.generateRandomBytes(32)
        val plaintext = "Tamper detection test".toByteArray(Charsets.UTF_8)
        val encrypted = CryptoEngine.encryptAesGcm(key, plaintext)

        // Flip one bit in ciphertext
        val tamperedCiphertext = encrypted.ciphertext.copyOf()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0x01).toByte()

        val result = CryptoEngine.decryptAesGcm(key, encrypted.iv, tamperedCiphertext)
        assertNull("Decryption of tampered ciphertext must return null", result)
    }

    @Test
    fun testHmacSha256OriginAuthentication() {
        val key = CryptoEngine.generateRandomBytes(32)
        val message = "Origin authenticated protocol frame".toByteArray(Charsets.UTF_8)

        val mac1 = CryptoEngine.computeHmacSha256(key, message)
        val mac2 = CryptoEngine.computeHmacSha256(key, message)

        assertArrayEquals("HMAC must be deterministic for identical input", mac1, mac2)

        val differentMessage = "Origin authenticated protocol frame modified".toByteArray(Charsets.UTF_8)
        val macDifferent = CryptoEngine.computeHmacSha256(key, differentMessage)
        assertFalse(mac1.contentEquals(macDifferent))
    }

    @Test
    fun testDeterministicSasCodeCalculation() {
        val fingerprintAlice = "AA:BB:CC:DD:EE:FF:11:22:33:44:55:66:77:88:99:00"
        val fingerprintBob   = "11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:FF"

        // SAS must be symmetric: Alice and Bob must see the EXACT SAME 6-digit code regardless of parameter order
        val sasCodeAlice = CryptoEngine.computeSasCode(fingerprintAlice, fingerprintBob)
        val sasCodeBob   = CryptoEngine.computeSasCode(fingerprintBob, fingerprintAlice)

        assertEquals("SAS code must be commutative and identical on both peer displays", sasCodeAlice, sasCodeBob)
        assertEquals("SAS code must be exactly 6 digits", 6, sasCodeAlice.length)
        assertTrue("SAS code must be purely numeric", sasCodeAlice.all { it.isDigit() })
    }

    @Test
    fun testSha256HexAndFingerprintFormatting() {
        val testData = "OfflineLink".toByteArray(Charsets.UTF_8)
        val hex = CryptoEngine.computeSha256Hex(testData)
        val fingerprint = CryptoEngine.formatFingerprint(hex)

        assertEquals(64, hex.length)
        assertTrue(fingerprint.contains(":"))
    }

    @Test
    fun testPairwiseKeyDerivationSymmetric() {
        val keyAB = CryptoEngine.derivePairwiseKey("NODE-A", "NODE-B")
        val keyBA = CryptoEngine.derivePairwiseKey("NODE-B", "NODE-A")

        assertArrayEquals("Pairwise key derivation must produce identical key for both nodes", keyAB, keyBA)
        assertEquals(32, keyAB.size)
    }
}
