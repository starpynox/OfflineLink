package com.offlinelink.transport

import android.content.Context
import android.util.Log
import com.offlinelink.data.OfflineLinkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

interface HybridTransportListener {
    fun onSessionEstablished(session: TransportSession) {}
    fun onPeerConnected(peerId: String, transportType: TransportType)
    fun onPeerDisconnected(peerId: String, transportType: TransportType)
    fun onFrameReceived(session: TransportSession, frame: ByteArray)
}

class HybridTransportManager(
    private val context: Context,
    val bluetoothTransport: BluetoothRfcommTransport,
    val wifiDirectTransport: WifiDirectTransport,
    private val repository: OfflineLinkRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : CommonTransportListener {

    private val _policy = MutableStateFlow(TransportPolicy.AUTOMATIC)
    val policy: StateFlow<TransportPolicy> = _policy.asStateFlow()

    // Map peerId -> list of active sessions
    private val peerSessionMap = ConcurrentHashMap<String, MutableList<TransportSession>>()
    // Map sessionId -> TransportSession
    private val activeSessions = ConcurrentHashMap<String, TransportSession>()

    private val listeners = mutableListOf<HybridTransportListener>()

    init {
        bluetoothTransport.registerListener(this)
        wifiDirectTransport.registerListener(this)
    }

    fun setTransportPolicy(newPolicy: TransportPolicy) {
        _policy.value = newPolicy
        Log.i(TAG, "Transport policy updated to: $newPolicy")
    }

    fun registerListener(listener: HybridTransportListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun unregisterListener(listener: HybridTransportListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun associateSessionWithPeer(session: TransportSession, peerId: String) {
        session.peerId = peerId
        val list = peerSessionMap.getOrPut(peerId) { mutableListOf() }
        synchronized(list) {
            if (!list.contains(session)) {
                list.add(session)
            }
        }
        activeSessions[session.sessionId] = session

        scope.launch {
            repository.updatePeerConnection(peerId, true, session.transportType.name)
            repository.recordDiagnostic(
                eventType = "SESSION_ASSOCIATED",
                transportType = session.transportType.name,
                details = "Peer $peerId associated with session ${session.sessionId}",
                peerId = peerId
            )
        }

        synchronized(listeners) {
            listeners.forEach { it.onPeerConnected(peerId, session.transportType) }
        }
    }

    fun isPeerConnected(peerId: String): Boolean {
        val sessions = peerSessionMap[peerId] ?: return false
        synchronized(sessions) {
            return sessions.any { it.isConnected }
        }
    }

    fun getAllConnectedPeerIds(): List<String> {
        return peerSessionMap.filter { (_, sessions) ->
            synchronized(sessions) { sessions.any { it.isConnected } }
        }.keys.toList()
    }

    /**
     * Resolves the single best active session for the given peer, strictly avoiding duplicate transmission.
     */
    fun selectSessionForPeer(peerId: String, payloadSizeBytes: Int = 0): TransportSession? {
        val sessions = peerSessionMap[peerId] ?: return null
        val aliveSessions = synchronized(sessions) { sessions.filter { it.isConnected } }
        if (aliveSessions.isEmpty()) return null

        val btSession = aliveSessions.firstOrNull { it.transportType == TransportType.BLUETOOTH }
        val wifiSession = aliveSessions.firstOrNull { it.transportType == TransportType.WIFI_DIRECT }

        return when (_policy.value) {
            TransportPolicy.BLUETOOTH_PREFERRED -> btSession ?: wifiSession
            TransportPolicy.WIFI_DIRECT_PREFERRED -> wifiSession ?: btSession
            TransportPolicy.AUTOMATIC -> {
                // If payload is large (>64 KB), prefer Wi-Fi Direct for high bandwidth
                if (payloadSizeBytes > 64 * 1024 && wifiSession != null) {
                    wifiSession
                } else {
                    // Prefer whichever is established, defaulting to Bluetooth for light control if both exist
                    wifiSession ?: btSession
                }
            }
        }
    }

    fun sendFrameToPeer(peerId: String, frameBytes: ByteArray, payloadSizeBytes: Int = 0): Boolean {
        val session = selectSessionForPeer(peerId, payloadSizeBytes)
        if (session == null) {
            Log.w(TAG, "Cannot send frame: No active session for peer $peerId")
            return false
        }
        val success = session.sendFrame(frameBytes)
        if (success) {
            scope.launch {
                repository.recordDiagnostic(
                    eventType = "FRAME_TX",
                    transportType = session.transportType.name,
                    details = "Sent ${frameBytes.size} bytes to peer $peerId",
                    bytesTransferred = frameBytes.size.toLong(),
                    peerId = peerId
                )
            }
        }
        return success
    }

    /**
     * Broadcasts a frame to all unique connected peers.
     * Guaranteed to send at most once per logical peerId, preventing duplicates.
     */
    fun broadcastFrame(frameBytes: ByteArray, payloadSizeBytes: Int = 0): Int {
        var sentCount = 0
        val uniquePeerIds = getAllConnectedPeerIds()
        for (peerId in uniquePeerIds) {
            if (sendFrameToPeer(peerId, frameBytes, payloadSizeBytes)) {
                sentCount++
            }
        }

        // Also send to any active unassociated sessions (e.g. during handshake discovery)
        val unassociated = activeSessions.values.filter { it.peerId == null && it.isConnected }
        for (session in unassociated) {
            if (session.sendFrame(frameBytes)) {
                sentCount++
            }
        }
        return sentCount
    }

    fun startAllHosting() {
        bluetoothTransport.startHosting()
        wifiDirectTransport.startHosting()
    }

    fun stopAll() {
        bluetoothTransport.stopHosting()
        wifiDirectTransport.stopHosting()
        activeSessions.values.forEach { it.close() }
        activeSessions.clear()
        peerSessionMap.clear()
    }

    override fun onSessionEstablished(session: TransportSession) {
        activeSessions[session.sessionId] = session
        Log.i(TAG, "Transport session established: ${session.sessionId} via ${session.transportType}")
        synchronized(listeners) {
            listeners.forEach { it.onSessionEstablished(session) }
        }
    }

    override fun onSessionClosed(session: TransportSession, reason: String) {
        activeSessions.remove(session.sessionId)
        session.peerId?.let { peerId ->
            val list = peerSessionMap[peerId]
            if (list != null) {
                synchronized(list) { list.remove(session) }
                if (list.isEmpty()) {
                    peerSessionMap.remove(peerId)
                    scope.launch {
                        repository.updatePeerConnection(peerId, false, null)
                    }
                    synchronized(listeners) {
                        listeners.forEach { it.onPeerDisconnected(peerId, session.transportType) }
                    }
                }
            }
        }
        Log.i(TAG, "Transport session closed: ${session.sessionId} ($reason)")
    }

    override fun onFrameReceived(session: TransportSession, frame: ByteArray) {
        scope.launch {
            repository.recordDiagnostic(
                eventType = "FRAME_RX",
                transportType = session.transportType.name,
                details = "Received ${frame.size} bytes on session ${session.sessionId}",
                bytesTransferred = frame.size.toLong(),
                peerId = session.peerId
            )
        }
        synchronized(listeners) {
            listeners.forEach { it.onFrameReceived(session, frame) }
        }
    }

    override fun onError(transportType: TransportType, message: String) {
        Log.e(TAG, "Transport error [$transportType]: $message")
        scope.launch {
            repository.recordDiagnostic(
                eventType = "ERROR",
                transportType = transportType.name,
                details = message
            )
        }
    }

    override fun onDiagnostic(transportType: TransportType, eventType: String, message: String) {
        scope.launch {
            repository.recordDiagnostic(
                eventType = eventType,
                transportType = transportType.name,
                details = message
            )
        }
    }

    companion object {
        private const val TAG = "OfflineLink-Hybrid"
    }
}
