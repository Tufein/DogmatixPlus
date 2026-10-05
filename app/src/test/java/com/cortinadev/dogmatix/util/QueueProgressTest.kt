package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueProgressTest {
    private fun row(status: DownloadStatus, size: Long = 100L, done: Long = 0L) =
        DownloadItemModel(name = "g", fileName = "g-$status-$size-$done", downloadSpeed = 0f, progress = 0f, fileSize = size, downloadedBytes = done, status = status)

    @Test fun `ring counts the bytes of what is still in progress`() {
        val s = QueueProgress.of(listOf(
            row(DownloadStatus.DOWNLOADING, size = 100, done = 50),
            row(DownloadStatus.QUEUED, size = 100, done = 0),
            row(DownloadStatus.UNZIPPING, size = 200, done = 0)
        ))
        assertEquals(250L, s.doneBytes)
        assertEquals(400L, s.totalBytes)
        assertEquals(0.625f, s.fraction, 0.0001f)
        assertEquals(2, s.active)
    }

    @Test fun `finished, failed and parked rows are counted but not measured`() {
        val s = QueueProgress.of(listOf(
            row(DownloadStatus.COMPLETED), row(DownloadStatus.COMPLETED), row(DownloadStatus.FAILED),
            row(DownloadStatus.STOPPED), row(DownloadStatus.PAUSED, done = 40)
        ))
        assertEquals(2, s.completed)
        assertEquals(1, s.failed)
        assertEquals(0, s.active)
        assertEquals(0L, s.totalBytes)
        assertEquals(0f, s.fraction, 0f)
        assertFalse(s.busy)
    }

    @Test fun `unknown sizes and overshooting counters stay in range`() {
        val s = QueueProgress.of(listOf(row(DownloadStatus.DOWNLOADING, size = 0, done = 500), row(DownloadStatus.DOWNLOADING, size = 10, done = 99)))
        assertEquals(10L, s.doneBytes)
        assertEquals(10L, s.totalBytes)
        assertEquals(1f, s.fraction, 0f)
        assertTrue(s.busy)
    }

    @Test fun `empty queue is idle`() {
        val s = QueueProgress.of(emptyList())
        assertEquals(QueueProgress.Summary(), s)
        assertFalse(s.busy)
    }
}
