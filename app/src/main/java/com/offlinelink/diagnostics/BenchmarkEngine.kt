package com.offlinelink.diagnostics

import android.content.Context
import com.google.gson.GsonBuilder
import com.offlinelink.data.DiagnosticLogEntity
import com.offlinelink.data.OfflineLinkRepository
import com.offlinelink.protocol.FrameCodec
import com.offlinelink.protocol.MessageEnvelope
import com.offlinelink.protocol.MessageType
import com.offlinelink.protocol.PingPayload
import com.offlinelink.protocol.JsonUtil
import com.offlinelink.transport.HybridTransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class BenchmarkResult(
    val peerId: String,
    val payloadSizeBytes: Int,
    val totalTrials: Int,
    val successfulTrials: Int,
    val minLatencyMs: Long,
    val maxLatencyMs: Long,
    val avgLatencyMs: Double,
    val throughputBytesPerSec: Double,
    val timestamp: Long = System.currentTimeMillis()
)

class BenchmarkEngine(
    private val transportManager: HybridTransportManager,
    private val repository: OfflineLinkRepository,
    private val myDeviceId: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _isBenchmarkRunning = MutableStateFlow(false)
    val isBenchmarkRunning: StateFlow<Boolean> = _isBenchmarkRunning.asStateFlow()

    private val _lastBenchmarkResult = MutableStateFlow<BenchmarkResult?>(null)
    val lastBenchmarkResult: StateFlow<BenchmarkResult?> = _lastBenchmarkResult.asStateFlow()

    fun runBenchmark(
        peerId: String,
        payloadSize: Int = 1024,
        trialCount: Int = 10,
        onProgress: ((Int, Int) -> Unit)? = null
    ) {
        if (_isBenchmarkRunning.value) return

        scope.launch {
            _lastBenchmarkResult.value = null
            _isBenchmarkRunning.value = true
            val latencies = mutableListOf<Long>()
            var successCount = 0
            val dummyBytes = ByteArray(payloadSize) { 0x41 } // 'A'

            for (i in 1..trialCount) {
                if (!transportManager.isPeerConnected(peerId)) {
                    android.util.Log.w("BenchmarkEngine", "Benchmark aborted: Peer $peerId disconnected")
                    break
                }
                onProgress?.invoke(i, trialCount)
                val trialStart = System.currentTimeMillis()

                val envelope = MessageEnvelope(
                    type = MessageType.PING,
                    messageId = UUID.randomUUID().toString(),
                    senderId = myDeviceId,
                    recipientId = peerId,
                    payloadLength = payloadSize,
                    encryptedPayloadBase64 = android.util.Base64.encodeToString(dummyBytes, android.util.Base64.NO_WRAP)
                )

                val frameBytes = FrameCodec.encodeFrame(envelope.toByteArray())
                val sent = transportManager.sendFrameToPeer(peerId, frameBytes, payloadSize)

                if (sent) {
                    successCount++
                    val duration = System.currentTimeMillis() - trialStart
                    latencies.add(duration)
                }

                delay(100) // 100ms interval between bursts
            }

            val activeDurationMs = latencies.sum().coerceAtLeast(1)
            val totalBytesTransferred = successCount.toLong() * payloadSize
            val throughputBps = if (successCount > 0) {
                (totalBytesTransferred.toDouble() / activeDurationMs) * 1000.0
            } else 0.0

            val minLat = latencies.minOrNull() ?: 0L
            val maxLat = latencies.maxOrNull() ?: 0L
            val avgLat = if (latencies.isNotEmpty()) latencies.average() else 0.0

            val result = BenchmarkResult(
                peerId = peerId,
                payloadSizeBytes = payloadSize,
                totalTrials = trialCount,
                successfulTrials = successCount,
                minLatencyMs = minLat,
                maxLatencyMs = maxLat,
                avgLatencyMs = avgLat,
                throughputBytesPerSec = throughputBps
            )

            _lastBenchmarkResult.value = result
            _isBenchmarkRunning.value = false

            repository.recordDiagnostic(
                eventType = "TRANSFER_BENCHMARK",
                transportType = "HYBRID",
                details = "Benchmark to $peerId: $successCount/$trialCount trials OK, Avg: %.1fms, Throughput: %.1f B/s".format(avgLat, throughputBps),
                latencyMs = avgLat.toLong(),
                bytesTransferred = totalBytesTransferred,
                peerId = peerId
            )
        }
    }

    suspend fun exportDiagnosticsAsJson(): String {
        val logs = repository.getAllDiagnostics()
        val gson = GsonBuilder().setPrettyPrinting().create()
        return gson.toJson(logs)
    }

    suspend fun exportDiagnosticsAsCsv(): String {
        val logs = repository.getAllDiagnostics()
        val sb = StringBuilder()
        sb.append("ID,Timestamp,EventType,TransportType,PeerID,LatencyMs,BytesTransferred,Details\n")
        for (log in logs) {
            val cleanDetails = log.details.replace(",", ";").replace("\n", " ")
            sb.append("${log.id},${log.timestamp},${log.eventType},${log.transportType},${log.peerId ?: ""},${log.latencyMs},${log.bytesTransferred},\"$cleanDetails\"\n")
        }
        return sb.toString()
    }
}
