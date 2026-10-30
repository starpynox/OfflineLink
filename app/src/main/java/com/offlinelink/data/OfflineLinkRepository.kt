package com.offlinelink.data

import android.content.Context
import androidx.room.withTransaction
import com.offlinelink.protocol.MessageEnvelope
import kotlinx.coroutines.flow.Flow

class OfflineLinkRepository(private val database: OfflineLinkDatabase) {

    val allPeersFlow: Flow<List<PeerEntity>> = database.peerDao().getAllPeersFlow()
    val allMessagesFlow: Flow<List<MessageEntity>> = database.messageDao().getAllMessagesFlow()
    val queueItemsFlow: Flow<List<QueueItemEntity>> = database.queueDao().getAllQueueItemsFlow()
    val fileTransfersFlow: Flow<List<FileTransferEntity>> = database.fileTransferDao().getAllTransfersFlow()
    val recentDiagnosticsFlow: Flow<List<DiagnosticLogEntity>> = database.diagnosticDao().getRecentLogsFlow()
    val pendingCountFlow: Flow<Int> = database.messageDao().getPendingCountFlow()
    val recentConversationIdsFlow: Flow<List<String>> = database.messageDao().getRecentConversationIdsFlow()

    fun getConversationFlow(conversationId: String): Flow<List<MessageEntity>> {
        return database.messageDao().getMessagesForConversationFlow(conversationId)
    }

    suspend fun getPeer(deviceId: String): PeerEntity? = database.peerDao().getPeerById(deviceId)

    suspend fun upsertPeer(peer: PeerEntity) = database.peerDao().upsertPeer(peer)

    suspend fun updatePeerConnection(deviceId: String, connected: Boolean, transport: String?) {
        database.peerDao().updateConnectionStatus(deviceId, connected, transport)
    }

    suspend fun resetAllConnections() {
        database.peerDao().resetAllConnections()
    }

    suspend fun updatePeerTrust(deviceId: String, trustStatus: String) {
        database.peerDao().updateTrustStatus(deviceId, trustStatus)
    }

    suspend fun recordOutgoingMessage(message: MessageEntity, envelope: MessageEnvelope) {
        require(message.recipientId.isNotBlank()) { "Recipient ID cannot be blank" }
        require(message.conversationId.isNotBlank()) { "Conversation ID cannot be blank" }
        database.messageDao().upsertMessage(message)
        database.queueDao().enqueue(
            QueueItemEntity(
                messageId = message.messageId,
                recipientId = message.recipientId,
                priority = message.priority,
                envelopeJson = envelope.toJson(),
                nextAttemptTimestamp = System.currentTimeMillis()
            )
        )
        database.seenMessageDao().markSeen(
            SeenMessageEntity(
                messageId = message.messageId,
                firstSeenTimestamp = message.timestamp,
                sourcePeerId = message.senderId,
                isDelivered = false
            )
        )
    }

    suspend fun recordIncomingMessage(message: MessageEntity) {
        database.messageDao().upsertMessage(message)
        database.seenMessageDao().markSeen(
            SeenMessageEntity(
                messageId = message.messageId,
                firstSeenTimestamp = message.timestamp,
                sourcePeerId = message.senderId,
                isDelivered = true
            )
        )
    }

    suspend fun updateDeliveryStatus(messageId: String, status: String, ackTimestamp: Long? = null) {
        database.messageDao().updateDeliveryStatus(messageId, status, ackTimestamp)
        if (status == MessageEntity.STATUS_DELIVERED) {
            database.queueDao().deleteByMessageId(messageId)
            database.seenMessageDao().markDelivered(messageId)
        }
    }

    suspend fun isMessageSeen(messageId: String): Boolean {
        return database.seenMessageDao().isSeen(messageId)
    }

    suspend fun markMessageSeen(messageId: String, sourcePeerId: String) {
        database.seenMessageDao().markSeen(
            SeenMessageEntity(
                messageId = messageId,
                sourcePeerId = sourcePeerId,
                isDelivered = false
            )
        )
    }

    suspend fun enqueueRelayMessage(envelope: MessageEnvelope, sourcePeerId: String) {
        val message = MessageEntity(
            messageId = envelope.messageId,
            conversationId = envelope.recipientId,
            senderId = envelope.senderId,
            recipientId = envelope.recipientId,
            groupId = envelope.groupId,
            type = envelope.type.name,
            content = "[Relayed Encrypted Message - ${envelope.payloadLength} bytes]",
            isEncrypted = true,
            timestamp = envelope.timestamp,
            expiryTimestamp = envelope.expiryTimestamp,
            priority = envelope.priority,
            hopLimit = envelope.hopLimit,
            hopCount = envelope.hopCount,
            direction = MessageEntity.DIRECTION_RELAY,
            deliveryStatus = MessageEntity.STATUS_RELAYED,
            relayNodeId = sourcePeerId
        )
        database.messageDao().upsertMessage(message)
        database.queueDao().enqueue(
            QueueItemEntity(
                messageId = envelope.messageId,
                recipientId = envelope.recipientId,
                priority = envelope.priority,
                envelopeJson = envelope.toJson(),
                nextAttemptTimestamp = System.currentTimeMillis()
            )
        )
        markMessageSeen(envelope.messageId, sourcePeerId)
    }

    suspend fun getPendingQueueItems(): List<QueueItemEntity> {
        return database.queueDao().getPendingQueueItems()
    }

    suspend fun removeQueueItem(messageId: String) {
        database.queueDao().deleteByMessageId(messageId)
    }

    suspend fun updateQueueItem(item: QueueItemEntity) {
        database.queueDao().updateQueueItem(item)
    }

    suspend fun upsertFileTransfer(transfer: FileTransferEntity) {
        database.fileTransferDao().upsertTransfer(transfer)
    }

    suspend fun updateFileTransferProgress(fileId: String, transferred: Int, status: String) {
        database.fileTransferDao().updateProgress(fileId, transferred, status)
    }

    suspend fun getFileTransfer(fileId: String): FileTransferEntity? {
        return database.fileTransferDao().getTransferById(fileId)
    }

    suspend fun recordDiagnostic(
        eventType: String,
        transportType: String,
        details: String,
        latencyMs: Long = 0L,
        bytesTransferred: Long = 0L,
        peerId: String? = null
    ) {
        database.diagnosticDao().insertLog(
            DiagnosticLogEntity(
                eventType = eventType,
                transportType = transportType,
                details = details,
                latencyMs = latencyMs,
                bytesTransferred = bytesTransferred,
                peerId = peerId
            )
        )
    }

    suspend fun getAllDiagnostics(): List<DiagnosticLogEntity> = database.diagnosticDao().getAllLogs()

    suspend fun recoverQueueOnStartup() {
        database.peerDao().resetAllConnections()
        database.queueDao().recoverStalledItemsOnStartup()
    }

    suspend fun clearConversation(conversationId: String) {
        database.withTransaction {
            database.messageDao().deleteMessagesForConversation(conversationId)
            database.queueDao().deleteByRecipientId(conversationId)
        }
    }

    suspend fun clearAllChats() {
        database.withTransaction {
            database.messageDao().deleteAllChatMessages()
            database.queueDao().deleteAllChatQueueItems()
        }
    }

    suspend fun clearAllData() {
        database.messageDao().deleteAllMessages()
        database.diagnosticDao().clearLogs()
        database.peerDao().resetAllConnections()
    }

    companion object {
        @Volatile
        private var instance: OfflineLinkRepository? = null

        fun getInstance(context: Context): OfflineLinkRepository {
            return instance ?: synchronized(this) {
                val db = OfflineLinkDatabase.getInstance(context)
                instance ?: OfflineLinkRepository(db).also { instance = it }
            }
        }
    }
}
