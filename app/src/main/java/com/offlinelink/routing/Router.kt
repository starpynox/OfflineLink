package com.offlinelink.routing

import android.content.Context
import android.util.Base64
import android.util.Log
import com.offlinelink.data.MessageEntity
import com.offlinelink.data.OfflineLinkRepository
import com.offlinelink.data.PeerEntity
import com.offlinelink.protocol.AckPayload
import com.offlinelink.protocol.FrameCodec
import com.offlinelink.protocol.JsonUtil
import com.offlinelink.protocol.MessageEnvelope
import com.offlinelink.protocol.MessageType
import com.offlinelink.protocol.PeerHelloPayload
import com.offlinelink.protocol.PingPayload
import com.offlinelink.protocol.PongPayload
import com.offlinelink.protocol.RoutingAdvertPayload
import com.offlinelink.protocol.RoutingFetchPayload
import com.offlinelink.protocol.SosPayload
import com.offlinelink.protocol.TextPayload
import com.offlinelink.security.CryptoEngine
import com.offlinelink.security.DeviceIdentityManager
import com.offlinelink.security.EncryptedResult
import com.offlinelink.transport.HybridTransportListener
import com.offlinelink.transport.HybridTransportManager
import com.offlinelink.transport.TransportSession
import com.offlinelink.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class Router(
    private val context: Context,
    val transportManager: HybridTransportManager,
    val repository: OfflineLinkRepository,
    val identityManager: DeviceIdentityManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : HybridTransportListener {

    private val myIdentity = identityManager.getIdentity()

    private val _lastPingLatencyMs = MutableStateFlow<Long?>(null)
    val lastPingLatencyMs: StateFlow<Long?> = _lastPingLatencyMs.asStateFlow()

    init {
        transportManager.registerListener(this)
    }

    /**
     * Sends an encrypted direct text message.
     * Enqueues persistently in Room, and if the recipient is connected, sends directly.
     */
    fun sendTextMessage(recipientId: String, text: String, onEnqueued: ((String) -> Unit)? = null) {
        val cleanRecipient = recipientId.trim()
        val cleanText = text.trim()
        if (cleanRecipient.isBlank() || cleanText.isBlank()) {
            Log.w(TAG, "Cannot send text message: recipientId or text is blank (recipient='$recipientId')")
            return
        }

        scope.launch {
            val messageId = UUID.randomUUID().toString()
            val pairwiseKey = CryptoEngine.derivePairwiseKey(myIdentity.deviceId, cleanRecipient)
            val encryptedResult = CryptoEngine.encryptAesGcm(pairwiseKey, cleanText.toByteArray(Charsets.UTF_8))

            val envelope = MessageEnvelope(
                type = MessageType.TEXT,
                messageId = messageId,
                senderId = myIdentity.deviceId,
                recipientId = cleanRecipient,
                priority = MessageEnvelope.PRIORITY_NORMAL,
                payloadLength = cleanText.length,
                encryptedPayloadBase64 = encryptedResult.toBase64()
            )

            val entity = MessageEntity(
                messageId = messageId,
                conversationId = cleanRecipient,
                senderId = myIdentity.deviceId,
                recipientId = cleanRecipient,
                type = MessageType.TEXT.name,
                content = cleanText,
                isEncrypted = true,
                timestamp = envelope.timestamp,
                expiryTimestamp = envelope.expiryTimestamp,
                priority = envelope.priority,
                hopLimit = envelope.hopLimit,
                hopCount = 0,
                direction = MessageEntity.DIRECTION_OUTGOING,
                deliveryStatus = MessageEntity.STATUS_QUEUED
            )

            repository.recordOutgoingMessage(entity, envelope)
            onEnqueued?.invoke(messageId)

            // Try immediate direct transmission if connected
            attemptDirectDelivery(envelope)
        }
    }

    /**
     * Sends an Emergency SOS alert broadcast.
     */
    fun sendSosAlert(
        category: String,
        note: String,
        latitude: Double? = null,
        longitude: Double? = null,
        accuracy: Float? = null,
        isCancelled: Boolean = false,
        onEnqueued: ((String) -> Unit)? = null
    ) {
        scope.launch {
            val messageId = UUID.randomUUID().toString()
            val sosPayload = SosPayload(
                category = category,
                note = note,
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracy,
                timestamp = System.currentTimeMillis(),
                isCancelled = isCancelled
            )
            val jsonPayload = JsonUtil.toJson(sosPayload)
            val payloadBase64 = Base64.encodeToString(jsonPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val envelope = MessageEnvelope(
                type = MessageType.SOS,
                messageId = messageId,
                senderId = myIdentity.deviceId,
                recipientId = MessageEnvelope.BROADCAST_RECIPIENT,
                priority = MessageEnvelope.PRIORITY_SOS,
                hopLimit = 5,
                payloadLength = jsonPayload.length,
                encryptedPayloadBase64 = payloadBase64
            )

            val entity = MessageEntity(
                messageId = messageId,
                conversationId = "EMERGENCY_SOS",
                senderId = myIdentity.deviceId,
                recipientId = MessageEnvelope.BROADCAST_RECIPIENT,
                type = MessageType.SOS.name,
                content = if (isCancelled) "SOS CANCELLED: $note" else "EMERGENCY [$category]: $note",
                isEncrypted = false,
                timestamp = envelope.timestamp,
                expiryTimestamp = envelope.expiryTimestamp,
                priority = envelope.priority,
                hopLimit = envelope.hopLimit,
                hopCount = 0,
                direction = MessageEntity.DIRECTION_OUTGOING,
                deliveryStatus = MessageEntity.STATUS_QUEUED
            )

            repository.recordOutgoingMessage(entity, envelope)
            onEnqueued?.invoke(messageId)

            val frameBytes = FrameCodec.encodeFrame(envelope.toByteArray())
            transportManager.broadcastFrame(frameBytes, envelope.payloadLength)
        }
    }

    /**
     * Sends a ping frame to measure round-trip latency.
     */
    fun sendPing(peerId: String) {
        scope.launch {
            val ping = PingPayload(clientTimestamp = System.currentTimeMillis())
            val envelope = MessageEnvelope(
                type = MessageType.PING,
                senderId = myIdentity.deviceId,
                recipientId = peerId,
                encryptedPayloadBase64 = Base64.encodeToString(JsonUtil.toJson(ping).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            )
            val frameBytes = FrameCodec.encodeFrame(envelope.toByteArray())
            transportManager.sendFrameToPeer(peerId, frameBytes)
        }
    }

    /**
     * Attempts direct transmission to the target peer if reachable.
     */
    private suspend fun attemptDirectDelivery(envelope: MessageEnvelope): Boolean {
        if (transportManager.isPeerConnected(envelope.recipientId)) {
            val frameBytes = FrameCodec.encodeFrame(envelope.toByteArray())
            val sent = transportManager.sendFrameToPeer(envelope.recipientId, frameBytes, envelope.payloadLength)
            if (sent) {
                repository.updateDeliveryStatus(envelope.messageId, MessageEntity.STATUS_SENDING)
                return true
            }
        }
        return false
    }

    // --- HybridTransportListener callbacks ---

    override fun onSessionEstablished(session: TransportSession) {
        scope.launch {
            Log.i(TAG, "Transport session established: ${session.sessionId} via ${session.transportType}. Sending initial PEER_HELLO...")
            sendPeerHelloDirect(session)
        }
    }

    override fun onPeerConnected(peerId: String, transportType: TransportType) {
        scope.launch {
            Log.i(TAG, "Peer connected: $peerId via $transportType. Initiating opportunistic sync.")
            // Advertise pending messages held in local store-and-forward queue
            sendRoutingAdvertisement(peerId)
        }
    }

    override fun onPeerDisconnected(peerId: String, transportType: TransportType) {
        Log.i(TAG, "Peer disconnected: $peerId via $transportType")
    }

    override fun onFrameReceived(session: TransportSession, frame: ByteArray) {
        scope.launch {
            val jsonString = String(frame, Charsets.UTF_8)
            val envelope = MessageEnvelope.fromJson(jsonString)
            if (envelope == null || !envelope.isValid()) {
                Log.w(TAG, "Rejected malformed or invalid frame")
                return@launch
            }

            if (envelope.isExpired()) {
                Log.w(TAG, "Dropped expired message: ${envelope.messageId}")
                return@launch
            }

            handleEnvelope(session, envelope)
        }
    }

    private suspend fun handleEnvelope(session: TransportSession, envelope: MessageEnvelope) {
        when (envelope.type) {
            MessageType.PEER_HELLO -> handlePeerHello(session, envelope)
            MessageType.PING -> handlePing(session, envelope)
            MessageType.PONG -> handlePong(envelope)
            MessageType.ACK -> handleAck(envelope)
            MessageType.ROUTING_ADVERT -> handleRoutingAdvert(session, envelope)
            MessageType.ROUTING_FETCH -> handleRoutingFetch(session, envelope)
            MessageType.TEXT, MessageType.SOS, MessageType.GROUP_TEXT -> handleApplicationMessage(session, envelope)
            else -> {
                Log.d(TAG, "Handled protocol message type: ${envelope.type}")
            }
        }
    }

    private suspend fun handlePeerHello(session: TransportSession, envelope: MessageEnvelope) {
        val helloBytes = Base64.decode(envelope.encryptedPayloadBase64, Base64.NO_WRAP)
        val hello = JsonUtil.fromJson<PeerHelloPayload>(String(helloBytes, Charsets.UTF_8)) ?: return

        Log.i(TAG, "Received PEER_HELLO from ${hello.displayName} (${hello.deviceId}) via ${session.transportType}")
        transportManager.associateSessionWithPeer(session, hello.deviceId)

        val rawHash = CryptoEngine.computeSha256Hex(hello.publicKeyHex.toByteArray(Charsets.UTF_8))
        val fingerprint = CryptoEngine.formatFingerprint(rawHash)

        val peer = PeerEntity(
            deviceId = hello.deviceId,
            displayName = hello.displayName,
            publicKeyHex = hello.publicKeyHex,
            fingerprint = fingerprint,
            supportedTransports = hello.supportedTransports.joinToString(","),
            isConnected = true,
            activeTransport = session.transportType.name,
            bluetoothAddress = if (session.transportType == TransportType.BLUETOOTH) session.peerAddress else null,
            wifiAddress = if (session.transportType == TransportType.WIFI_DIRECT) session.peerAddress else null
        )
        repository.upsertPeer(peer)

        // Ensure reciprocal greeting is received by remote peer
        if (session.peerId != myIdentity.deviceId) {
            sendPeerHelloDirect(session)
        }

        // Advertise pending messages held in local store-and-forward queue
        sendRoutingAdvertisement(hello.deviceId)
    }

    private fun sendPeerHelloDirect(session: TransportSession) {
        val hello = PeerHelloPayload(
            deviceId = myIdentity.deviceId,
            displayName = myIdentity.deviceName,
            publicKeyHex = myIdentity.publicKeyHex,
            supportedTransports = listOf("BLUETOOTH", "WIFI_DIRECT")
        )
        val json = JsonUtil.toJson(hello)
        val envelope = MessageEnvelope(
            type = MessageType.PEER_HELLO,
            senderId = myIdentity.deviceId,
            recipientId = MessageEnvelope.BROADCAST_RECIPIENT,
            encryptedPayloadBase64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        )
        val frameBytes = FrameCodec.encodeFrame(envelope.toByteArray())
        val sent = session.sendFrame(frameBytes)
        Log.i(TAG, "Sent direct PEER_HELLO on session ${session.sessionId} via ${session.transportType}: success=$sent")
    }

    private suspend fun sendPeerHello(peerId: String) {
        val hello = PeerHelloPayload(
            deviceId = myIdentity.deviceId,
            displayName = myIdentity.deviceName,
            publicKeyHex = myIdentity.publicKeyHex,
            supportedTransports = listOf("BLUETOOTH", "WIFI_DIRECT")
        )
        val json = JsonUtil.toJson(hello)
        val envelope = MessageEnvelope(
            type = MessageType.PEER_HELLO,
            senderId = myIdentity.deviceId,
            recipientId = peerId,
            encryptedPayloadBase64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        )
        val frameBytes = FrameCodec.encodeFrame(envelope.toByteArray())
        transportManager.sendFrameToPeer(peerId, frameBytes)
    }

    private suspend fun handlePing(session: TransportSession, envelope: MessageEnvelope) {
        val rawBytes = Base64.decode(envelope.encryptedPayloadBase64, Base64.NO_WRAP)
        val ping = JsonUtil.fromJson<PingPayload>(String(rawBytes, Charsets.UTF_8)) ?: return

        val pong = PongPayload(clientTimestamp = ping.clientTimestamp)
        val pongJson = JsonUtil.toJson(pong)
        val replyEnvelope = MessageEnvelope(
            type = MessageType.PONG,
            senderId = myIdentity.deviceId,
            recipientId = envelope.senderId,
            encryptedPayloadBase64 = Base64.encodeToString(pongJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        )
        session.sendFrame(FrameCodec.encodeFrame(replyEnvelope.toByteArray()))
    }

    private suspend fun handlePong(envelope: MessageEnvelope) {
        val rawBytes = Base64.decode(envelope.encryptedPayloadBase64, Base64.NO_WRAP)
        val pong = JsonUtil.fromJson<PongPayload>(String(rawBytes, Charsets.UTF_8)) ?: return

        val rtt = System.currentTimeMillis() - pong.clientTimestamp
        _lastPingLatencyMs.value = rtt
        repository.recordDiagnostic(
            eventType = "PING_PONG",
            transportType = "HYBRID",
            details = "Ping RTT latency: ${rtt}ms",
            latencyMs = rtt,
            peerId = envelope.senderId
        )
        Log.i(TAG, "Measured round-trip latency to ${envelope.senderId}: ${rtt}ms")
    }

    private suspend fun handleAck(envelope: MessageEnvelope) {
        val rawBytes = Base64.decode(envelope.encryptedPayloadBase64, Base64.NO_WRAP)
        val ack = JsonUtil.fromJson<AckPayload>(String(rawBytes, Charsets.UTF_8)) ?: return

        Log.i(TAG, "Received protocol ACK for message ${ack.originalMessageId} with status ${ack.status}")
        repository.updateDeliveryStatus(ack.originalMessageId, MessageEntity.STATUS_DELIVERED, ack.deliveredTimestamp)
        repository.recordDiagnostic(
            eventType = "ACK_RECEIVED",
            transportType = "HYBRID",
            details = "ACK received for ${ack.originalMessageId}",
            peerId = envelope.senderId
        )
    }

    // --- Store-and-Forward Routing Mechanisms ---

    private suspend fun sendRoutingAdvertisement(peerId: String) {
        val pendingItems = repository.getPendingQueueItems()
        if (pendingItems.isEmpty()) return

        val advert = RoutingAdvertPayload(
            heldMessageIds = pendingItems.map { it.messageId },
            targetRecipients = pendingItems.map { it.recipientId },
            priorities = pendingItems.map { it.priority },
            expiries = pendingItems.map { System.currentTimeMillis() + 86400000L }
        )
        val json = JsonUtil.toJson(advert)
        val envelope = MessageEnvelope(
            type = MessageType.ROUTING_ADVERT,
            senderId = myIdentity.deviceId,
            recipientId = peerId,
            encryptedPayloadBase64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        )
        val frameBytes = FrameCodec.encodeFrame(envelope.toByteArray())
        transportManager.sendFrameToPeer(peerId, frameBytes)
    }

    private suspend fun handleRoutingAdvert(session: TransportSession, envelope: MessageEnvelope) {
        val rawBytes = Base64.decode(envelope.encryptedPayloadBase64, Base64.NO_WRAP)
        val advert = JsonUtil.fromJson<RoutingAdvertPayload>(String(rawBytes, Charsets.UTF_8)) ?: return

        val fetchList = mutableListOf<String>()
        for (i in advert.heldMessageIds.indices) {
            val messageId = advert.heldMessageIds[i]
            val recipientId = advert.targetRecipients[i]

            // 1. Has message already been processed/delivered?
            if (repository.isMessageSeen(messageId)) continue

            // 2. Is it addressed to me, broadcast, or eligible for store-and-forward relay?
            if (recipientId == myIdentity.deviceId || recipientId == MessageEnvelope.BROADCAST_RECIPIENT || true) {
                fetchList.add(messageId)
            }
        }

        if (fetchList.isNotEmpty()) {
            val fetch = RoutingFetchPayload(requestedMessageIds = fetchList)
            val json = JsonUtil.toJson(fetch)
            val fetchEnvelope = MessageEnvelope(
                type = MessageType.ROUTING_FETCH,
                senderId = myIdentity.deviceId,
                recipientId = envelope.senderId,
                encryptedPayloadBase64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            )
            session.sendFrame(FrameCodec.encodeFrame(fetchEnvelope.toByteArray()))
        }
    }

    private suspend fun handleRoutingFetch(session: TransportSession, envelope: MessageEnvelope) {
        val rawBytes = Base64.decode(envelope.encryptedPayloadBase64, Base64.NO_WRAP)
        val fetch = JsonUtil.fromJson<RoutingFetchPayload>(String(rawBytes, Charsets.UTF_8)) ?: return

        val pendingItems = repository.getPendingQueueItems()
        for (msgId in fetch.requestedMessageIds) {
            val item = pendingItems.firstOrNull { it.messageId == msgId } ?: continue
            val msgEnvelope = MessageEnvelope.fromJson(item.envelopeJson) ?: continue

            if (msgEnvelope.canHop()) {
                val forwardEnvelope = msgEnvelope.withIncrementedHop()
                val frameBytes = FrameCodec.encodeFrame(forwardEnvelope.toByteArray())
                session.sendFrame(frameBytes)

                repository.recordDiagnostic(
                    eventType = "ROUTING_RELAY_FORWARD",
                    transportType = session.transportType.name,
                    details = "Forwarded message $msgId to relay ${envelope.senderId} (hop ${forwardEnvelope.hopCount}/${forwardEnvelope.hopLimit})",
                    peerId = envelope.senderId
                )
            }
        }
    }

    private suspend fun handleApplicationMessage(session: TransportSession, envelope: MessageEnvelope) {
        // Persistent duplicate suppression check
        if (repository.isMessageSeen(envelope.messageId)) {
            Log.d(TAG, "Suppressed duplicate message: ${envelope.messageId}")
            return
        }

        // Mark seen to prevent duplicate processing or forward loops
        repository.markMessageSeen(envelope.messageId, envelope.senderId)

        val isForMe = envelope.recipientId == myIdentity.deviceId || envelope.recipientId == MessageEnvelope.BROADCAST_RECIPIENT

        if (isForMe) {
            // Final destination reached!
            processLocally(envelope)
            sendAck(envelope)
        } else {
            // Intermediate relay node! (Encrypted payload is NOT decrypted by relay)
            if (envelope.canHop()) {
                Log.i(TAG, "Storing and forwarding message ${envelope.messageId} destined for ${envelope.recipientId}")
                repository.enqueueRelayMessage(envelope, envelope.senderId)
                repository.recordDiagnostic(
                    eventType = "RELAY_STORE",
                    transportType = session.transportType.name,
                    details = "Stored message ${envelope.messageId} for forwarding to ${envelope.recipientId} (hop ${envelope.hopCount})",
                    peerId = envelope.recipientId
                )

                // If destination is already connected to this relay, deliver immediately!
                if (transportManager.isPeerConnected(envelope.recipientId)) {
                    val forwardEnvelope = envelope.withIncrementedHop()
                    val frameBytes = FrameCodec.encodeFrame(forwardEnvelope.toByteArray())
                    transportManager.sendFrameToPeer(envelope.recipientId, frameBytes, forwardEnvelope.payloadLength)
                }
            }
        }
    }

    private suspend fun processLocally(envelope: MessageEnvelope) {
        when (envelope.type) {
            MessageType.TEXT -> {
                val pairwiseKey = CryptoEngine.derivePairwiseKey(myIdentity.deviceId, envelope.senderId)
                val encResult = EncryptedResult.fromBase64(envelope.encryptedPayloadBase64)
                val plainBytes = encResult?.let { CryptoEngine.decryptAesGcm(pairwiseKey, it.iv, it.ciphertext) }

                val text = if (plainBytes != null) {
                    String(plainBytes, Charsets.UTF_8)
                } else {
                    "[Encrypted message - Decryption failed or unverified sender]"
                }

                val entity = MessageEntity(
                    messageId = envelope.messageId,
                    conversationId = envelope.senderId,
                    senderId = envelope.senderId,
                    recipientId = envelope.recipientId,
                    type = MessageType.TEXT.name,
                    content = text,
                    isEncrypted = plainBytes != null,
                    timestamp = envelope.timestamp,
                    expiryTimestamp = envelope.expiryTimestamp,
                    priority = envelope.priority,
                    hopLimit = envelope.hopLimit,
                    hopCount = envelope.hopCount,
                    direction = MessageEntity.DIRECTION_INCOMING,
                    deliveryStatus = MessageEntity.STATUS_DELIVERED
                )
                repository.recordIncomingMessage(entity)
            }
            MessageType.SOS -> {
                val rawBytes = Base64.decode(envelope.encryptedPayloadBase64, Base64.NO_WRAP)
                val sos = JsonUtil.fromJson<SosPayload>(String(rawBytes, Charsets.UTF_8))
                val content = if (sos != null) {
                    val locStr = if (sos.latitude != null && sos.longitude != null) {
                        " (Loc: %.4f, %.4f)".format(sos.latitude, sos.longitude)
                    } else ""
                    if (sos.isCancelled) "SOS CANCELLED: ${sos.note}" else "EMERGENCY [${sos.category}]$locStr: ${sos.note}"
                } else "EMERGENCY ALERT RECEIVED"

                val entity = MessageEntity(
                    messageId = envelope.messageId,
                    conversationId = "EMERGENCY_SOS",
                    senderId = envelope.senderId,
                    recipientId = envelope.recipientId,
                    type = MessageType.SOS.name,
                    content = content,
                    isEncrypted = false,
                    timestamp = envelope.timestamp,
                    expiryTimestamp = envelope.expiryTimestamp,
                    priority = envelope.priority,
                    hopLimit = envelope.hopLimit,
                    hopCount = envelope.hopCount,
                    direction = MessageEntity.DIRECTION_INCOMING,
                    deliveryStatus = MessageEntity.STATUS_DELIVERED
                )
                repository.recordIncomingMessage(entity)
            }
            else -> {}
        }
    }

    private suspend fun sendAck(originalEnvelope: MessageEnvelope) {
        val ack = AckPayload(
            originalMessageId = originalEnvelope.messageId,
            recipientId = myIdentity.deviceId,
            deliveredTimestamp = System.currentTimeMillis(),
            status = AckPayload.STATUS_DELIVERED
        )
        val json = JsonUtil.toJson(ack)
        val ackEnvelope = MessageEnvelope(
            type = MessageType.ACK,
            senderId = myIdentity.deviceId,
            recipientId = originalEnvelope.senderId,
            encryptedPayloadBase64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        )
        val frameBytes = FrameCodec.encodeFrame(ackEnvelope.toByteArray())
        transportManager.sendFrameToPeer(originalEnvelope.senderId, frameBytes)
    }

    companion object {
        private const val TAG = "OfflineLink-Router"
    }
}
