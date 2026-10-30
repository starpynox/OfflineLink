package com.offlinelink.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class WifiDirectTransport(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : CommonTransport, WifiP2pManager.ChannelListener {

    override val transportType: TransportType = TransportType.WIFI_DIRECT

    private val wifiP2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var channel: WifiP2pManager.Channel? = null

    private val listeners = mutableListOf<CommonTransportListener>()
    private val activeSessions = ConcurrentHashMap<String, SocketTransportSession>()

    private val _discoveredDevices = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val discoveredDevicesFlow: StateFlow<List<WifiP2pDevice>> = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _connectingWifiAddress = MutableStateFlow<String?>(null)
    val connectingWifiAddress: StateFlow<String?> = _connectingWifiAddress.asStateFlow()

    private val _connectionStatus = MutableStateFlow<String>("Idle")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var clientConnectJob: Job? = null
    private var connectJob: Job? = null

    @Volatile
    private var isHosting = false
    @Volatile
    private var isP2pEnabled = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    isP2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "Wi-Fi Direct state changed: enabled=$isP2pEnabled")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    val ch = ensureChannel() ?: return
                    wifiP2pManager?.requestPeers(ch) { peers ->
                        val list = peers.deviceList.toList()
                        _discoveredDevices.value = list
                        Log.d(TAG, "Wi-Fi Direct peers updated: ${list.size} devices found")
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    val p2pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    }

                    val group = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    }

                    Log.d(TAG, "P2P connection state broadcast received: isConnected=${networkInfo?.isConnected}, groupFormed=${p2pInfo?.groupFormed}, group=${group?.networkName}")

                    if (networkInfo?.isConnected == true || p2pInfo?.groupFormed == true) {
                        _isDiscovering.value = false
                        _connectionStatus.value = "P2P Group Formed! Establishing TCP sockets..."
                        val ch = ensureChannel() ?: return
                        wifiP2pManager?.requestConnectionInfo(ch) { info ->
                            handleConnectionInfo(info)
                        }
                    } else {
                        // Crucial fix: Do not overwrite status or reset connecting if a connection attempt is actively in progress
                        if (!_isConnecting.value) {
                            _connectionStatus.value = "Disconnected"
                            Log.d(TAG, "Wi-Fi Direct disconnected or group dissolved")
                            activeSessions.values.forEach { it.close() }
                            activeSessions.clear()
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    Log.d(TAG, "This device P2P info: ${device?.deviceName} (${device?.deviceAddress}, status=${device?.status})")
                }
            }
        }
    }

    init {
        initializeChannel()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        try {
            context.registerReceiver(receiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register P2P receiver: ${e.message}")
        }
    }

    private fun initializeChannel(): WifiP2pManager.Channel? {
        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), this)
        return channel
    }

    fun ensureChannel(): WifiP2pManager.Channel? {
        return channel ?: initializeChannel()
    }

    override fun onChannelDisconnected() {
        Log.w(TAG, "Wi-Fi Direct channel disconnected. Re-initializing channel...")
        initializeChannel()
    }

    override fun registerListener(listener: CommonTransportListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun unregisterListener(listener: CommonTransportListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun getDiscoveredDevices(): List<WifiP2pDevice> = _discoveredDevices.value

    @SuppressLint("MissingPermission")
    fun discoverPeers(onSuccess: (() -> Unit)? = null, onFailure: ((String) -> Unit)? = null) {
        val ch = ensureChannel()
        if (ch == null) {
            val err = "Wi-Fi Direct channel unavailable"
            onFailure?.invoke(err)
            notifyError(err)
            return
        }

        _isDiscovering.value = true
        _connectionStatus.value = "Scanning for nearby Wi-Fi Direct devices..."

        wifiP2pManager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct discoverPeers started successfully")
                _isDiscovering.value = true
                onSuccess?.invoke()
            }

            override fun onFailure(reason: Int) {
                val reasonText = mapFailureReason(reason)
                val err = "discoverPeers failed: $reasonText (code $reason)"
                Log.w(TAG, err)
                _isDiscovering.value = false
                _connectionStatus.value = "Scan failed: $reasonText"
                onFailure?.invoke(err)
                notifyError(err)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun stopPeerDiscovery(onComplete: (() -> Unit)? = null) {
        val ch = ensureChannel()
        if (ch == null) {
            _isDiscovering.value = false
            onComplete?.invoke()
            return
        }

        wifiP2pManager?.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct stopPeerDiscovery succeeded")
                _isDiscovering.value = false
                _connectionStatus.value = "Scan stopped"
                onComplete?.invoke()
            }

            override fun onFailure(reason: Int) {
                Log.d(TAG, "stopPeerDiscovery failed (code $reason)")
                _isDiscovering.value = false
                onComplete?.invoke()
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun cancelConnect(onComplete: (() -> Unit)? = null) {
        connectJob?.cancel()
        connectJob = null
        _isConnecting.value = false
        _connectingWifiAddress.value = null
        val ch = ensureChannel()
        if (ch != null) {
            wifiP2pManager?.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct cancelConnect succeeded")
                    _connectionStatus.value = "Connection cancelled"
                    onComplete?.invoke()
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "cancelConnect failed (code $reason)")
                    _connectionStatus.value = "Idle"
                    onComplete?.invoke()
                }
            })
        } else {
            _connectionStatus.value = "Idle"
            onComplete?.invoke()
        }
    }

    override fun startHosting() {
        isHosting = true
        ensureChannel()
    }

    override fun stopHosting() {
        isHosting = false
        connectJob?.cancel()
        connectJob = null
        _isConnecting.value = false
        _connectingWifiAddress.value = null
        _isDiscovering.value = false
        _connectionStatus.value = "Idle"
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        val ch = ensureChannel()
        if (ch != null) {
            wifiP2pManager?.removeGroup(ch, null)
        }
    }

    /**
     * Finds the local IPv4 address assigned to the Wi-Fi Direct P2P interface in the 192.168.49.0/24 subnet.
     * Returns null if DHCP lease assignment has not yet completed on the client.
     */
    private fun findLocalP2pAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        if (host.startsWith("192.168.49.")) {
                            return addr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error looking up local P2P network interface: ${e.message}")
        }
        return null
    }

    /**
     * Binds a socket to Android's P2P network via ConnectivityManager so packets are never
     * misrouted to the active 5G/LTE Cellular network.
     */
    private fun bindSocketToP2pNetwork(socket: Socket) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        try {
            cm.allNetworks.forEach { network ->
                val linkProps = cm.getLinkProperties(network)
                val isP2p = linkProps?.interfaceName?.contains("p2p", ignoreCase = true) == true ||
                        linkProps?.linkAddresses?.any { it.address.hostAddress?.startsWith("192.168.49.") == true } == true
                if (isP2p) {
                    network.bindSocket(socket)
                    Log.d(TAG, "Bound TCP socket to Android P2P Network: $network (${linkProps?.interfaceName})")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "bindSocketToP2pNetwork failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun connectToPeer(address: String) {
        // Prevent concurrent duplicate attempts to the same address
        if (_isConnecting.value && _connectingWifiAddress.value == address) {
            Log.w(TAG, "Connection attempt to $address already in progress. Ignoring duplicate.")
            return
        }

        val ch = ensureChannel()
        if (ch == null) {
            notifyError("Wi-Fi Direct channel unavailable. Please toggle Wi-Fi.")
            return
        }

        connectJob?.cancel()
        connectJob = scope.launch {
            _isConnecting.value = true
            _connectingWifiAddress.value = address
            _connectionStatus.value = "Sending P2P invitation to $address..."
            Log.i(TAG, "Initiating direct Wi-Fi P2P connection to target peer: $address")

            // DO NOT call stopPeerDiscovery() here!
            // In Android 10 and 11 (e.g. OPPO A9 2020), stopPeerDiscovery() flushes mPeers in P2pStateMachine,
            // which causes connect() to immediately fail with code 0 (ERROR).
            val config = WifiP2pConfig().apply {
                deviceAddress = address
                wps.setup = WpsInfo.PBC // Required on modern Android
                groupOwnerIntent = 7 // Balanced negotiation intent (0-15)
            }

            wifiP2pManager?.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct connect invitation accepted by framework. Waiting for peer acceptance...")
                    _connectionStatus.value = "Invitation sent! Check the other phone's notification shade or dialog and tap ACCEPT."
                }

                override fun onFailure(reason: Int) {
                    val reasonText = mapFailureReason(reason)
                    val err = "Wi-Fi Direct connect to $address failed: $reasonText (code $reason)"
                    Log.e(TAG, err)
                    _isConnecting.value = false
                    _connectingWifiAddress.value = null
                    _connectionStatus.value = "Connection failed: $reasonText"
                    notifyError(err)

                    // Recovery on code 0 (ERROR) or code 2 (BUSY)
                    if (reason == WifiP2pManager.ERROR || reason == WifiP2pManager.BUSY) {
                        Log.i(TAG, "P2P connect failed (code $reason). Cancelling stale connect...")
                        wifiP2pManager?.cancelConnect(ch, null)
                    }
                }
            })

            // Watchdog timeout: If peer doesn't accept within 40 seconds, reset state cleanly
            delay(40_000)
            if (_isConnecting.value && _connectingWifiAddress.value == address) {
                Log.w(TAG, "Wi-Fi Direct connect invitation to $address timed out after 40s")
                _isConnecting.value = false
                _connectingWifiAddress.value = null
                _connectionStatus.value = "Invitation timed out. Ensure remote phone is scanning and accepts the prompt."
            }
        }
    }

    override fun disconnect(peerAddress: String) {
        connectJob?.cancel()
        connectJob = null
        _isConnecting.value = false
        _connectingWifiAddress.value = null
        _connectionStatus.value = "Disconnected"
        activeSessions[peerAddress]?.close()
        activeSessions.remove(peerAddress)
        val ch = ensureChannel()
        if (ch != null) {
            wifiP2pManager?.cancelConnect(ch, null)
            wifiP2pManager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "P2P Group removed successfully")
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "removeGroup failed (code $reason)")
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    fun resetP2p(onComplete: (() -> Unit)? = null) {
        connectJob?.cancel()
        connectJob = null
        _isConnecting.value = false
        _connectingWifiAddress.value = null
        _isDiscovering.value = false
        _connectionStatus.value = "Resetting Wi-Fi Direct..."

        activeSessions.values.forEach { it.close() }
        activeSessions.clear()

        serverJob?.cancel()
        serverJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        clientConnectJob?.cancel()
        clientConnectJob = null

        val ch = ensureChannel()
        if (ch != null) {
            wifiP2pManager?.cancelConnect(ch, null)
            wifiP2pManager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct reset: Group removed successfully")
                    _connectionStatus.value = "Idle (Reset complete)"
                    onComplete?.invoke()
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Wi-Fi Direct reset: removeGroup failed (code $reason)")
                    _connectionStatus.value = "Idle"
                    onComplete?.invoke()
                }
            })
        } else {
            _connectionStatus.value = "Idle"
            onComplete?.invoke()
        }
    }

    override fun getActiveSessions(): List<TransportSession> = activeSessions.values.toList()

    private fun handleConnectionInfo(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) {
            Log.d(TAG, "handleConnectionInfo: Group not formed yet")
            return
        }

        Log.i(TAG, "handleConnectionInfo: Group formed! isGroupOwner=${info.isGroupOwner}, groupOwnerAddress=${info.groupOwnerAddress?.hostAddress}")

        if (info.isGroupOwner) {
            _connectionStatus.value = "Group Owner: Listening on TCP port $P2P_PORT"
            startTcpServer()
        } else {
            val ownerAddress = info.groupOwnerAddress?.hostAddress
            if (ownerAddress == null) {
                Log.w(TAG, "Group Client: Group Owner IP address is not yet available. Waiting for DHCP lease...")
                scope.launch {
                    delay(800)
                    val ch = ensureChannel() ?: return@launch
                    wifiP2pManager?.requestConnectionInfo(ch) { retryInfo ->
                        handleConnectionInfo(retryInfo)
                    }
                }
                return
            }
            _connectionStatus.value = "Group Client: Connecting to GO at $ownerAddress:$P2P_PORT"
            connectTcpClient(ownerAddress)
        }
    }

    private fun startTcpServer() {
        serverJob?.cancel()
        serverJob = scope.launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(P2P_PORT))
                }
                val localIp = findLocalP2pAddress()?.hostAddress ?: "0.0.0.0"
                Log.i(TAG, "TCP Server bound to port $P2P_PORT (local P2P IP: $localIp). Waiting for client connections...")
                _connectionStatus.value = "Group Owner: Listening on port $P2P_PORT (IP: $localIp)"
                notifyDiagnostic("TCP_SERVER_BOUND", "TCP Server listening on port $P2P_PORT (local IP: $localIp)")

                while (isActive) {
                    val socket = try {
                        withContext(Dispatchers.IO) {
                            serverSocket?.accept()
                        }
                    } catch (e: IOException) {
                        break
                    }

                    if (socket != null) {
                        val remoteAddress = socket.inetAddress.hostAddress ?: "TCP-CLIENT"
                        Log.i(TAG, "Accepted TCP client connection from $remoteAddress")
                        _connectionStatus.value = "Connected to $remoteAddress"
                        _isConnecting.value = false
                        _connectingWifiAddress.value = null
                        notifyDiagnostic("TCP_SERVER_ACCEPTED", "Accepted TCP client connection from $remoteAddress")
                        manageSocket(socket, remoteAddress)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "TCP Server coroutine cancelled cleanly (normal lifecycle)")
                throw e
            } catch (e: Exception) {
                val err = "TCP Server error: ${e.javaClass.simpleName} - ${e.message}"
                Log.e(TAG, err)
                _connectionStatus.value = "Server error: ${e.message}"
                notifyError(err)
            }
        }
    }

    private fun connectTcpClient(host: String) {
        clientConnectJob?.cancel()
        clientConnectJob = scope.launch {
            val attemptId = UUID.randomUUID().toString().take(8)
            var attempts = 0
            val maxAttempts = 20
            var connected = false
            var lastError = ""

            Log.i(TAG, "[$attemptId] Starting TCP client connection loop to Group Owner at $host:$P2P_PORT")
            notifyDiagnostic("TCP_CLIENT_START", "[$attemptId] Starting TCP client connection to $host:$P2P_PORT")

            while (isActive && attempts < maxAttempts && !connected) {
                attempts++
                val startTime = System.currentTimeMillis()

                // 1. Verify whether client has received an IP lease from Group Owner's DHCP server
                val localP2pIp = findLocalP2pAddress()
                if (localP2pIp == null && attempts <= 8) {
                    Log.d(TAG, "[$attemptId] Attempt $attempts/$maxAttempts: Waiting for DHCP lease from GO on P2P interface...")
                    _connectionStatus.value = "Waiting for DHCP IP assignment (attempt $attempts/$maxAttempts)..."
                    delay(1000)
                    continue
                }

                var socket: Socket? = null
                try {
                    val localIpText = localP2pIp?.hostAddress ?: "UNBOUND"
                    Log.d(TAG, "[$attemptId] Attempt $attempts/$maxAttempts to connect TCP client from $localIpText to $host:$P2P_PORT...")
                    _connectionStatus.value = "Connecting to Group Owner at $host (attempt $attempts/$maxAttempts)..."

                    socket = Socket()

                    // Bind to P2P network so cellular data (5G/LTE) does not hijack the route
                    bindSocketToP2pNetwork(socket)

                    // Bind source address to local P2P IP if available
                    if (localP2pIp != null) {
                        socket.reuseAddress = true
                        socket.bind(InetSocketAddress(localP2pIp, 0))
                    }

                    withContext(Dispatchers.IO) {
                        socket.connect(InetSocketAddress(host, P2P_PORT), 3500)
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i(TAG, "[$attemptId] TCP socket connected successfully to $host:$P2P_PORT in ${elapsed}ms!")
                    _connectionStatus.value = "Connected to $host"
                    _isConnecting.value = false
                    _connectingWifiAddress.value = null
                    connected = true

                    notifyDiagnostic("TCP_CLIENT_CONNECTED", "[$attemptId] Connected to $host:$P2P_PORT in ${elapsed}ms")
                    manageSocket(socket, host)
                    break
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - startTime
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    Log.w(TAG, "[$attemptId] Attempt $attempts failed in ${elapsed}ms ($lastError). Retrying in 1000ms...")
                    try {
                        socket?.close()
                    } catch (_: Exception) {}
                    delay(1000)
                }
            }

            if (!connected && isActive) {
                val err = "Failed to establish TCP data channel to Group Owner at $host after $maxAttempts attempts ($lastError)"
                Log.e(TAG, "[$attemptId] $err")
                _connectionStatus.value = "TCP connection failed ($lastError)"
                _isConnecting.value = false
                _connectingWifiAddress.value = null
                notifyError(err)
            }
        }
    }

    private fun manageSocket(socket: Socket, address: String) {
        scope.launch {
            try {
                val inputStream = socket.getInputStream()
                val outputStream = socket.getOutputStream()

                val session = SocketTransportSession(
                    peerAddress = address,
                    transportType = TransportType.WIFI_DIRECT,
                    socket = socket,
                    inputStream = inputStream,
                    outputStream = outputStream,
                    onCloseCallback = { closedSession ->
                        activeSessions.remove(address)
                        notifySessionClosed(closedSession, "TCP socket closed")
                    }
                )

                activeSessions[address] = session
                notifySessionEstablished(session)

                val decoder = FrameCodec.StreamDecoder()
                val buffer = ByteArray(8192)

                while (isActive && session.isConnected) {
                    val bytesRead = try {
                        withContext(Dispatchers.IO) {
                            inputStream.read(buffer)
                        }
                    } catch (e: Exception) {
                        -1
                    }

                    if (bytesRead <= 0) {
                        Log.d(TAG, "Wi-Fi Direct peer $address closed connection (EOF)")
                        break
                    }

                    val frames = decoder.processBytes(buffer, 0, bytesRead)
                    for (frame in frames) {
                        notifyFrameReceived(session, frame)
                    }
                }

                session.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error managing Wi-Fi Direct socket for $address: ${e.message}")
            } finally {
                activeSessions.remove(address)
            }
        }
    }

    private fun mapFailureReason(reason: Int): String {
        return when (reason) {
            WifiP2pManager.ERROR -> "ERROR (Peer unavailable, stale scan cache, or framework error)"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED (Device does not support Wi-Fi Direct)"
            WifiP2pManager.BUSY -> "BUSY (Wi-Fi Direct framework is busy with another operation)"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
            else -> "UNKNOWN_ERROR"
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
        synchronized(listeners) { listeners.forEach { it.onError(TransportType.WIFI_DIRECT, message) } }
    }

    private fun notifyDiagnostic(eventType: String, message: String) {
        synchronized(listeners) { listeners.forEach { it.onDiagnostic(TransportType.WIFI_DIRECT, eventType, message) } }
    }

    companion object {
        private const val TAG = "OfflineLink-WiFi"
        const val P2P_PORT = 8988
    }
}

