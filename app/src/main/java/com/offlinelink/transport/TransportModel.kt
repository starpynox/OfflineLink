package com.offlinelink.transport

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.UUID

enum class TransportType {
    BLUETOOTH,
    WIFI_DIRECT
}

enum class TransportPolicy {
    AUTOMATIC,
    BLUETOOTH_PREFERRED,
    WIFI_DIRECT_PREFERRED
}

enum class WifiDirectState {
    IDLE,
    DISCOVERING,
    CONNECTION_REQUESTED,
    CONNECTING,
    GROUP_FORMED,
    DATA_CHANNEL_CONNECTING,
    CONNECTED,
    FAILED,
    DISCONNECTED
}

/**
 * Encapsulates an active physical connection session over either RFCOMM or TCP.
 */
interface TransportSession {
    val sessionId: String
    var peerId: String?
    val peerAddress: String
    val transportType: TransportType
    val isConnected: Boolean
    val establishedTimestamp: Long

    fun sendFrame(frameBytes: ByteArray): Boolean
    fun close()
}

/**
 * Socket-backed implementation of [TransportSession].
 */
class SocketTransportSession(
    override val sessionId: String = UUID.randomUUID().toString(),
    override var peerId: String? = null,
    override val peerAddress: String,
    override val transportType: TransportType,
    private val socket: Any, // BluetoothSocket or java.net.Socket
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val onCloseCallback: ((SocketTransportSession) -> Unit)? = null
) : TransportSession {

    override val establishedTimestamp: Long = System.currentTimeMillis()

    @Volatile
    private var closed = false

    override val isConnected: Boolean
        get() = !closed

    @Synchronized
    override fun sendFrame(frameBytes: ByteArray): Boolean {
        if (closed) return false
        return try {
            outputStream.write(frameBytes)
            outputStream.flush()
            true
        } catch (e: Exception) {
            close()
            false
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            try {
                inputStream.close()
            } catch (_: Exception) {}
            try {
                outputStream.close()
            } catch (_: Exception) {}
            try {
                when (socket) {
                    is Socket -> socket.close()
                    is android.bluetooth.BluetoothSocket -> socket.close()
                }
            } catch (_: Exception) {}
            onCloseCallback?.invoke(this)
        }
    }
}

interface CommonTransportListener {
    fun onSessionEstablished(session: TransportSession)
    fun onSessionClosed(session: TransportSession, reason: String)
    fun onFrameReceived(session: TransportSession, frame: ByteArray)
    fun onError(transportType: TransportType, message: String)
    fun onDiagnostic(transportType: TransportType, eventType: String, message: String) {}
}

interface CommonTransport {
    val transportType: TransportType
    fun startHosting()
    fun stopHosting()
    fun connectToPeer(address: String)
    fun disconnect(peerAddress: String)
    fun getActiveSessions(): List<TransportSession>
    fun registerListener(listener: CommonTransportListener)
    fun unregisterListener(listener: CommonTransportListener)
}
