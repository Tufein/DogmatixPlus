package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueActionsTest {
    private fun item(name: String, status: DownloadStatus) =
        DownloadItemModel(name = name, fileName = "$name.zip", downloadSpeed = 0f, progress = 0f, fileSize = 1L, status = status)

    private val list = listOf(
        item("a", DownloadStatus.QUEUED), item("b", DownloadStatus.DOWNLOADING), item("c", DownloadStatus.UNZIPPING),
        item("d", DownloadStatus.FAILED), item("e", DownloadStatus.STOPPED), item("f", DownloadStatus.COMPLETED),
        item("g", DownloadStatus.PAUSED), item("h", DownloadStatus.COPYING)
    )

    @Test fun countsPerAction() {
        val c = QueueActions.counts(list)
        assertEquals(3, c.stoppable)
        assertEquals(2, c.retryable)
        assertEquals(1, c.clearable)
        assertTrue(c.any)
    }

    @Test fun pausedAndCopyingRowsAreLeftAlone() {
        assertEquals(listOf("a.zip", "b.zip", "c.zip"), QueueActions.stoppable(list))
        assertEquals(listOf("d.zip", "e.zip"), QueueActions.retryable(list))
        assertEquals(listOf("f.zip"), QueueActions.clearable(list))
    }

    @Test fun emptyQueueHasNothingToDo() {
        assertFalse(QueueActions.counts(emptyList()).any)
    }

    @Test fun webDownloadsPauseWhileQueuedTorrentsOnlyWhileTransferring() {
        assertTrue(QueueActions.canPause(DownloadStatus.DOWNLOADING, isTorrent = false))
        assertTrue(QueueActions.canPause(DownloadStatus.QUEUED, isTorrent = false))
        assertTrue(QueueActions.canPause(DownloadStatus.DOWNLOADING, isTorrent = true))
        assertFalse(QueueActions.canPause(DownloadStatus.QUEUED, isTorrent = true))
        assertFalse(QueueActions.canPause(DownloadStatus.UNZIPPING, isTorrent = false))
        assertFalse(QueueActions.canPause(DownloadStatus.COPYING, isTorrent = false))
        assertFalse(QueueActions.canPause(DownloadStatus.PAUSED, isTorrent = false))
    }

    @Test fun pauseAllAndResumeAllActOnTheRightRows() {
        assertEquals(listOf("a.zip", "b.zip"), QueueActions.pausable(list))
        assertEquals(listOf("g.zip"), QueueActions.resumable(list))
        val c = QueueActions.counts(list)
        assertEquals(2, c.pausable)
        assertEquals(1, c.resumable)
    }

    @Test fun aParkedQueueStillShowsItsButtons() {
        assertTrue(QueueActions.counts(listOf(item("g", DownloadStatus.PAUSED))).any)
    }

    @Test fun pauseAllCountSkipsRowsThatCannotPause() {
        val c = QueueActions.counts(list) { it.fileName != "a.zip" }
        assertEquals(1, c.pausable)
        assertEquals(listOf("b.zip"), QueueActions.pausable(list) { it.fileName != "a.zip" })
    }
}
