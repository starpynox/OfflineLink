package com.offlinelink.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinelink.data.PeerEntity
import com.offlinelink.presentation.OfflineLinkViewModel

@Composable
fun SecurityScreen(
    viewModel: OfflineLinkViewModel,
    modifier: Modifier = Modifier
) {
    val myIdentity by viewModel.myIdentity.collectAsState()
    val peers by viewModel.peers.collectAsState()

    var editingName by remember { mutableStateOf(false) }
    var tempName by remember { mutableStateOf(myIdentity.deviceName) }
    var verifyingPeer by remember { mutableStateOf<PeerEntity?>(null) }
    var showClearDataDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Security & Trust Management", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        // My Cryptographic Identity Card
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Local Node Identity", fontWeight = FontWeight.Bold)
                    IconButton(onClick = {
                        tempName = myIdentity.deviceName
                        editingName = true
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit name")
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Device ID: ${myIdentity.deviceId}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text(text = "Display Name: ${myIdentity.deviceName}", style = MaterialTheme.typography.bodyMedium)

                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Cryptographic Fingerprint (SHA-256):", style = MaterialTheme.typography.labelMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = myIdentity.fingerprint,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(myIdentity.fingerprint))
                            Toast.makeText(context, "Fingerprint copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // Trusted Peers List
        Text(text = "Peer Trust Verification (${peers.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        if (peers.isEmpty()) {
            Text("No discovered peers yet.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        } else {
            for (peer in peers) {
                OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = peer.displayName, fontWeight = FontWeight.Bold)
                                Text(text = peer.deviceId, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = when (peer.trustStatus) {
                                    PeerEntity.TRUST_TRUSTED -> MaterialTheme.colorScheme.primaryContainer
                                    PeerEntity.TRUST_REVOKED -> MaterialTheme.colorScheme.errorContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = peer.trustStatus,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Fingerprint: ${peer.fingerprint.take(20)}...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color.Gray
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { verifyingPeer = peer }) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Verify (SAS)")
                            }
                        }
                    }
                }
            }
        }

        Divider()

        // Privacy & Reset Section
        Text(text = "Privacy & Local Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(
            onClick = { showClearDataDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.DeleteForever, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear All Local Messages & Diagnostics")
        }
    }

    // Name Editing Dialog
    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Edit Device Display Name") },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    label = { Text("Node Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateDeviceName(tempName)
                    editingName = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingName = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // SAS Verification Dialog
    verifyingPeer?.let { peer ->
        val sasCode = viewModel.computeSasCode(peer.fingerprint)
        AlertDialog(
            onDismissRequest = { verifyingPeer = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Verify Peer Identity")
                }
            },
            text = {
                Column {
                    Text("Compare the 6-digit Short Authentication String (SAS) code verbally or out-of-band with ${peer.displayName}:")
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = sasCode,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 4.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Both devices calculate this code from your respective cryptographic fingerprints. If the numbers match, there is no man-in-the-middle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updatePeerTrust(peer.deviceId, PeerEntity.TRUST_TRUSTED)
                    verifyingPeer = null
                    Toast.makeText(context, "${peer.displayName} marked as TRUSTED", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Mark Trusted")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        viewModel.updatePeerTrust(peer.deviceId, PeerEntity.TRUST_REVOKED)
                        verifyingPeer = null
                        Toast.makeText(context, "${peer.displayName} REVOKED", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Revoke")
                }
            }
        )
    }

    // Clear Data Confirmation Dialog
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("Clear All Local Data?") },
            text = { Text("This will permanently delete all stored messages, queue items, and diagnostic telemetry from this device.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDataDialog = false
                        viewModel.clearAllData()
                        Toast.makeText(context, "All local data cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
