package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueProgressTest {
    private fun row(status: DownloadStatus, size: Long = 100L, done: Long = 0L, name: String = "game") =
        DownloadItemModel(name = name, fileName = name, downloadSpeed = 0f, progress = 0f, fileSize = size, downloadedBytes = done, status = status)

    @Test fun `finishing queued downloads accumulates from zero to one hundred percent`() {
        val queue = (1..3).map { row(DownloadStatus.QUEUED, name = "game-$it") }.toMutableList()
        var previousFraction = QueueProgress.of(queue).fraction
        assertEquals(0f, previousFraction, 0f)

        queue.indices.forEach { index ->
            listOf(DownloadStatus.DOWNLOADING, DownloadStatus.COPYING, DownloadStatus.UNZIPPING).forEach { status ->
                queue[index] = queue[index].copy(status = status, downloadedBytes = 100L, progress = 1f)
                val summary = QueueProgress.of(queue)
                assertEquals(3, summary.total)
                assertEquals(index / 3f, summary.fraction, 0.0001f)
                assertEquals(listOf(0, 33, 66)[index], summary.percent)
                assertTrue(summary.busy)
                assertFalse(summary.allCompleted)
                assertTrue(summary.fraction >= previousFraction)
                previousFraction = summary.fraction
            }

            queue[index] = queue[index].copy(status = DownloadStatus.COMPLETED)
            val summary = QueueProgress.of(queue)
            assertEquals(index + 1, summary.completed)
            assertEquals(3, summary.total)
            assertEquals((index + 1) / 3f, summary.fraction, 0.0001f)
            assertEquals(listOf(33, 66, 100)[index], summary.percent)
            assertTrue(summary.fraction >= previousFraction)
            previousFraction = summary.fraction
        }

        val finished = QueueProgress.of(queue)
        assertEquals(1f, finished.fraction, 0f)
        assertTrue(finished.allCompleted)
        assertFalse(finished.busy)
    }

    @Test fun `download sizes and transfer counters do not determine completed share`() {
        val s = QueueProgress.of(listOf(
            row(DownloadStatus.COMPLETED, size = 1_000_000_000L, name = "large"),
            row(DownloadStatus.COMPLETED, size = 0L, name = "unknown-complete"),
            row(DownloadStatus.DOWNLOADING, size = 1L, done = 99L, name = "small-transfer"),
            row(DownloadStatus.UNZIPPING, size = 0L, done = 500L, name = "unknown-extract")
        ))
        assertEquals(0.5f, s.fraction, 0f)
        assertEquals(50, s.percent)
        assertEquals(2, s.completed)
        assertEquals(4, s.total)
        assertTrue(s.busy)
        assertFalse(s.allCompleted)
    }

    @Test fun `failed stopped and paused downloads still need completion`() {
        val s = QueueProgress.of(listOf(
            row(DownloadStatus.COMPLETED, name = "done"),
            row(DownloadStatus.FAILED, name = "failed"),
            row(DownloadStatus.STOPPED, name = "stopped"),
            row(DownloadStatus.PAUSED, done = 40L, name = "paused")
        ))
        assertEquals(0.25f, s.fraction, 0f)
        assertEquals(25, s.percent)
        assertEquals(4, s.total)
        assertEquals(1, s.failed)
        assertEquals(0, s.active)
        assertFalse(s.busy)
        assertFalse(s.allCompleted)
    }

    @Test fun `copying and extracting unknown sized downloads keep the queue busy`() {
        val s = QueueProgress.of(listOf(
            row(DownloadStatus.COPYING, size = 0L, name = "copy"),
            row(DownloadStatus.UNZIPPING, size = 0L, name = "extract")
        ))
        assertEquals(0f, s.fraction, 0f)
        assertEquals(0, s.active)
        assertEquals(2, s.processing)
        assertTrue(s.busy)
        assertFalse(s.allCompleted)
    }

    @Test fun `completed queue stays at one hundred percent while idle`() {
        val s = QueueProgress.of(listOf(
            row(DownloadStatus.COMPLETED, size = 0L, name = "unknown"),
            row(DownloadStatus.COMPLETED, size = 10L, done = 0L, name = "known")
        ))
        assertEquals(1f, s.fraction, 0f)
        assertEquals(100, s.percent)
        assertTrue(s.allCompleted)
        assertFalse(s.busy)
    }

    @Test fun `adding or removing queue rows explicitly changes the measured queue`() {
        val completed = row(DownloadStatus.COMPLETED, name = "done")
        val first = row(DownloadStatus.QUEUED, name = "first")
        val second = row(DownloadStatus.QUEUED, name = "second")
        assertEquals(0.5f, QueueProgress.of(listOf(completed, first)).fraction, 0f)
        assertEquals(1 / 3f, QueueProgress.of(listOf(completed, first, second)).fraction, 0.0001f)
        assertEquals(0f, QueueProgress.of(listOf(first, second)).fraction, 0f)
    }

    @Test fun `empty queue is idle`() {
        val s = QueueProgress.of(emptyList())
        assertEquals(0, s.total)
        assertEquals(0f, s.fraction, 0f)
        assertEquals(0, s.percent)
        assertFalse(s.busy)
        assertFalse(s.allCompleted)
    }
}
