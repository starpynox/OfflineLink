package com.offlinelink.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val bluetoothAddress: String? = null,
    val wifiAddress: String? = null,
    val publicKeyHex: String = "",
    val fingerprint: String = "",
    val trustStatus: String = TRUST_UNVERIFIED,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val supportedTransports: String = "BLUETOOTH,WIFI_DIRECT",
    val isConnected: Boolean = false,
    val activeTransport: String? = null
) {
    companion object {
        const val TRUST_UNVERIFIED = "UNVERIFIED"
        const val TRUST_TRUSTED = "TRUSTED"
        const val TRUST_REVOKED = "REVOKED"
    }
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["timestamp"]),
        Index(value = ["deliveryStatus"])
    ]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val groupId: String? = null,
    val type: String, // "TEXT", "SOS", "GROUP_TEXT", "FILE"
    val content: String,
    val isEncrypted: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val expiryTimestamp: Long = System.currentTimeMillis() + 86400000L,
    val priority: Int = 0,
    val hopLimit: Int = 4,
    val hopCount: Int = 0,
    val direction: String = DIRECTION_OUTGOING,
    val deliveryStatus: String = STATUS_QUEUED,
    val ackTimestamp: Long? = null,
    val relayNodeId: String? = null
) {
    companion object {
        const val DIRECTION_OUTGOING = "OUTGOING"
        const val DIRECTION_INCOMING = "INCOMING"
        const val DIRECTION_RELAY = "RELAY"

        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_SENDING = "SENDING"
        const val STATUS_RELAYED = "RELAYED"
        const val STATUS_DELIVERED = "DELIVERED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_EXPIRED = "EXPIRED"
    }
}

@Entity(
    tableName = "message_queue",
    indices = [
        Index(value = ["status"]),
        Index(value = ["priority", "nextAttemptTimestamp"])
    ]
)
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val queueId: Long = 0,
    val messageId: String,
    val recipientId: String,
    val priority: Int = 0,
    val envelopeJson: String,
    val retryCount: Int = 0,
    val maxRetries: Int = 5,
    val lastAttemptTimestamp: Long = 0L,
    val nextAttemptTimestamp: Long = 0L,
    val status: String = QUEUE_STATUS_PENDING
) {
    companion object {
        const val QUEUE_STATUS_PENDING = "PENDING"
        const val QUEUE_STATUS_IN_FLIGHT = "IN_FLIGHT"
        const val QUEUE_STATUS_COMPLETED = "COMPLETED"
        const val QUEUE_STATUS_FAILED = "FAILED"
    }
}

@Entity(tableName = "seen_messages")
data class SeenMessageEntity(
    @PrimaryKey val messageId: String,
    val firstSeenTimestamp: Long = System.currentTimeMillis(),
    val sourcePeerId: String = "",
    val isDelivered: Boolean = false
)

@Entity(tableName = "file_transfers")
data class FileTransferEntity(
    @PrimaryKey val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val totalChunks: Int,
    val transferredChunks: Int = 0,
    val sha256: String,
    val senderId: String,
    val recipientId: String,
    val localFilePath: String? = null,
    val status: String = TRANSFER_IN_PROGRESS,
    val direction: String = DIRECTION_OUTGOING,
    val updatedTimestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val DIRECTION_OUTGOING = "OUTGOING"
        const val DIRECTION_INCOMING = "INCOMING"

        const val TRANSFER_IN_PROGRESS = "IN_PROGRESS"
        const val TRANSFER_COMPLETED = "COMPLETED"
        const val TRANSFER_FAILED = "FAILED"
        const val TRANSFER_CANCELLED = "CANCELLED"
    }
}

@Entity(tableName = "diagnostic_logs")
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String,
    val transportType: String,
    val peerId: String? = null,
    val details: String,
    val latencyMs: Long = 0L,
    val bytesTransferred: Long = 0L
)
