package com.offlinelink.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.offlinelink.presentation.screens.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: OfflineLinkViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val activeChatPeerId by viewModel.activeChatPeerId.collectAsState()

    BackHandler(enabled = currentScreen != AppScreen.DASHBOARD || activeChatPeerId != null) {
        if (currentScreen == AppScreen.CHAT && activeChatPeerId != null) {
            viewModel.closeChat()
        } else {
            viewModel.navigateTo(AppScreen.DASHBOARD)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (currentScreen) {
                            AppScreen.DASHBOARD -> "OfflineLink"
                            AppScreen.NEARBY -> "Nearby & Connections"
                            AppScreen.CHAT -> "Encrypted Chat"
                            AppScreen.SOS -> "Emergency SOS"
                            AppScreen.TRANSFERS -> "File Transfers"
                            AppScreen.QUEUE -> "Store & Forward Queue"
                            AppScreen.DIAGNOSTICS -> "Network Diagnostics"
                            AppScreen.SECURITY -> "Security & Trust"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.navigateTo(AppScreen.TRANSFERS) }) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Transfers")
                    }
                    IconButton(onClick = { viewModel.navigateTo(AppScreen.QUEUE) }) {
                        Icon(Icons.Default.AllInbox, contentDescription = "Queue")
                    }
                    IconButton(onClick = { viewModel.navigateTo(AppScreen.DIAGNOSTICS) }) {
                        Icon(Icons.Default.Speed, contentDescription = "Diagnostics")
                    }
                    IconButton(onClick = { viewModel.navigateTo(AppScreen.SECURITY) }) {
                        Icon(Icons.Default.Security, contentDescription = "Security")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentScreen == AppScreen.DASHBOARD,
                    onClick = { viewModel.navigateTo(AppScreen.DASHBOARD) },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = currentScreen == AppScreen.NEARBY,
                    onClick = { viewModel.navigateTo(AppScreen.NEARBY) },
                    icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                    label = { Text("Nearby") }
                )
                NavigationBarItem(
                    selected = currentScreen == AppScreen.CHAT,
                    onClick = { viewModel.navigateTo(AppScreen.CHAT) },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                    label = { Text("Chat") }
                )
                NavigationBarItem(
                    selected = currentScreen == AppScreen.SOS,
                    onClick = { viewModel.navigateTo(AppScreen.SOS) },
                    icon = { Icon(Icons.Default.Warning, contentDescription = null) },
                    label = { Text("SOS") }
                )
                NavigationBarItem(
                    selected = currentScreen == AppScreen.DIAGNOSTICS,
                    onClick = { viewModel.navigateTo(AppScreen.DIAGNOSTICS) },
                    icon = { Icon(Icons.Default.Speed, contentDescription = null) },
                    label = { Text("Stats") }
                )
            }
        }
    ) { innerPadding ->
        when (currentScreen) {
            AppScreen.DASHBOARD -> DashboardScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
            AppScreen.NEARBY -> NearbyScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
            AppScreen.CHAT -> ChatScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
            AppScreen.SOS -> SosScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
            AppScreen.TRANSFERS -> FileTransferScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
            AppScreen.QUEUE -> QueueScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
            AppScreen.DIAGNOSTICS -> DiagnosticsScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
            AppScreen.SECURITY -> SecurityScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
        }
    }
}
