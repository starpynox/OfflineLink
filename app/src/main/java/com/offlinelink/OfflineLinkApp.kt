package com.offlinelink

import android.app.Application
import android.util.Log
import com.offlinelink.data.OfflineLinkDatabase
import com.offlinelink.data.OfflineLinkRepository
import com.offlinelink.diagnostics.BenchmarkEngine
import com.offlinelink.features.FileTransferManager
import com.offlinelink.features.LocationHelper
import com.offlinelink.routing.Router
import com.offlinelink.security.DeviceIdentityManager
import com.offlinelink.service.OfflineLinkForegroundService
import com.offlinelink.transport.BluetoothRfcommTransport
import com.offlinelink.transport.HybridTransportManager
import com.offlinelink.transport.WifiDirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OfflineLinkApp : Application() {

    lateinit var database: OfflineLinkDatabase
        private set

    lateinit var repository: OfflineLinkRepository
        private set

    lateinit var identityManager: DeviceIdentityManager
        private set

    lateinit var bluetoothTransport: BluetoothRfcommTransport
        private set

    lateinit var wifiDirectTransport: WifiDirectTransport
        private set

    lateinit var transportManager: HybridTransportManager
        private set

    lateinit var router: Router
        private set

    lateinit var fileTransferManager: FileTransferManager
        private set

    lateinit var locationHelper: LocationHelper
        private set

    lateinit var benchmarkEngine: BenchmarkEngine
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = OfflineLinkDatabase.getInstance(this)
        repository = OfflineLinkRepository.getInstance(this)
        identityManager = DeviceIdentityManager.getInstance(this)

        val myIdentity = identityManager.getIdentity()
        Log.i(TAG, "OfflineLink initializing with Device ID: ${myIdentity.deviceId}, Fingerprint: ${myIdentity.fingerprint}")

        bluetoothTransport = BluetoothRfcommTransport(this)
        wifiDirectTransport = WifiDirectTransport(this)
        transportManager = HybridTransportManager(
            context = this,
            bluetoothTransport = bluetoothTransport,
            wifiDirectTransport = wifiDirectTransport,
            repository = repository
        )

        router = Router(
            context = this,
            transportManager = transportManager,
            repository = repository,
            identityManager = identityManager
        )

        fileTransferManager = FileTransferManager(
            context = this,
            transportManager = transportManager,
            repository = repository,
            identityManager = identityManager
        )

        locationHelper = LocationHelper(this)

        benchmarkEngine = BenchmarkEngine(
            transportManager = transportManager,
            repository = repository,
            myDeviceId = myIdentity.deviceId
        )

        // Queue recovery and transport hosting startup
        appScope.launch {
            repository.recoverQueueOnStartup()
            transportManager.startAllHosting()
            OfflineLinkForegroundService.start(this@OfflineLinkApp)
        }
    }

    companion object {
        const val TAG = "OfflineLink"
        lateinit var instance: OfflineLinkApp
            private set
    }
}
