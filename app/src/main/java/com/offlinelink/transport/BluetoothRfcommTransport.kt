package com.offlinelink.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.offlinelink.protocol.FrameCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BluetoothRfcommTransport(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : CommonTransport {

    override val transportType: TransportType = TransportType.BLUETOOTH

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private val listeners = mutableListOf<CommonTransportListener>()
    private val activeSessions = ConcurrentHashMap<String, SocketTransportSession>()
    private val connectingAddresses = ConcurrentHashMap.newKeySet<String>()

    private val _isServerListening = MutableStateFlow(false)
    val isServerListening: StateFlow<Boolean> = _isServerListening.asStateFlow()

    private val _connectingPeerAddress = MutableStateFlow<String?>(null)
    val connectingPeerAddress: StateFlow<String?> = _connectingPeerAddress.asStateFlow()

    private var serverJob: Job? = null
    private var insecureServerSocket: BluetoothServerSocket? = null
    private var secureServerSocket: BluetoothServerSocket? = null

    @Volatile
    private var isHosting = false

    override fun registerListener(listener: CommonTransportListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun unregisterListener(listener: CommonTransportListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return try {
            if (isBluetoothEnabled()) {
                bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    private val _serverStatusText = MutableStateFlow("Server: Stopped")
    val serverStatusText: StateFlow<String> = _serverStatusText.asStateFlow()

    @SuppressLint("MissingPermission")
    override fun startHosting() {
        if (!isBluetoothEnabled()) {
            Log.w(TAG, "Cannot start Bluetooth hosting: Bluetooth is disabled")
            _isServerListening.value = false
            _serverStatusText.value = "Bluetooth disabled"
            return
        }

        // If the server is already active, listening, and healthy, do not cancel and restart it
        if (isHosting && _isServerListening.value && serverJob?.isActive == true) {
            Log.d(TAG, "Bluetooth RFCOMM server is already actively listening. Retaining listener.")
            return
        }

        stopHosting()
        isHosting = true

        serverJob = scope.launch {
            try {
                // Primary: Dedicated OfflineLink application UUID (Insecure RFCOMM for maximal Android-to-Android compatibility)
                try {
                    insecureServerSocket = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(
                        SERVICE_NAME_INSECURE,
                        CUSTOM_APP_UUID
                    )
                    Log.i(TAG, "Bluetooth RFCOMM server listening on Custom UUID: $CUSTOM_APP_UUID")
                } catch (e: Exception) {
                    Log.e(TAG, "Primary custom UUID server socket failed: ${e.message}")
                }

                // Secondary: Standard SPP UUID in separate try-catch (graceful fallback if OS allows, non-fatal if reserved)
                try {
                    secureServerSocket = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(
                        SERVICE_NAME_SPP,
                        STANDARD_SPP_UUID
                    )
                    Log.i(TAG, "Secondary Bluetooth RFCOMM server listening on SPP: $STANDARD_SPP_UUID")
                } catch (e: Exception) {
                    Log.w(TAG, "SPP UUID reserved by OS or unavailable: ${e.message}")
                    secureServerSocket = null
                }

                val hasActiveListener = insecureServerSocket != null || secureServerSocket != null
                _isServerListening.value = hasActiveListener
                _serverStatusText.value = if (hasActiveListener) "Server: LISTENING (OfflineLink RFCOMM Ready)" else "Server: FAILED to bind"

                if (!hasActiveListener) {
                    notifyError("Bluetooth server failed to bind any listening socket")
                    return@launch
                }

                // Launch coroutine to accept custom UUID sockets
                val customAcceptJob = launch {
                    while (isActive && isHosting) {
                        val socket = try {
                            insecureServerSocket?.accept()
                        } catch (e: IOException) {
                            if (!isHosting) break
                            Log.w(TAG, "Insecure server accept error: ${e.message}")
                            null
                        }

                        if (socket != null) {
                            val address = socket.remoteDevice?.address ?: "BT-UNKNOWN"
                            Log.i(TAG, "Accepted incoming Bluetooth connection (custom UUID) from: $address")
                            manageConnectedSocket(socket, address)
                        }
                    }
                }

                // Launch coroutine to accept SPP sockets if bound
                val sppAcceptJob = launch {
                    while (isActive && isHosting) {
                        val socket = try {
                            secureServerSocket?.accept()
                        } catch (e: IOException) {
                            if (!isHosting) break
                            null
                        }

                        if (socket != null) {
                            val address = socket.remoteDevice?.address ?: "BT-UNKNOWN"
                            Log.i(TAG, "Accepted incoming Bluetooth connection (SPP) from: $address")
                            manageConnectedSocket(socket, address)
                        }
                    }
                }

                customAcceptJob.join()
                sppAcceptJob.join()
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Bluetooth RFCOMM server coroutine cancelled cleanly (normal lifecycle)")
                throw e
            } catch (e: SecurityException) {
                Log.e(TAG, "Bluetooth hosting security exception (missing permission?): ${e.message}")
                _isServerListening.value = false
                _serverStatusText.value = "Permission required: ${e.message}"
                isHosting = false
                notifyError("Bluetooth server permission required: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth RFCOMM hosting error: ${e.message}")
                _isServerListening.value = false
                _serverStatusText.value = "Error: ${e.message}"
                isHosting = false
                notifyError("Bluetooth server failed: ${e.message}")
            }
        }
    }

    override fun stopHosting() {
        isHosting = false
        _isServerListening.value = false
        _serverStatusText.value = "Server: Stopped"
        serverJob?.cancel()
        serverJob = null

        try {
            insecureServerSocket?.close()
        } catch (_: Exception) {}
        insecureServerSocket = null

        try {
            secureServerSocket?.close()
        } catch (_: Exception) {}
        secureServerSocket = null
    }

    @SuppressLint("MissingPermission")
    override fun connectToPeer(address: String) {
        // Prevent duplicate concurrent connection attempts to the same address
        if (connectingAddresses.contains(address)) {
            Log.w(TAG, "Connection attempt to $address already in progress. Ignoring duplicate.")
            return
        }

        val existingSession = activeSessions[address]
        if (existingSession != null && existingSession.isConnected) {
            Log.i(TAG, "Already connected to Bluetooth peer $address")
            return
        } else if (existingSession != null) {
            Log.d(TAG, "Cleaning up stale inactive session for $address prior to reconnect")
            existingSession.close()
            activeSessions.remove(address)
        }

        if (!isBluetoothEnabled()) {
            notifyError("Bluetooth is disabled. Please enable Bluetooth.")
            return
        }

        connectingAddresses.add(address)
        _connectingPeerAddress.value = address

        scope.launch {
            try {
                val device = bluetoothAdapter?.getRemoteDevice(address)
                if (device == null) {
                    notifyError("Device not found for address: $address")
                    return@launch
                }

                // Step 1: Cancel active inquiry discovery to avoid RFCOMM radio collision
                try {
                    bluetoothAdapter?.cancelDiscovery()
                } catch (_: Exception) {}
                delay(400) // Allow baseband 400ms to complete inquiry cancel and settle

                Log.d(TAG, "Attempting Bluetooth connection to $address (${device.name ?: "Unknown"})")

                var connectedSocket: BluetoothSocket? = null
                val tierErrors = mutableListOf<String>()

                // Tier 1: Insecure RFCOMM on Dedicated OfflineLink Custom UUID
                var tier1Socket: BluetoothSocket? = null
                try {
                    Log.d(TAG, "Tier 1: Connecting via Insecure Custom UUID ($CUSTOM_APP_UUID)...")
                    tier1Socket = device.createInsecureRfcommSocketToServiceRecord(CUSTOM_APP_UUID)
                    tier1Socket.connect()
                    connectedSocket = tier1Socket
                    Log.i(TAG, "Tier 1 connection SUCCEEDED!")
                } catch (e: Exception) {
                    try { tier1Socket?.close() } catch (_: Exception) {}
                    val msg = "${e.javaClass.simpleName}: ${e.message}"
                    tierErrors.add("Tier 1 (Insecure Custom): $msg")
                    Log.w(TAG, "Tier 1 failed: $msg. Trying Tier 2...")
                    delay(150)
                }

                // Tier 2: Secure RFCOMM on Dedicated OfflineLink Custom UUID
                if (connectedSocket == null) {
                    var tier2Socket: BluetoothSocket? = null
                    try {
                        Log.d(TAG, "Tier 2: Connecting via Secure Custom UUID ($CUSTOM_APP_UUID)...")
                        tier2Socket = device.createRfcommSocketToServiceRecord(CUSTOM_APP_UUID)
                        tier2Socket.connect()
                        connectedSocket = tier2Socket
                        Log.i(TAG, "Tier 2 connection SUCCEEDED!")
                    } catch (e: Exception) {
                        try { tier2Socket?.close() } catch (_: Exception) {}
                        val msg = "${e.javaClass.simpleName}: ${e.message}"
                        tierErrors.add("Tier 2 (Secure Custom): $msg")
                        Log.w(TAG, "Tier 2 failed: $msg. Trying Tier 3...")
                        delay(150)
                    }
                }

                // Tier 3: Insecure RFCOMM on standard SPP UUID
                if (connectedSocket == null) {
                    var tier3Socket: BluetoothSocket? = null
                    try {
                        Log.d(TAG, "Tier 3: Connecting via Insecure SPP UUID ($STANDARD_SPP_UUID)...")
                        tier3Socket = device.createInsecureRfcommSocketToServiceRecord(STANDARD_SPP_UUID)
                        tier3Socket.connect()
                        connectedSocket = tier3Socket
                        Log.i(TAG, "Tier 3 connection SUCCEEDED!")
                    } catch (e: Exception) {
                        try { tier3Socket?.close() } catch (_: Exception) {}
                        val msg = "${e.javaClass.simpleName}: ${e.message}"
                        tierErrors.add("Tier 3 (Insecure SPP): $msg")
                        Log.w(TAG, "Tier 3 failed: $msg. Trying Tier 4...")
                        delay(150)
                    }
                }

                // Tier 4: Reflection channel 1 fallback (bypasses SDP service discovery)
                if (connectedSocket == null) {
                    var tier4Socket: BluetoothSocket? = null
                    try {
                        Log.d(TAG, "Tier 4: Connecting via reflection RFCOMM Channel 1...")
                        val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                        tier4Socket = m.invoke(device, 1) as? BluetoothSocket
                        tier4Socket?.connect()
                        connectedSocket = tier4Socket
                        Log.i(TAG, "Tier 4 connection SUCCEEDED!")
                    } catch (e: Exception) {
                        try { tier4Socket?.close() } catch (_: Exception) {}
                        val msg = "${e.javaClass.simpleName}: ${e.message}"
                        tierErrors.add("Tier 4 (Channel 1 reflection): $msg")
                        Log.w(TAG, "Tier 4 failed: $msg")
                    }
                }

                if (connectedSocket != null) {
                    Log.i(TAG, "Bluetooth RFCOMM connected to peer $address")
                    manageConnectedSocket(connectedSocket, address)
                } else {
                    val fullErr = tierErrors.joinToString(" | ")
                    Log.e(TAG, "All Bluetooth connection attempts to $address failed: $fullErr")
                    notifyError("Bluetooth connect to $address failed. Ensure remote phone has OfflineLink open with Server LISTENING. Details: $fullErr")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error connecting to Bluetooth peer $address: ${e.message}")
                notifyError("Connection to $address failed: ${e.message}")
            } finally {
                connectingAddresses.remove(address)
                _connectingPeerAddress.value = null
            }
        }
    }

    override fun disconnect(peerAddress: String) {
        activeSessions[peerAddress]?.close()
        activeSessions.remove(peerAddress)
    }

    override fun getActiveSessions(): List<TransportSession> = activeSessions.values.toList()

    private fun manageConnectedSocket(socket: BluetoothSocket, address: String) {
        scope.launch {
            try {
                val inputStream = socket.inputStream
                val outputStream = socket.outputStream

                val session = SocketTransportSession(
                    peerAddress = address,
                    transportType = TransportType.BLUETOOTH,
                    socket = socket,
                    inputStream = inputStream,
                    outputStream = outputStream,
                    onCloseCallback = { closedSession ->
                        activeSessions.remove(address)
                        notifySessionClosed(closedSession, "Socket closed")
                    }
                )

                activeSessions[address] = session
                notifySessionEstablished(session)

                val decoder = FrameCodec.StreamDecoder()
                val buffer = ByteArray(4096)

                while (isActive && session.isConnected) {
                    val bytesRead = try {
                        withContext(Dispatchers.IO) {
                            inputStream.read(buffer)
                        }
                    } catch (e: Exception) {
                        -1
                    }

                    if (bytesRead <= 0) {
                        Log.d(TAG, "Bluetooth peer $address disconnected (EOF)")
                        break
                    }

                    val frames = decoder.processBytes(buffer, 0, bytesRead)
                    for (frame in frames) {
                        notifyFrameReceived(session, frame)
                    }
                }

                session.close()
            } catch (e: Exception) {
                Log.e(TAG, "Session read error for $address: ${e.message}")
            } finally {
                activeSessions.remove(address)
            }
        }
    }

    private fun notifySessionEstablished(session: TransportSession) {
        synchronized(listeners) { listeners.forEach { it.onSessionEstablished(session) } }
    }

    private fun notifySessionClosed(session: TransportSession, reason: String) {
        synchronized(listeners) { listeners.forEach { it.onSessionClosed(session, reason) } }
    }

    private fun notifyFrameReceived(session: TransportSession, frame: ByteArray) {
        synchronized(listeners) { listeners.forEach { it.onFrameReceived(session, frame) } }
    }

    private fun notifyError(message: String) {
        synchronized(listeners) { listeners.forEach { it.onError(TransportType.BLUETOOTH, message) } }
    }

    companion object {
        private const val TAG = "OfflineLink-BT"
        const val SERVICE_NAME_CUSTOM = "OfflineLink-RFCOMM-Custom"
        const val SERVICE_NAME_SPP = "OfflineLink-SPP-Fallback"
        const val SERVICE_NAME_INSECURE = "OfflineLink-SPP-Insecure"
        const val SERVICE_NAME_SECURE = "OfflineLink-SPP-Secure"

        // Universal Bluetooth Serial Port Profile (SPP) UUID supported by all Android devices & basebands
        val STANDARD_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        // Custom application-dedicated UUID
        val CUSTOM_APP_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
    }
}
