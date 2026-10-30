package com.offlinelink.protocol

import org.junit.Assert.*
import org.junit.Test

class FrameCodecTest {

    @Test
    fun testEncodeAndDecodeSingleFrame() {
        val payload = "Hello OfflineLink Framing!".toByteArray(Charsets.UTF_8)
        val encoded = FrameCodec.encodeFrame(payload)

        val decoder = FrameCodec.StreamDecoder()
        val decodedFrames = decoder.processBytes(encoded)

        assertEquals(1, decodedFrames.size)
        assertArrayEquals(payload, decodedFrames[0])
    }

    @Test
    fun testFragmentedStreamReads() {
        val payload = "A message fragmented over multiple socket reads".toByteArray(Charsets.UTF_8)
        val encoded = FrameCodec.encodeFrame(payload)

        val decoder = FrameCodec.StreamDecoder()

        // Split encoded bytes into 3 small chunks
        val split1 = encoded.copyOfRange(0, 5)
        val split2 = encoded.copyOfRange(5, 18)
        val split3 = encoded.copyOfRange(18, encoded.size)

        val frames1 = decoder.processBytes(split1)
        assertEquals(0, frames1.size) // Incomplete frame

        val frames2 = decoder.processBytes(split2)
        assertEquals(0, frames2.size) // Still incomplete

        val frames3 = decoder.processBytes(split3)
        assertEquals(1, frames3.size) // Complete!
        assertArrayEquals(payload, frames3[0])
    }

    @Test
    fun testMultipleConcatenatedFramesInSingleBuffer() {
        val payload1 = "Frame 1".toByteArray(Charsets.UTF_8)
        val payload2 = "Frame 2 - Longer content".toByteArray(Charsets.UTF_8)
        val payload3 = "Frame 3".toByteArray(Charsets.UTF_8)

        val combined = FrameCodec.encodeFrame(payload1) +
                FrameCodec.encodeFrame(payload2) +
                FrameCodec.encodeFrame(payload3)

        val decoder = FrameCodec.StreamDecoder()
        val frames = decoder.processBytes(combined)

        assertEquals(3, frames.size)
        assertArrayEquals(payload1, frames[0])
        assertArrayEquals(payload2, frames[1])
        assertArrayEquals(payload3, frames[2])
    }

    @Test
    fun testCorruptedCrcRejected() {
        val payload = "Sensitive payload".toByteArray(Charsets.UTF_8)
        val encoded = FrameCodec.encodeFrame(payload)

        // Corrupt one payload byte
        encoded[encoded.size - 1] = (encoded[encoded.size - 1] + 1).toByte()

        val decoder = FrameCodec.StreamDecoder()
        val frames = decoder.processBytes(encoded)

        assertEquals(0, frames.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testPayloadExceedingMaxSafetyLimitThrows() {
        val oversize = ByteArray(FrameCodec.MAX_PAYLOAD_SIZE + 1)
        FrameCodec.encodeFrame(oversize)
    }
}
