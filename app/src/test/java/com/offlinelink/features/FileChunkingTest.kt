package com.offlinelink.features

import com.offlinelink.security.CryptoEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class FileChunkingTest {

    @Test
    fun testFileChunkingAndSha256Reassembly() {
        val originalData = ByteArray(64 * 1024) { (it % 256).toByte() }
        val originalSha256 = CryptoEngine.computeSha256Hex(originalData)

        val chunkSize = 16 * 1024
        val totalChunks = (originalData.size + chunkSize - 1) / chunkSize
        assertEquals(4, totalChunks)

        // Split into chunks
        val chunks = mutableListOf<ByteArray>()
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = (start + chunkSize).coerceAtMost(originalData.size)
            chunks.add(originalData.copyOfRange(start, end))
        }

        // Reassemble in receiver
        val outputStream = ByteArrayOutputStream()
        for (chunk in chunks) {
            outputStream.write(chunk)
        }
        val reassembledData = outputStream.toByteArray()

        assertEquals(originalData.size, reassembledData.size)
        assertArrayEquals(originalData, reassembledData)

        val reassembledSha256 = CryptoEngine.computeSha256Hex(reassembledData)
        assertEquals(originalSha256, reassembledSha256)
    }

    @Test
    fun testCorruptedChunkCausesSha256Mismatch() {
        val originalData = "Important offline emergency map vector data".toByteArray(Charsets.UTF_8)
        val originalSha256 = CryptoEngine.computeSha256Hex(originalData)

        val corruptedData = originalData.copyOf()
        corruptedData[5] = (corruptedData[5] + 1).toByte()
        val corruptedSha256 = CryptoEngine.computeSha256Hex(corruptedData)

        assertNotEquals("Corrupted file must yield distinct SHA-256 hash", originalSha256, corruptedSha256)
    }
}
