package com.offlinelink.features

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.offlinelink.data.FileTransferEntity
import com.offlinelink.data.OfflineLinkRepository
import com.offlinelink.protocol.FileAckPayload
import com.offlinelink.protocol.FileChunkPayload
import com.offlinelink.protocol.FileInitPayload
import com.offlinelink.protocol.FrameCodec
import com.offlinelink.protocol.JsonUtil
import com.offlinelink.protocol.MessageEnvelope
import com.offlinelink.protocol.MessageType
import com.offlinelink.security.CryptoEngine
import com.offlinelink.security.DeviceIdentityManager
import com.offlinelink.transport.HybridTransportListener
import com.offlinelink.transport.HybridTransportManager
import com.offlinelink.transport.TransportSession
import com.offlinelink.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FileTransferManager(
    private val context: Context,
    private val transportManager: HybridTransportManager,
    private val repository: OfflineLinkRepository,
    private val identityManager: DeviceIdentityManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : HybridTransportListener {

    private val myDeviceId = identityManager.getIdentity().deviceId
    private val activeReceivingFiles = ConcurrentHashMap<String, FileOutputStream>()
    private val activeInitPayloads = ConcurrentHashMap<String, FileInitPayload>()

    init {
        transportManager.registerListener(this)
    }

    /**
     * Sends a file from a Uri (or local file) to the target recipient.
     */
    fun sendFile(
        recipientId: String,
        uri: Uri,
        fileName: String,
        fileSize: Long,
        onProgress: ((Int, Int) -> Unit)? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val cleanRecipient = recipientId.trim()
        if (cleanRecipient.isBlank()) {
            Log.w(TAG, "Cannot send file: blank recipientId")
            onComplete?.invoke(false)
            return
        }

        scope.launch {
            val fileId = UUID.randomUUID().toString()
            val chunkSizeBytes = DEFAULT_CHUNK_SIZE
            val totalChunks = ((fileSize + chunkSizeBytes - 1) / chunkSizeBytes).toInt().coerceAtLeast(1)

            // Calculate SHA-256 of file stream
            val sha256Hex = computeFileSha256(uri)

            val transfer = FileTransferEntity(
                fileId = fileId,
                fileName = fileName,
                fileSize = fileSize,
                totalChunks = totalChunks,
                transferredChunks = 0,
                sha256 = sha256Hex,
                senderId = myDeviceId,
                recipientId = recipientId,
                status = FileTransferEntity.TRANSFER_IN_PROGRESS,
                direction = FileTransferEntity.DIRECTION_OUTGOING
            )
            repository.upsertFileTransfer(transfer)

            // 1. Send FILE_INIT
            val initPayload = FileInitPayload(
                fileId = fileId,
                fileName = fileName,
                fileSizeBytes = fileSize,
                totalChunks = totalChunks,
                chunkSizeBytes = chunkSizeBytes,
                sha256Hash = sha256Hex
            )
            val initEnvelope = MessageEnvelope(
                type = MessageType.FILE_INIT,
                senderId = myDeviceId,
                recipientId = recipientId,
                payloadLength = JsonUtil.toJson(initPayload).length,
                encryptedPayloadBase64 = Base64.encodeToString(JsonUtil.toJson(initPayload).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            )
            transportManager.sendFrameToPeer(recipientId, FrameCodec.encodeFrame(initEnvelope.toByteArray()), fileSize.toInt())

            // 2. Stream Chunks
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val buffer = ByteArray(chunkSizeBytes)
                    var chunkIndex = 0
                    var bytesRead: Int

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        val slice = if (bytesRead == chunkSizeBytes) buffer else buffer.copyOfRange(0, bytesRead)
                        val chunkPayload = FileChunkPayload(
                            fileId = fileId,
                            chunkIndex = chunkIndex,
                            totalChunks = totalChunks,
                            dataBase64 = Base64.encodeToString(slice, Base64.NO_WRAP)
                        )
                        val chunkEnvelope = MessageEnvelope(
                            type = MessageType.FILE_CHUNK,
                            senderId = myDeviceId,
                            recipientId = recipientId,
                            encryptedPayloadBase64 = Base64.encodeToString(JsonUtil.toJson(chunkPayload).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        )
                        val frameBytes = FrameCodec.encodeFrame(chunkEnvelope.toByteArray())
                        val sent = transportManager.sendFrameToPeer(recipientId, frameBytes, bytesRead)

                        if (!sent) {
                            Log.w(TAG, "Chunk $chunkIndex write failed. Aborting transfer.")
                            repository.updateFileTransferProgress(fileId, chunkIndex, FileTransferEntity.TRANSFER_FAILED)
                            onComplete?.invoke(false)
                            return@launch
                        }

                        chunkIndex++
                        repository.updateFileTransferProgress(fileId, chunkIndex, FileTransferEntity.TRANSFER_IN_PROGRESS)
                        onProgress?.invoke(chunkIndex, totalChunks)
                    }

                    repository.updateFileTransferProgress(fileId, totalChunks, FileTransferEntity.TRANSFER_COMPLETED)
                    onComplete?.invoke(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming file $fileName: ${e.message}")
                repository.updateFileTransferProgress(fileId, 0, FileTransferEntity.TRANSFER_FAILED)
                onComplete?.invoke(false)
            }
        }
    }

    private suspend fun computeFileSha256(uri: Uri): String = withContext(Dispatchers.IO) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buf = ByteArray(8192)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02X".format(it) }
    }

    override fun onFrameReceived(session: TransportSession, frame: ByteArray) {
        val jsonString = String(frame, Charsets.UTF_8)
        val envelope = MessageEnvelope.fromJson(jsonString) ?: return

        when (envelope.type) {
            MessageType.FILE_INIT -> handleFileInit(session, envelope)
            MessageType.FILE_CHUNK -> handleFileChunk(session, envelope)
            MessageType.FILE_ACK -> handleFileAck(envelope)
            else -> {}
        }
    }

    private fun handleFileInit(session: TransportSession, envelope: MessageEnvelope) {
        scope.launch {
            val raw = Base64.decode(envelope.encryptedPayloadBase64, Base64.NO_WRAP)
            val init = JsonUtil.fromJson<FileInitPayload>(String(raw, Charsets.UTF_8)) ?: return@launch

            val downloadDir = File(context.filesDir, "transfers").apply { mkdirs() }
            val outputFile = File(downloadDir, "${init.fileId}_${init.fileName}")
            val fos = FileOutputStream(outputFile)

            activeReceivingFiles[init.fileId] = fos
            activeInitPayloads[init.fileId] = init

            val entity = FileTransferEntity(
                fileId = init.fileId,
                fileName = init.fileName,
                fileSize = init.fileSizeBytes,
                totalChunks = init.totalChunks,
                transferredChunks = 0,
                sha256 = init.sha256Hash,
                senderId = envelope.senderId,
                recipientId = myDeviceId,
                localFilePath = outputFile.absolutePath,
                status = FileTransferEntity.TRANSFER_IN_PROGRESS,
                direction = FileTransferEntity.DIRECTION_INCOMING
            )
            repository.upsertFileTransfer(entity)
            Log.i(TAG, "Started receiving file ${init.fileName} (${init.fileSizeBytes} bytes, ${init.totalChunks} chunks)")
        }
    }

    private fun handleFileChunk(session: TransportSession, envelope: MessageEnvelope) {
        scope.launch {
            val raw = Base64.decode(envelope.encryptedPayloadBase64, Base64.NO_WRAP)
            val chunk = JsonUtil.fromJson<FileChunkPayload>(String(raw, Charsets.UTF_8)) ?: return@launch

            val fos = activeReceivingFiles[chunk.fileId] ?: return@launch
            val chunkBytes = Base64.decode(chunk.dataBase64, Base64.NO_WRAP)
            fos.write(chunkBytes)
            fos.flush()

            val transferred = chunk.chunkIndex + 1
            val isLast = transferred >= chunk.totalChunks

            if (isLast) {
                fos.close()
                activeReceivingFiles.remove(chunk.fileId)
                val init = activeInitPayloads.remove(chunk.fileId)

                // Verify SHA-256
                val transferEntity = repository.getFileTransfer(chunk.fileId)
                val targetFile = transferEntity?.localFilePath?.let { File(it) }
                val computedHash = targetFile?.let { computeLocalSha256(it) } ?: ""

                val verified = computedHash.equals(init?.sha256Hash, ignoreCase = true)
                val finalStatus = if (verified) FileTransferEntity.TRANSFER_COMPLETED else FileTransferEntity.TRANSFER_FAILED

                repository.updateFileTransferProgress(chunk.fileId, transferred, finalStatus)
                Log.i(TAG, "Completed file reception for ${chunk.fileId}. Hash valid: $verified ($computedHash vs ${init?.sha256Hash})")

                // Send FILE_ACK
                val ack = FileAckPayload(fileId = chunk.fileId, chunkIndex = chunk.chunkIndex, isComplete = true, isCorrupted = !verified)
                val ackEnvelope = MessageEnvelope(
                    type = MessageType.FILE_ACK,
                    senderId = myDeviceId,
                    recipientId = envelope.senderId,
                    encryptedPayloadBase64 = Base64.encodeToString(JsonUtil.toJson(ack).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                )
                session.sendFrame(FrameCodec.encodeFrame(ackEnvelope.toByteArray()))
            } else {
                repository.updateFileTransferProgress(chunk.fileId, transferred, FileTransferEntity.TRANSFER_IN_PROGRESS)
            }
        }
    }

    private fun computeLocalSha256(file: File): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    private fun handleFileAck(envelope: MessageEnvelope) {
        val raw = Base64.decode(envelope.encryptedPayloadBase64, Base64.NO_WRAP)
        val ack = JsonUtil.fromJson<FileAckPayload>(String(raw, Charsets.UTF_8)) ?: return
        Log.i(TAG, "File ACK received for ${ack.fileId}: isComplete=${ack.isComplete}, isCorrupted=${ack.isCorrupted}")
    }

    override fun onPeerConnected(peerId: String, transportType: TransportType) {}
    override fun onPeerDisconnected(peerId: String, transportType: TransportType) {}

    companion object {
        private const val TAG = "OfflineLink-File"
        const val DEFAULT_CHUNK_SIZE = 16 * 1024 // 16 KB chunks for high reliability
    }
}
