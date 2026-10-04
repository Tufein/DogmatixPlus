package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueSummaryTest {
    private fun row(name: String, status: DownloadStatus) =
        DownloadItemModel(name = name, fileName = name, downloadSpeed = 0f, progress = 0f, fileSize = 1L, status = status)

    @Test fun summarisesARunOnceTheLastDownloadIsDone() {
        val s = QueueSummary()
        assertNull(s.update(listOf(row("a", DownloadStatus.DOWNLOADING), row("b", DownloadStatus.DOWNLOADING), row("c", DownloadStatus.DOWNLOADING))))
        assertNull(s.update(listOf(row("a", DownloadStatus.COMPLETED), row("b", DownloadStatus.DOWNLOADING), row("c", DownloadStatus.FAILED))))
        val done = s.update(listOf(row("a", DownloadStatus.COMPLETED), row("b", DownloadStatus.COMPLETED), row("c", DownloadStatus.FAILED)))
        assertEquals(QueueSummary.Summary(completed = 2, failed = 1, stopped = 0), done)
        // Told once.
        assertNull(s.update(listOf(row("a", DownloadStatus.COMPLETED), row("b", DownloadStatus.COMPLETED), row("c", DownloadStatus.FAILED))))
    }

    @Test fun downloadsAddedDuringTheRunCountAndOldRowsDoNot() {
        val s = QueueSummary()
        val old = row("old", DownloadStatus.COMPLETED)
        s.update(listOf(old, row("a", DownloadStatus.DOWNLOADING)))
        s.update(listOf(old, row("a", DownloadStatus.COMPLETED), row("b", DownloadStatus.QUEUED)))
        val done = s.update(listOf(old, row("a", DownloadStatus.COMPLETED), row("b", DownloadStatus.STOPPED)))
        assertEquals(QueueSummary.Summary(completed = 1, failed = 0, stopped = 1), done)
    }

    @Test fun aSingleDownloadOrAPausedRunGivesNoSummaryUntilItEnds() {
        val s = QueueSummary()
        s.update(listOf(row("a", DownloadStatus.DOWNLOADING)))
        assertNull(s.update(listOf(row("a", DownloadStatus.COMPLETED))))
        s.update(listOf(row("x", DownloadStatus.DOWNLOADING), row("y", DownloadStatus.DOWNLOADING)))
        // Paused rows do not keep the run open; it ends with them counted as stopped.
        assertEquals(QueueSummary.Summary(1, 0, 1), s.update(listOf(row("x", DownloadStatus.PAUSED), row("y", DownloadStatus.COMPLETED))))
    }

    @Test fun rowsRemovedDuringTheRunAreLeftOut() {
        val s = QueueSummary()
        s.update(listOf(row("a", DownloadStatus.DOWNLOADING), row("b", DownloadStatus.DOWNLOADING), row("c", DownloadStatus.DOWNLOADING)))
        assertEquals(QueueSummary.Summary(2, 0, 0), s.update(listOf(row("a", DownloadStatus.COMPLETED), row("c", DownloadStatus.COMPLETED))))
    }
}
