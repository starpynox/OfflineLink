package com.offlinelink.protocol

import com.google.gson.Gson
import java.util.UUID

/**
 * Common, versioned message envelope used across both Bluetooth Classic and Wi-Fi Direct transports.
 * Encapsulates routing metadata and an encrypted payload.
 */
data class MessageEnvelope(
    val version: Int = 1,
    val type: MessageType,
    val messageId: String = UUID.randomUUID().toString(),
    val senderId: String,
    val recipientId: String,
    val groupId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val expiryTimestamp: Long = System.currentTimeMillis() + DEFAULT_TTL_MS,
    val sequenceNumber: Long = 0L,
    val hopLimit: Int = DEFAULT_HOP_LIMIT,
    val hopCount: Int = 0,
    val priority: Int = PRIORITY_NORMAL,
    val payloadLength: Int = 0,
    val encryptedPayloadBase64: String = "",
    val authTag: String = "",
    val signature: String = ""
) {
    companion object {
        const val DEFAULT_HOP_LIMIT = 4
        const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L // 24 Hours
        const val BROADCAST_RECIPIENT = "*"

        const val PRIORITY_NORMAL = 0
        const val PRIORITY_HIGH = 1
        const val PRIORITY_SOS = 2

        private val gson = Gson()

        fun fromJson(json: String): MessageEnvelope? {
            return try {
                gson.fromJson(json, MessageEnvelope::class.java)?.takeIf { it.isValid() }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJson(): String = gson.toJson(this)

    fun toByteArray(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    fun isValid(): Boolean {
        if (version != 1) return false
        if (messageId.isBlank()) return false
        if (senderId.isBlank()) return false
        if (recipientId.isBlank()) return false
        if (hopLimit < 0 || hopCount < 0 || hopCount > hopLimit) return false
        if (payloadLength < 0 || payloadLength > FrameCodec.MAX_PAYLOAD_SIZE) return false
        return true
    }

    fun isExpired(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return expiryTimestamp in 1 until currentTimeMillis
    }

    fun canHop(): Boolean = hopCount < hopLimit

    fun withIncrementedHop(): MessageEnvelope {
        return copy(hopCount = hopCount + 1)
    }
}
