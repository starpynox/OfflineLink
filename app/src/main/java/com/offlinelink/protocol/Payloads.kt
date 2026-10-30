package com.offlinelink.protocol

import com.google.gson.Gson

object JsonUtil {
    val gson = Gson()
    inline fun <reified T> fromJson(json: String): T? = try {
        gson.fromJson(json, T::class.java)
    } catch (e: Exception) {
        null
    }
    fun toJson(any: Any): String = gson.toJson(any)
}

data class PingPayload(
    val clientTimestamp: Long = System.currentTimeMillis()
)

data class PongPayload(
    val clientTimestamp: Long,
    val serverTimestamp: Long = System.currentTimeMillis()
)

data class PeerHelloPayload(
    val deviceId: String,
    val displayName: String,
    val publicKeyHex: String,
    val listenPort: Int = 8988,
    val supportedTransports: List<String> = listOf("BLUETOOTH", "WIFI_DIRECT"),
    val protocolVersion: Int = 1
)

data class PeerVerifyPayload(
    val sasCode: String,
    val fingerprint: String
)

data class TextPayload(
    val text: String,
    val conversationId: String
)

data class AckPayload(
    val originalMessageId: String,
    val recipientId: String,
    val deliveredTimestamp: Long = System.currentTimeMillis(),
    val status: String = STATUS_DELIVERED // "DELIVERED" or "RELAY_ACCEPTED"
) {
    companion object {
        const val STATUS_DELIVERED = "DELIVERED"
        const val STATUS_RELAY_ACCEPTED = "RELAY_ACCEPTED"
    }
}

data class SosPayload(
    val category: String, // "MEDICAL", "TRAPPED", "ASSISTANCE", "SAFETY_CHECKIN", "CUSTOM"
    val note: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isCancelled: Boolean = false
) {
    companion object {
        const val CATEGORY_MEDICAL = "MEDICAL"
        const val CATEGORY_TRAPPED = "TRAPPED"
        const val CATEGORY_ASSISTANCE = "ASSISTANCE"
        const val CATEGORY_SAFETY_CHECKIN = "SAFETY_CHECKIN"
        const val CATEGORY_CUSTOM = "CUSTOM"
    }
}

data class GroupTextPayload(
    val groupId: String,
    val groupName: String,
    val text: String
)

data class FileInitPayload(
    val fileId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val totalChunks: Int,
    val chunkSizeBytes: Int,
    val sha256Hash: String,
    val mimeType: String = "application/octet-stream"
)

data class FileChunkPayload(
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val dataBase64: String
)

data class FileAckPayload(
    val fileId: String,
    val chunkIndex: Int,
    val isComplete: Boolean = false,
    val isCorrupted: Boolean = false
)

data class RoutingAdvertPayload(
    val heldMessageIds: List<String>,
    val targetRecipients: List<String>,
    val priorities: List<Int>,
    val expiries: List<Long>
)

data class RoutingFetchPayload(
    val requestedMessageIds: List<String>
)
