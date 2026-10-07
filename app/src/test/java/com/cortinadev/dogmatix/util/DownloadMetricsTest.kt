package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadMetricsTest {
    private fun item(
        status: DownloadStatus = DownloadStatus.DOWNLOADING,
        size: Long = 10_485_760L,
        done: Long = 0L,
        speed: Float = 2f
    ) = DownloadItemModel("Game", "game.zip", speed, 0f, size, done, status)

    @Test fun `per file time rounds up while preserving binary speed units`() {
        val metrics = DownloadMetrics.of(item(done = 1L))
        assertEquals(10_485_759L, metrics.remainingBytes)
        assertEquals(2_097_152L, metrics.bytesPerSecond)
        assertEquals(5L, metrics.etaSeconds)
    }

    @Test fun `unknown content length never becomes zero remaining or an ETA`() {
        val metrics = DownloadMetrics.of(item(size = 0L, done = 4_096L))
        assertNull(metrics.remainingBytes)
        assertNull(metrics.etaSeconds)
        assertEquals(2_097_152L, metrics.bytesPerSecond)
    }

    @Test fun `waiting paused finished and post processing have no transfer speed or ETA`() {
        DownloadStatus.entries.filter { it != DownloadStatus.DOWNLOADING }.forEach { status ->
            val metrics = DownloadMetrics.of(item(status))
            assertEquals(0L, metrics.bytesPerSecond)
            assertNull(metrics.etaSeconds)
        }
    }

    @Test fun `complete and malformed byte counters do not yield negative remaining`() {
        assertEquals(0L, DownloadMetrics.of(item(done = Long.MAX_VALUE)).remainingBytes)
        assertNull(DownloadMetrics.of(item(done = Long.MAX_VALUE)).etaSeconds)
        assertEquals(10_485_760L, DownloadMetrics.of(item(done = Long.MIN_VALUE)).remainingBytes)
    }

    @Test fun `invalid speed is unknown rather than an infinite ETA`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 0f).forEach { speed ->
            val metrics = DownloadMetrics.of(item(speed = speed))
            assertEquals(0L, metrics.bytesPerSecond)
            assertNull(metrics.etaSeconds)
        }
    }
}
