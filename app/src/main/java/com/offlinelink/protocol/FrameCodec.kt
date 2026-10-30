package com.offlinelink.protocol

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Binary length-prefixed framing codec for all OfflineLink transports (Bluetooth RFCOMM and Wi-Fi Direct TCP).
 *
 * Frame Format:
 * [4 bytes MAGIC ("OLNK")]
 * [2 bytes PROTOCOL_VERSION]
 * [4 bytes PAYLOAD_LENGTH (Big-Endian)]
 * [4 bytes CRC32_CHECKSUM (Big-Endian)]
 * [N bytes RAW_PAYLOAD]
 */
object FrameCodec {
    val MAGIC = byteArrayOf(0x4F, 0x4C, 0x4E, 0x4B) // "OLNK"
    const val CURRENT_PROTOCOL_VERSION: Short = 1
    const val HEADER_SIZE = 4 + 2 + 4 + 4 // 14 bytes
    const val MAX_PAYLOAD_SIZE = 2 * 1024 * 1024 // 2 MB safety limit

    /**
     * Encodes a payload into a framed byte array with magic, version, length, and CRC32.
     */
    fun encodeFrame(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_SIZE) {
            "Payload size ${payload.size} exceeds maximum allowable frame size $MAX_PAYLOAD_SIZE"
        }

        val crc = CRC32()
        crc.update(payload)
        val crcValue = crc.value.toInt()

        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(MAGIC)
            putShort(CURRENT_PROTOCOL_VERSION)
            putInt(payload.size)
            putInt(crcValue)
            put(payload)
        }
        return buffer.array()
    }

    /**
     * Stream frame reader that handles socket stream fragmentation, partial reads,
     * and multiple concatenated frames in the same input stream.
     */
    class StreamDecoder {
        private val buffer = ByteArrayOutputStream()

        /**
         * Appends incoming chunk and extracts all complete, validated frames.
         * Throws [IllegalArgumentException] if frame is malformed or corrupted.
         */
        @Synchronized
        fun processBytes(data: ByteArray, offset: Int = 0, length: Int = data.size): List<ByteArray> {
            buffer.write(data, offset, length)
            val frames = mutableListOf<ByteArray>()

            var currentBytes = buffer.toByteArray()
            var readIndex = 0

            while (currentBytes.size - readIndex >= HEADER_SIZE) {
                // Check magic bytes
                val magic0 = currentBytes[readIndex]
                val magic1 = currentBytes[readIndex + 1]
                val magic2 = currentBytes[readIndex + 2]
                val magic3 = currentBytes[readIndex + 3]

                if (magic0 != MAGIC[0] || magic1 != MAGIC[1] || magic2 != MAGIC[2] || magic3 != MAGIC[3]) {
                    // Scan forward to resynchronize on magic
                    readIndex++
                    continue
                }

                val headerBuffer = ByteBuffer.wrap(currentBytes, readIndex + 4, HEADER_SIZE - 4).order(ByteOrder.BIG_ENDIAN)
                val version = headerBuffer.short
                val payloadLength = headerBuffer.int
                val expectedCrc = headerBuffer.int

                if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_SIZE) {
                    // Malformed length header - advance past magic and seek next valid frame
                    readIndex += 4
                    continue
                }

                val totalFrameSize = HEADER_SIZE + payloadLength
                if (currentBytes.size - readIndex < totalFrameSize) {
                    // Incomplete frame, wait for more data from stream
                    break
                }

                // Complete frame available - verify CRC32
                val payload = currentBytes.copyOfRange(readIndex + HEADER_SIZE, readIndex + totalFrameSize)
                val crc = CRC32()
                crc.update(payload)
                if (crc.value.toInt() != expectedCrc) {
                    // Corrupted frame, skip header and search for next frame
                    readIndex += 4
                    continue
                }

                // Valid frame extracted
                frames.add(payload)
                readIndex += totalFrameSize
            }

            // Compact remaining unprocessed bytes in the buffer
            buffer.reset()
            if (readIndex < currentBytes.size) {
                buffer.write(currentBytes, readIndex, currentBytes.size - readIndex)
            }

            return frames
        }

        @Synchronized
        fun clear() {
            buffer.reset()
        }
    }
}
