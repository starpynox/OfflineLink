package com.offlinelink.presentation.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinelink.data.FileTransferEntity
import com.offlinelink.presentation.OfflineLinkViewModel

@Composable
fun FileTransferScreen(
    viewModel: OfflineLinkViewModel,
    modifier: Modifier = Modifier
) {
    val transfers by viewModel.fileTransfers.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val connectedPeers = peers.filter { it.isConnected }
    val context = LocalContext.current

    var selectedRecipientId by remember { mutableStateOf(connectedPeers.firstOrNull()?.deviceId ?: "") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && selectedRecipientId.isNotBlank()) {
            val fileName = "doc_${System.currentTimeMillis()}.bin"
            val fileSize = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 2048L
            } catch (e: Exception) { 2048L }
            viewModel.sendFile(selectedRecipientId, uri, fileName, fileSize)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Send Card
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Send Offline File", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                if (connectedPeers.isEmpty()) {
                    Text("No connected peers. Connect via Bluetooth or Wi-Fi Direct first to send files.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(text = "Target Peer: ${connectedPeers.firstOrNull()?.displayName ?: selectedRecipientId}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            selectedRecipientId = connectedPeers.first().deviceId
                            filePickerLauncher.launch("*/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select and Send File")
                    }
                }
            }
        }

        // Transfers List
        Text(text = "Transfer History (${transfers.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        if (transfers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No file transfers yet.", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(transfers) { transfer ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = transfer.fileName, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "${transfer.direction} • ${transfer.fileSize / 1024} KB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = when (transfer.status) {
                                        FileTransferEntity.TRANSFER_COMPLETED -> MaterialTheme.colorScheme.primaryContainer
                                        FileTransferEntity.TRANSFER_FAILED -> MaterialTheme.colorScheme.errorContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ) {
                                    Text(
                                        text = transfer.status,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            val progress = if (transfer.totalChunks > 0) {
                                transfer.transferredChunks.toFloat() / transfer.totalChunks.toFloat()
                            } else 0f
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Chunks: ${transfer.transferredChunks}/${transfer.totalChunks}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                                Text(
                                    text = "SHA-256: ${transfer.sha256.take(12)}...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
