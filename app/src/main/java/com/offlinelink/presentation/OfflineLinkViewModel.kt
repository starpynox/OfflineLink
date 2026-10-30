package com.offlinelink.presentation

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinelink.OfflineLinkApp
import com.offlinelink.data.DiagnosticLogEntity
import com.offlinelink.data.FileTransferEntity
import com.offlinelink.data.MessageEntity
import com.offlinelink.data.PeerEntity
import com.offlinelink.data.QueueItemEntity
import com.offlinelink.diagnostics.BenchmarkResult
import com.offlinelink.features.LocationSnapshot
import com.offlinelink.security.CryptoEngine
import com.offlinelink.security.DeviceIdentity
import com.offlinelink.transport.TransportPolicy
import com.offlinelink.transport.TransportSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen {
    DASHBOARD,
    NEARBY,
    CHAT,
    SOS,
    TRANSFERS,
    QUEUE,
    DIAGNOSTICS,
    SECURITY
}

class OfflineLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OfflineLinkApp
    private val repository = app.repository
    private val router = app.router
    private val transportManager = app.transportManager
    private val identityManager = app.identityManager
    private val benchmarkEngine = app.benchmarkEngine
    private val fileTransferManager = app.fileTransferManager
    private val locationHelper = app.locationHelper

    private val _currentScreen = MutableStateFlow(AppScreen.DASHBOARD)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _activeChatPeerId = MutableStateFlow<String?>(null)
    val activeChatPeerId: StateFlow<String?> = _activeChatPeerId.asStateFlow()

    val myIdentity: StateFlow<DeviceIdentity> = MutableStateFlow(identityManager.getIdentity()).asStateFlow()

    val peers: StateFlow<List<PeerEntity>> = repository.allPeersFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val queueItems: StateFlow<List<QueueItemEntity>> = repository.queueItemsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val fileTransfers: StateFlow<List<FileTransferEntity>> = repository.fileTransfersFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentDiagnostics: StateFlow<List<DiagnosticLogEntity>> = repository.recentDiagnosticsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val transportPolicy: StateFlow<TransportPolicy> = transportManager.policy

    val pingLatencyMs: StateFlow<Long?> = router.lastPingLatencyMs

    val benchmarkResult: StateFlow<BenchmarkResult?> = benchmarkEngine.lastBenchmarkResult
    val isBenchmarkRunning: StateFlow<Boolean> = benchmarkEngine.isBenchmarkRunning

    // Reactive transport state
    val discoveredWifiDevices = app.wifiDirectTransport.discoveredDevicesFlow
    val isWifiDirectDiscovering = app.wifiDirectTransport.isDiscovering
    val isConnectingWifi = app.wifiDirectTransport.isConnecting
    val connectingWifiAddress = app.wifiDirectTransport.connectingWifiAddress
    val wifiConnectionStatus = app.wifiDirectTransport.connectionStatus
    val isBtServerListening = app.bluetoothTransport.isServerListening
    val connectingBtAddress = app.bluetoothTransport.connectingPeerAddress
    val btServerStatusText = app.bluetoothTransport.serverStatusText

    val recentConversationIds: StateFlow<List<String>> = repository.recentConversationIdsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun startBluetoothHosting() {
        app.bluetoothTransport.startHosting()
    }

    fun stopBluetoothHosting() {
        app.bluetoothTransport.stopHosting()
    }

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    fun openChatWith(peerId: String) {
        val cleanId = peerId.trim()
        if (cleanId.isNotBlank()) {
            _activeChatPeerId.value = cleanId
            _currentScreen.value = AppScreen.CHAT
        } else {
            _activeChatPeerId.value = null
        }
    }

    fun closeChat() {
        _activeChatPeerId.value = null
    }

    fun getConversationMessages(conversationId: String) = repository.getConversationFlow(conversationId)

    fun sendTextMessage(recipientId: String, text: String) {
        val cleanRecipient = recipientId.trim()
        val cleanText = text.trim()
        if (cleanRecipient.isNotBlank() && cleanText.isNotBlank()) {
            router.sendTextMessage(cleanRecipient, cleanText)
        }
    }

    fun sendSosAlert(category: String, note: String, includeLocation: Boolean) {
        var snapshot: LocationSnapshot? = null
        if (includeLocation) {
            snapshot = locationHelper.getOneTimeLocationSnapshot()
        }
        router.sendSosAlert(
            category = category,
            note = note,
            latitude = snapshot?.latitude,
            longitude = snapshot?.longitude,
            accuracy = snapshot?.accuracyMeters,
            isCancelled = false
        )
    }

    fun cancelSosAlert(note: String = "All clear - situation resolved") {
        router.sendSosAlert(
            category = "CANCELLED",
            note = note,
            isCancelled = true
        )
    }

    fun sendPing(peerId: String) {
        router.sendPing(peerId)
    }

    fun runBenchmark(peerId: String, payloadSize: Int, trials: Int) {
        benchmarkEngine.runBenchmark(peerId, payloadSize, trials)
    }

    fun setTransportPolicy(policy: TransportPolicy) {
        transportManager.setTransportPolicy(policy)
    }

    fun connectBluetooth(address: String) {
        app.bluetoothTransport.connectToPeer(address)
    }

    fun getPairedBluetoothDevices() = app.bluetoothTransport.getPairedDevices()

    fun startWifiDirectDiscovery(onSuccess: (() -> Unit)? = null, onFailure: ((String) -> Unit)? = null) {
        app.wifiDirectTransport.discoverPeers(onSuccess, onFailure)
    }

    fun stopWifiDirectDiscovery(onComplete: (() -> Unit)? = null) {
        app.wifiDirectTransport.stopPeerDiscovery(onComplete)
    }

    fun cancelWifiDirectConnect(onComplete: (() -> Unit)? = null) {
        app.wifiDirectTransport.cancelConnect(onComplete)
    }

    fun resetWifiDirect(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            app.wifiDirectTransport.resetP2p {
                restartHosting()
                onComplete?.invoke()
            }
        }
    }

    fun getDiscoveredWifiDirectDevices() = app.wifiDirectTransport.getDiscoveredDevices()

    fun connectWifiDirect(deviceAddress: String) {
        app.wifiDirectTransport.connectToPeer(deviceAddress)
    }

    fun onPermissionsGranted() {
        viewModelScope.launch {
            app.bluetoothTransport.startHosting()
            app.wifiDirectTransport.startHosting()
        }
    }

    fun restartHosting() {
        viewModelScope.launch {
            app.bluetoothTransport.startHosting()
            app.wifiDirectTransport.startHosting()
        }
    }

    fun disconnectPeer(address: String) {
        transportManager.bluetoothTransport.disconnect(address)
        transportManager.wifiDirectTransport.disconnect(address)
    }

    fun sendFile(recipientId: String, uri: Uri, fileName: String, fileSize: Long) {
        fileTransferManager.sendFile(recipientId, uri, fileName, fileSize)
    }

    fun updatePeerTrust(deviceId: String, trustStatus: String) {
        viewModelScope.launch {
            repository.updatePeerTrust(deviceId, trustStatus)
        }
    }

    fun computeSasCode(remoteFingerprint: String): String {
        return CryptoEngine.computeSasCode(myIdentity.value.fingerprint, remoteFingerprint)
    }

    fun updateDeviceName(name: String) {
        identityManager.updateDeviceName(name)
        (myIdentity as MutableStateFlow).value = identityManager.getIdentity()
    }

    fun clearConversation(conversationId: String, onComplete: (() -> Unit)? = null) {
        val cleanId = conversationId.trim()
        if (cleanId.isNotBlank()) {
            viewModelScope.launch {
                repository.clearConversation(cleanId)
                onComplete?.invoke()
            }
        }
    }

    fun clearAllChats(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            repository.clearAllChats()
            onComplete?.invoke()
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.clearAllData()
        }
    }

    suspend fun exportDiagnosticsCsv(): String = benchmarkEngine.exportDiagnosticsAsCsv()
    suspend fun exportDiagnosticsJson(): String = benchmarkEngine.exportDiagnosticsAsJson()
}
