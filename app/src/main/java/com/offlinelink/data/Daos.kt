package com.offlinelink.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY isConnected DESC, lastSeenTimestamp DESC")
    fun getAllPeersFlow(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE isConnected = 1")
    suspend fun getConnectedPeers(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getPeerById(deviceId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("UPDATE peers SET isConnected = :connected, activeTransport = :transport WHERE deviceId = :deviceId")
    suspend fun updateConnectionStatus(deviceId: String, connected: Boolean, transport: String?)

    @Query("UPDATE peers SET isConnected = 0, activeTransport = null")
    suspend fun resetAllConnections()

    @Query("UPDATE peers SET trustStatus = :trustStatus WHERE deviceId = :deviceId")
    suspend fun updateTrustStatus(deviceId: String, trustStatus: String)

    @Query("DELETE FROM peers WHERE deviceId = :deviceId")
    suspend fun deletePeer(deviceId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversationFlow(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessagesFlow(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("UPDATE messages SET deliveryStatus = :status, ackTimestamp = :ackTimestamp WHERE messageId = :messageId")
    suspend fun updateDeliveryStatus(messageId: String, status: String, ackTimestamp: Long? = null)

    @Query("SELECT COUNT(*) FROM messages WHERE deliveryStatus = 'QUEUED' OR deliveryStatus = 'SENDING'")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT DISTINCT conversationId FROM messages ORDER BY timestamp DESC")
    fun getRecentConversationIdsFlow(): Flow<List<String>>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String): Int

    @Query("DELETE FROM messages WHERE conversationId != 'EMERGENCY_SOS'")
    suspend fun deleteAllChatMessages(): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM message_queue WHERE status = 'PENDING' AND nextAttemptTimestamp <= :currentTime ORDER BY priority DESC, queueId ASC")
    suspend fun getPendingQueueItems(currentTime: Long = System.currentTimeMillis()): List<QueueItemEntity>

    @Query("SELECT * FROM message_queue ORDER BY priority DESC, queueId ASC")
    fun getAllQueueItemsFlow(): Flow<List<QueueItemEntity>>

    @Query("SELECT * FROM message_queue WHERE messageId = :messageId LIMIT 1")
    suspend fun getQueueItemByMessageId(messageId: String): QueueItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: QueueItemEntity): Long

    @Update
    suspend fun updateQueueItem(item: QueueItemEntity)

    @Query("UPDATE message_queue SET status = :status WHERE messageId = :messageId")
    suspend fun updateQueueStatus(messageId: String, status: String)

    @Query("DELETE FROM message_queue WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: String)

    @Query("DELETE FROM message_queue WHERE recipientId = :recipientId")
    suspend fun deleteByRecipientId(recipientId: String): Int

    @Query("DELETE FROM message_queue WHERE recipientId != 'BROADCAST'")
    suspend fun deleteAllChatQueueItems(): Int

    @Query("UPDATE message_queue SET status = 'PENDING' WHERE status = 'IN_FLIGHT'")
    suspend fun recoverStalledItemsOnStartup()

    @Query("DELETE FROM message_queue WHERE status = 'COMPLETED'")
    suspend fun pruneCompleted()
}

@Dao
interface SeenMessageDao {
    @Query("SELECT EXISTS(SELECT 1 FROM seen_messages WHERE messageId = :messageId)")
    suspend fun isSeen(messageId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(seen: SeenMessageEntity)

    @Query("UPDATE seen_messages SET isDelivered = 1 WHERE messageId = :messageId")
    suspend fun markDelivered(messageId: String)
}

@Dao
interface FileTransferDao {
    @Query("SELECT * FROM file_transfers ORDER BY updatedTimestamp DESC")
    fun getAllTransfersFlow(): Flow<List<FileTransferEntity>>

    @Query("SELECT * FROM file_transfers WHERE fileId = :fileId LIMIT 1")
    suspend fun getTransferById(fileId: String): FileTransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransfer(transfer: FileTransferEntity)

    @Query("UPDATE file_transfers SET transferredChunks = :transferred, status = :status, updatedTimestamp = :updatedTime WHERE fileId = :fileId")
    suspend fun updateProgress(fileId: String, transferred: Int, status: String, updatedTime: Long = System.currentTimeMillis())
}

@Dao
interface DiagnosticDao {
    @Insert
    suspend fun insertLog(log: DiagnosticLogEntity)

    @Query("SELECT * FROM diagnostic_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogsFlow(limit: Int = 100): Flow<List<DiagnosticLogEntity>>

    @Query("SELECT * FROM diagnostic_logs ORDER BY timestamp DESC")
    suspend fun getAllLogs(): List<DiagnosticLogEntity>

    @Query("DELETE FROM diagnostic_logs")
    suspend fun clearLogs()
}
