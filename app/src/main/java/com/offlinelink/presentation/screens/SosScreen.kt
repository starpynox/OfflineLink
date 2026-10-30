package com.offlinelink.presentation.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinelink.data.MessageEntity
import com.offlinelink.presentation.OfflineLinkViewModel
import com.offlinelink.protocol.SosPayload

@Composable
fun SosScreen(
    viewModel: OfflineLinkViewModel,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(SosPayload.CATEGORY_ASSISTANCE) }
    var customNote by remember { mutableStateOf("") }
    var attachLocation by remember { mutableStateOf(true) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    val myIdentity by viewModel.myIdentity.collectAsState()
    val sosMessages by viewModel.getConversationMessages("EMERGENCY_SOS").collectAsState(initial = emptyList())

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Warning Banner
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Emergency, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Emergency Mesh Broadcast",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "SOS broadcasts with urgent priority across all Bluetooth & Wi-Fi Direct multi-hop relays.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Text(text = "Select Emergency Category", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        // Category Chips
        val categories = listOf(
            SosPayload.CATEGORY_MEDICAL to "Medical Aid",
            SosPayload.CATEGORY_TRAPPED to "Trapped / Rescue",
            SosPayload.CATEGORY_ASSISTANCE to "Assistance",
            SosPayload.CATEGORY_SAFETY_CHECKIN to "Safety Check-in",
            SosPayload.CATEGORY_CUSTOM to "Custom Alert"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.take(3).forEach { (cat, label) ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.drop(3).forEach { (cat, label) ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }

        OutlinedTextField(
            value = customNote,
            onValueChange = { customNote = it },
            label = { Text("Situation Details / Notes") },
            placeholder = { Text("e.g. 2 people injured, need water and stretcher near campsite...") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = attachLocation,
                onCheckedChange = { attachLocation = it }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = "Attach GPS Location Snapshot", fontWeight = FontWeight.Medium)
                Text(text = "Shares current latitude & longitude offline with nearby responders.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }

        // Broadcast Button with Safeguard
        Button(
            onClick = { showConfirmDialog = true },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Send, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "BROADCAST SOS ALERT", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        OutlinedButton(
            onClick = { viewModel.cancelSosAlert() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Send 'All Clear' / Cancel Alert")
        }

        Divider()

        // Emergency Broadcast History
        val incomingAlerts = sosMessages.filter { it.direction == MessageEntity.DIRECTION_INCOMING }
        val outgoingAlerts = sosMessages.filter { it.direction == MessageEntity.DIRECTION_OUTGOING }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Emergency Mesh Alerts (${sosMessages.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (sosMessages.isNotEmpty()) {
                Text(
                    text = "${incomingAlerts.size} received · ${outgoingAlerts.size} sent",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }

        if (sosMessages.isEmpty()) {
            Text("No emergency alerts received or broadcast yet.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        } else {
            for (msg in sosMessages) {
                val isOutgoing = msg.senderId == myIdentity.deviceId || msg.direction == MessageEntity.DIRECTION_OUTGOING
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = when {
                            msg.content.startsWith("SOS CANCELLED") -> MaterialTheme.colorScheme.surfaceVariant
                            isOutgoing -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                ) {
                                    Text(
                                        text = if (isOutgoing) "My Broadcast" else "Received Alert",
                                        color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isOutgoing) "This Device" else msg.senderId,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(text = "Hops: ${msg.hopCount}/${msg.hopLimit}", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = msg.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Confirm Emergency SOS") },
            text = { Text("Are you sure you want to broadcast an emergency $selectedCategory alert to all nearby nodes and store-and-forward relays?") },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        viewModel.sendSosAlert(selectedCategory, customNote.ifBlank { "Immediate assistance requested" }, attachLocation)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Broadcast Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
