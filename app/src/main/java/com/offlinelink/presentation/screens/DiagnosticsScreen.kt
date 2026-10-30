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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinelink.presentation.OfflineLinkViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    viewModel: OfflineLinkViewModel,
    modifier: Modifier = Modifier
) {
    val logs by viewModel.recentDiagnostics.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val connectedPeers = peers.filter { it.isConnected }
    val pingLatency by viewModel.pingLatencyMs.collectAsState()
    val benchmarkResult by viewModel.benchmarkResult.collectAsState()
    val isBenchmarkRunning by viewModel.isBenchmarkRunning.collectAsState()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var selectedPayloadSize by remember { mutableStateOf(1024) }
    var selectedTrials by remember { mutableStateOf(10) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Diagnostics & Benchmarks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        // Ping Latency Card
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "Round-Trip Latency (Ping RTT)", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (pingLatency != null) "${pingLatency} ms" else "No ping sent yet",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = {
                            connectedPeers.firstOrNull()?.let { viewModel.sendPing(it.deviceId) }
                        },
                        enabled = connectedPeers.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Ping")
                    }
                }
            }
        }

        // Benchmark Suite Card
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Network Benchmark Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Runs repeatable transmission trials between two connected nodes to measure throughput and jitter.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(12.dp))

                Text(text = "Payload Size", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(256 to "256 B", 1024 to "1 KB", 10240 to "10 KB", 51200 to "50 KB").forEach { (size, label) ->
                        FilterChip(
                            selected = selectedPayloadSize == size,
                            onClick = { selectedPayloadSize = size },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Trial Count", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 25, 50).forEach { trials ->
                        FilterChip(
                            selected = selectedTrials == trials,
                            onClick = { selectedTrials = trials },
                            label = { Text("$trials Trials", fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val target = connectedPeers.firstOrNull()
                        if (target != null) {
                            viewModel.runBenchmark(target.deviceId, selectedPayloadSize, selectedTrials)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectedPeers.isNotEmpty() && !isBenchmarkRunning
                ) {
                    if (isBenchmarkRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Running Benchmark Trials...")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (connectedPeers.isNotEmpty()) "Run Benchmark (${connectedPeers.first().displayName})" else "Connect a peer to benchmark")
                    }
                }

                benchmarkResult?.let { res ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = "Benchmark Results", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = "Success Rate: ${res.successfulTrials} / ${res.totalTrials} (%.0f%%)".format(res.successfulTrials.toDouble() / res.totalTrials * 100), fontSize = 13.sp)
                            Text(text = "Avg Latency: %.1f ms (Min: %d ms, Max: %d ms)".format(res.avgLatencyMs, res.minLatencyMs, res.maxLatencyMs), fontSize = 13.sp)
                            Text(text = "Throughput: %.2f KB/s".format(res.throughputBytesPerSec / 1024.0), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Export Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        val csv = viewModel.exportDiagnosticsCsv()
                        clipboardManager.setText(AnnotatedString(csv))
                        Toast.makeText(context, "Exported CSV copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Export CSV")
            }

            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        val json = viewModel.exportDiagnosticsJson()
                        clipboardManager.setText(AnnotatedString(json))
                        Toast.makeText(context, "Exported JSON copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Code, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Export JSON")
            }
        }

        Divider()

        // Telemetry Logs Feed
        Text(text = "Telemetry Event Logs (${logs.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        if (logs.isEmpty()) {
            Text("No diagnostic events recorded yet.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        } else {
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            for (log in logs.take(20)) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = log.eventType, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(text = timeFmt.format(Date(log.timestamp)), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Text(text = log.details, style = MaterialTheme.typography.bodySmall)
                        if (log.latencyMs > 0 || log.bytesTransferred > 0) {
                            Text(
                                text = "Latency: ${log.latencyMs}ms • Bytes: ${log.bytesTransferred} • Transport: ${log.transportType}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
