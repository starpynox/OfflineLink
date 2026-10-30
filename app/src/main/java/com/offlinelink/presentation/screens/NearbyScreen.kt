package com.offlinelink.presentation.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinelink.presentation.OfflineLinkViewModel
import com.offlinelink.transport.TransportPolicy

@SuppressLint("MissingPermission")
@Composable
fun NearbyScreen(
    viewModel: OfflineLinkViewModel,
    modifier: Modifier = Modifier
) {
    val policy by viewModel.transportPolicy.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val connectedPeers = peers.filter { it.isConnected }

    val isBtServerListening by viewModel.isBtServerListening.collectAsState()
    val connectingBtAddress by viewModel.connectingBtAddress.collectAsState()
    val btServerStatusText by viewModel.btServerStatusText.collectAsState()

    val wifiDevices by viewModel.discoveredWifiDevices.collectAsState()
    val isConnectingWifi by viewModel.isConnectingWifi.collectAsState()
    val connectingWifiAddress by viewModel.connectingWifiAddress.collectAsState()
    val wifiConnectionStatus by viewModel.wifiConnectionStatus.collectAsState()

    val isDiscoveringWifi by viewModel.isWifiDirectDiscovering.collectAsState()
    var btDevices by remember { mutableStateOf(emptyList<android.bluetooth.BluetoothDevice>()) }

    LaunchedEffect(Unit) {
        btDevices = viewModel.getPairedBluetoothDevices()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Server Hosting Status Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (isBtServerListening) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isBtServerListening) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isBtServerListening) "Offline Server: LISTENING" else "Offline Server: STOPPED",
                                fontWeight = FontWeight.Bold,
                                color = if (isBtServerListening) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Text(
                            text = if (isBtServerListening) btServerStatusText else "Server socket is not listening. Tap Restart to initialize.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isBtServerListening) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { viewModel.restartHosting() },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Text("Restart")
                    }
                }
            }
        }

        // Active Application Data Channels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Active Transport Sessions (${connectedPeers.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (connectedPeers.isNotEmpty()) {
                Surface(
                    color = Color(0xFFE8F5E9),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Data Channel Active",
                        color = Color(0xFF2E7D32),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (connectedPeers.isEmpty()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LinkOff, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "No active transport connections established yet. Connect via Bluetooth or Wi-Fi Direct below.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            for (peer in connectedPeers) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (peer.activeTransport == "WIFI_DIRECT") Icons.Default.Wifi else Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = peer.displayName, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = "Transport: ${peer.activeTransport} • Verified ID: ${peer.deviceId.take(8)}...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Fingerprint: ${peer.fingerprint}",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(
                                onClick = { viewModel.openChatWith(peer.deviceId) }
                            ) {
                                Text("Chat")
                            }
                            OutlinedButton(
                                onClick = { viewModel.disconnectPeer(peer.bluetoothAddress ?: peer.wifiAddress ?: peer.deviceId) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }
        }

        // Transport Policy Selector
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Transport Preference",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = policy == TransportPolicy.AUTOMATIC,
                        onClick = { viewModel.setTransportPolicy(TransportPolicy.AUTOMATIC) },
                        label = { Text("Auto") }
                    )
                    FilterChip(
                        selected = policy == TransportPolicy.BLUETOOTH_PREFERRED,
                        onClick = { viewModel.setTransportPolicy(TransportPolicy.BLUETOOTH_PREFERRED) },
                        label = { Text("BT Preferred") }
                    )
                    FilterChip(
                        selected = policy == TransportPolicy.WIFI_DIRECT_PREFERRED,
                        onClick = { viewModel.setTransportPolicy(TransportPolicy.WIFI_DIRECT_PREFERRED) },
                        label = { Text("Wi-Fi Direct") }
                    )
                }
            }
        }

        HorizontalDivider()

        // Bluetooth Classic RFCOMM Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Bluetooth Classic (RFCOMM)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { btDevices = viewModel.getPairedBluetoothDevices() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh paired devices")
            }
        }

        if (btDevices.isEmpty()) {
            Text(
                text = "No paired Bluetooth devices found. Pair both Android phones in Android Settings > Bluetooth first, then tap refresh.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            for (dev in btDevices) {
                val isConnectingThis = connectingBtAddress == dev.address
                val isConnectedThis = connectedPeers.any { it.bluetoothAddress == dev.address }
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = dev.name ?: "Unknown Bluetooth Device", fontWeight = FontWeight.Medium)
                            Text(text = dev.address, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            if (isConnectedThis) {
                                Text(text = "RFCOMM Connected", color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            } else {
                                Text(text = "Paired in Android Settings", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.connectBluetooth(dev.address) },
                            enabled = connectingBtAddress == null && !isConnectedThis
                        ) {
                            if (isConnectingThis) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Connecting...")
                            } else if (isConnectedThis) {
                                Text("Connected")
                            } else {
                                Text("Connect")
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // Wi-Fi Direct Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Wi-Fi Direct (P2P + TCP)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (isDiscoveringWifi) {
                OutlinedButton(
                    onClick = { viewModel.stopWifiDirectDiscovery() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop Scan")
                }
            } else {
                FilledTonalButton(
                    onClick = { viewModel.startWifiDirectDiscovery() },
                    enabled = !isConnectingWifi
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scan P2P")
                }
            }
        }

        // Wi-Fi Direct Connection Status readout
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isConnectingWifi) Icons.Default.Sync else Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "P2P Status: $wifiConnectionStatus",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp
                    )
                }
                if (isConnectingWifi) {
                    TextButton(onClick = { viewModel.cancelWifiDirectConnect() }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Connection Instructions & Guidance Card
        Surface(
            color = Color(0xFFF1F5F9),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "Wi-Fi Direct Connection Guide:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "1. Tap 'Scan P2P' on BOTH phones so Wi-Fi radios stay active.\n" +
                           "2. Tap 'P2P Connect' on ONE phone only.\n" +
                           "3. Modern Android (OnePlus / Android 13+): The prompt arrives in the NOTIFICATION SHADE. Swipe down from top of screen and tap ACCEPT.",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = Color.DarkGray
                )
            }
        }

        if (wifiDevices.isEmpty()) {
            Text(
                text = "Tap 'Scan P2P' on both phones. When the other phone appears, tap 'P2P Connect'.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            for (p2p in wifiDevices) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = p2p.deviceName.ifBlank { "Wi-Fi Direct Peer" }, fontWeight = FontWeight.Medium)
                            Text(text = p2p.deviceAddress, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        val isConnectingThis = connectingWifiAddress == p2p.deviceAddress
                        Button(
                            onClick = { viewModel.connectWifiDirect(p2p.deviceAddress) },
                            enabled = connectingWifiAddress == null
                        ) {
                            if (isConnectingThis) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Connecting...")
                            } else {
                                Text("P2P Connect")
                            }
                        }
                    }
                }
            }
        }

        // Clean Reset Button
        OutlinedButton(
            onClick = {
                viewModel.resetWifiDirect()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reset Wi-Fi Direct Groups & Restart Server")
        }
    }
}
