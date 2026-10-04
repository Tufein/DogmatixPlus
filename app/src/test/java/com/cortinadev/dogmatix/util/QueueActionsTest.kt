package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
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
}
