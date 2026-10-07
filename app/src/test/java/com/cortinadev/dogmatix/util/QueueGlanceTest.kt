package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueGlanceTest {
    private val mib = 1_048_576L

    private fun item(
        status: DownloadStatus = DownloadStatus.DOWNLOADING,
        size: Long = 100 * mib,
        done: Long = 0,
        speed: Float = 0f,
        progress: Float = 0f
    ) = DownloadItemModel(
        name = "Game", fileName = "game.zip", downloadSpeed = speed, progress = progress,
        fileSize = size, downloadedBytes = done, status = status
    )

    @Test fun `an empty queue is idle`() {
        assertEquals(QueueGlance.Idle, QueueGlance.of(emptyList()))
        assertEquals(0, QueueGlance.of(emptyList()).active)
        assertNull(QueueGlance.of(emptyList()).etaSeconds)
    }

    @Test fun `paused and finished downloads do not count`() {
        val glance = QueueGlance.of(listOf(
            item(DownloadStatus.PAUSED, done = 50 * mib),
            item(DownloadStatus.COMPLETED, done = 100 * mib),
            item(DownloadStatus.FAILED),
            item(DownloadStatus.STOPPED)
        ))
        assertEquals(QueueGlance.Idle, glance)
    }

    @Test fun `percent is weighted by size`() {
        // 90 of 100 MiB and 0 of 300 MiB: 90 of 400, not the average of 90 % and 0 %.
        val glance = QueueGlance.of(listOf(
            item(size = 100 * mib, done = 90 * mib),
            item(size = 300 * mib, done = 0)
        ))
        assertEquals(2, glance.active)
        assertEquals(22, glance.percent)
        assertEquals(0.22f, glance.fraction, 0.0001f)
        assertEquals(310 * mib, glance.remainingBytes)
    }

    @Test fun `without sizes the progress values are averaged`() {
        val glance = QueueGlance.of(listOf(
            item(size = 0, progress = 0.5f),
            item(size = 0, progress = 1f)
        ))
        assertEquals(75, glance.percent)
    }

    @Test fun `time left comes from the transferring downloads`() {
        val glance = QueueGlance.of(listOf(
            item(DownloadStatus.DOWNLOADING, size = 100 * mib, done = 0, speed = 2f),
            item(DownloadStatus.DOWNLOADING, size = 100 * mib, done = 0, speed = 3f),
            item(DownloadStatus.QUEUED, size = 300 * mib, done = 0)
        ))
        assertEquals(5 * mib, glance.bytesPerSecond)
        assertEquals(500 * mib, glance.remainingBytes)
        assertEquals(100L, glance.etaSeconds)
    }

    @Test fun `no time left while nothing transfers`() {
        val glance = QueueGlance.of(listOf(item(DownloadStatus.QUEUED, speed = 0f)))
        assertEquals(1, glance.active)
        assertNull(glance.etaSeconds)
    }

    @Test fun `copying and unpacking are running but add no speed`() {
        val glance = QueueGlance.of(listOf(item(DownloadStatus.COPYING, done = 100 * mib), item(DownloadStatus.UNZIPPING, done = 100 * mib)))
        assertEquals(2, glance.active)
        assertEquals(100, glance.percent)
        assertEquals(0L, glance.bytesPerSecond)
        assertTrue(QueueGlance.isRunning(item(DownloadStatus.UNZIPPING)))
    }

    @Test fun `more downloaded than the size does not pass 100`() {
        assertEquals(100, QueueGlance.of(listOf(item(size = 10 * mib, done = 20 * mib))).percent)
    }

    @Test fun `unknown sized transfer suppresses queue ETA without inflating known progress`() {
        val glance = QueueGlance.of(listOf(
            item(size = 100 * mib, done = 50 * mib, speed = 2f),
            item(size = 0L, done = 200 * mib, speed = 1f)
        ))
        assertEquals(50, glance.percent)
        assertEquals(3 * mib, glance.bytesPerSecond)
        assertNull(glance.etaSeconds)
    }

    @Test fun `one file overrun cannot count as bytes completed by a different file`() {
        val glance = QueueGlance.of(listOf(
            item(size = 100 * mib, done = 200 * mib),
            item(size = 100 * mib, done = 0L)
        ))
        assertEquals(50, glance.percent)
    }

    @Test fun `very large queues do not overflow percentage arithmetic`() {
        val glance = QueueGlance.of(listOf(item(size = Long.MAX_VALUE, done = Long.MAX_VALUE / 2)))
        assertEquals(50, glance.percent)
    }
}
