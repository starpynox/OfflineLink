package com.offlinelink.presentation.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.offlinelink.data.MessageEntity
import com.offlinelink.presentation.OfflineLinkViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: OfflineLinkViewModel,
    modifier: Modifier = Modifier
) {
    val activePeerId by viewModel.activeChatPeerId.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val myIdentity by viewModel.myIdentity.collectAsState()

    var textInput by remember { mutableStateOf("") }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showClearConversationDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && activePeerId != null) {
            val fileName = "file_${System.currentTimeMillis()}.dat"
            val fileSize = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 1024L
            } catch (e: Exception) { 1024L }
            viewModel.sendFile(activePeerId!!, uri, fileName, fileSize)
        }
    }

    val recentConversations by viewModel.recentConversationIds.collectAsState()
    val validRecent = recentConversations.filter { it.isNotBlank() && it != "EMERGENCY_SOS" }

    if (activePeerId == null) {
        // Conversation / Peer Selector
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Conversations & Encrypted Chat",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (peers.isNotEmpty() || validRecent.isNotEmpty()) {
                    IconButton(onClick = { showClearAllDialog = true }) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "Clear All Chats",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (showClearAllDialog) {
                AlertDialog(
                    onDismissRequest = { showClearAllDialog = false },
                    title = { Text("Clear All Chats?") },
                    text = { Text("This will permanently delete all conversation history and pending chat queue items. Discovered peers, trust status, and security keys will be preserved.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.clearAllChats()
                                showClearAllDialog = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Clear All")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearAllDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            if (peers.isEmpty() && validRecent.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No discovered peers yet. Connect via Bluetooth or Wi-Fi Direct first.", color = Color.Gray)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (peers.isNotEmpty()) {
                        item {
                            Text(
                                text = "Discovered & Nearby Peers",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(peers) { peer ->
                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.openChatWith(peer.deviceId) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(text = peer.displayName, fontWeight = FontWeight.SemiBold)
                                        Text(text = peer.deviceId, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (peer.isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = if (peer.isConnected) "Online (${peer.activeTransport})" else "Store & Forward",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val existingPeersDeviceIds = peers.map { it.deviceId }.toSet()
                    val otherRecent = validRecent.filter { it !in existingPeersDeviceIds }

                    if (otherRecent.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Saved Conversations (Store & Forward)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(otherRecent) { convId ->
                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.openChatWith(convId) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(text = convId, fontWeight = FontWeight.SemiBold)
                                        Text(text = "Queued / Store & Forward", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = "Queued",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Handle system Back gesture
        BackHandler {
            viewModel.closeChat()
        }

        // Active 1-on-1 Chat
        val currentTargetId = activePeerId!!
        val conversationMessages by viewModel.getConversationMessages(currentTargetId).collectAsState(initial = emptyList())
        val listState = rememberLazyListState()

        LaunchedEffect(conversationMessages.size) {
            if (conversationMessages.isNotEmpty()) {
                listState.animateScrollToItem(conversationMessages.size - 1)
            }
        }

        val targetPeer = peers.firstOrNull { it.deviceId == currentTargetId }

        Column(modifier = modifier.fillMaxSize()) {
            // Chat Header
            Surface(
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.closeChat() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = targetPeer?.displayName ?: currentTargetId,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "End-to-End Encrypted (AES-GCM)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.sendPing(currentTargetId) }) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = "Ping")
                    }
                    IconButton(onClick = { showClearConversationDialog = true }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Conversation", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (showClearConversationDialog) {
                AlertDialog(
                    onDismissRequest = { showClearConversationDialog = false },
                    title = { Text("Clear Conversation?") },
                    text = { Text("Delete message history and queued messages for this contact? The peer connection, trust verification, and encryption keys will not be affected.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.clearConversation(currentTargetId)
                                showClearConversationDialog = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Clear")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearConversationDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Message Stream
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(conversationMessages) { msg ->
                    MessageBubble(msg = msg, isOutgoing = msg.senderId == myIdentity.deviceId)
                }
            }

            // Compose & Send Row
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach File")
                    }
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Encrypted offline message...") },
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank() && currentTargetId.isNotBlank()) {
                                viewModel.sendTextMessage(currentTargetId, textInput)
                                textInput = ""
                            }
                        },
                        enabled = textInput.isNotBlank() && currentTargetId.isNotBlank()
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (textInput.isNotBlank() && currentTargetId.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(msg: MessageEntity, isOutgoing: Boolean) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val timeStr = timeFormat.format(Date(msg.timestamp))

    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val alignment = if (isOutgoing) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isOutgoing) 16.dp else 2.dp,
                bottomEnd = if (isOutgoing) 2.dp else 16.dp
            ),
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = msg.content,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeStr,
                        fontSize = 10.sp,
                        color = textColor.copy(alpha = 0.7f)
                    )
                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        val statusIcon = when (msg.deliveryStatus) {
                            MessageEntity.STATUS_DELIVERED -> Icons.Default.DoneAll
                            MessageEntity.STATUS_RELAYED -> Icons.Default.AltRoute
                            MessageEntity.STATUS_SENDING -> Icons.Default.Done
                            else -> Icons.Default.Schedule
                        }
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = msg.deliveryStatus,
                            tint = textColor.copy(alpha = 0.9f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = msg.deliveryStatus,
                            fontSize = 9.sp,
                            color = textColor.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}
