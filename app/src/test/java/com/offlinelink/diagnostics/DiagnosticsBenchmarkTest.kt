package com.offlinelink.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsBenchmarkTest {

    @Test
    fun testLatencyAndThroughputCalculations() {
        val latencies = listOf(15L, 22L, 18L, 30L, 15L)
        val min = latencies.minOrNull() ?: 0L
        val max = latencies.maxOrNull() ?: 0L
        val avg = latencies.average()

        assertEquals(15L, min)
        assertEquals(30L, max)
        assertEquals(20.0, avg, 0.001)

        val totalBytes = 50 * 1024L // 50 KB
        val durationMs = 500L // 0.5 seconds
        val throughputBytesPerSec = (totalBytes.toDouble() / durationMs) * 1000.0

        assertEquals(102400.0, throughputBytesPerSec, 0.01) // 100 KB/s
    }

    @Test
    fun testCsvSanitization() {
        val rawDetails = "Transfer completed, 15 chunks\nHash verified"
        val clean = rawDetails.replace(",", ";").replace("\n", " ")

        assertFalse(clean.contains("\n"))
        assertTrue(clean.contains(";"))
    }

    @Test
    fun testActiveThroughputExcludesInterTrialSleep() {
        val payloadSizeBytes = 1024 // 1 KB per trial
        val trials = 10
        val individualTrialLatencies = listOf(20L, 25L, 18L, 22L, 20L, 19L, 21L, 23L, 24L, 28L) // sum = 220ms
        val interTrialSleepMs = 100L * trials // 1000ms idle delay

        // Overall wall-clock time would be 1220ms
        val overallDurationMs = individualTrialLatencies.sum() + interTrialSleepMs
        val rawTotalBytes = trials * payloadSizeBytes

        // Skewed throughput (deflated by idle delay)
        val skewedBps = (rawTotalBytes.toDouble() / overallDurationMs) * 1000.0

        // Pure active transmission throughput (actual socket flight speed)
        val activeDurationMs = individualTrialLatencies.sum().coerceAtLeast(1)
        val activeBps = (rawTotalBytes.toDouble() / activeDurationMs) * 1000.0

        assertTrue("Active throughput must measure pure transmission speed", activeBps > skewedBps)
        assertEquals(220L, activeDurationMs)
        assertEquals(46545.45, activeBps, 0.1) // ~46.5 KB/s
    }
}
