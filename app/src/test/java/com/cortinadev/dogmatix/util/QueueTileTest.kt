package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueTileTest {
    private fun item(status: DownloadStatus, name: String = status.name) = DownloadItemModel(
        name = name, fileName = "$name.zip", downloadSpeed = 0f, progress = 0f, fileSize = 100, status = status
    )

    @Test fun `an empty queue is idle and a tap opens the downloads`() {
        val s = QueueTile.state(emptyList(), held = false)
        assertEquals(QueueTile.State(QueueTileMode.IDLE, 0), s)
        assertEquals(QueueTileTap.OPEN_DOWNLOADS, QueueTile.tap(s))
    }

    @Test fun `running and waiting downloads make it active and count in the queue`() {
        val list = listOf(item(DownloadStatus.DOWNLOADING, "a"), item(DownloadStatus.QUEUED, "b"), item(DownloadStatus.UNZIPPING, "c"))
        val s = QueueTile.state(list, held = false)
        assertEquals(QueueTile.State(QueueTileMode.ACTIVE, 3), s)
        assertEquals(QueueTileTap.HOLD, QueueTile.tap(s))
    }

    @Test fun `finished and paused downloads are not in the queue`() {
        val list = listOf(
            item(DownloadStatus.COMPLETED, "a"), item(DownloadStatus.FAILED, "b"), item(DownloadStatus.STOPPED, "c"),
            item(DownloadStatus.PAUSED, "d"), item(DownloadStatus.QUEUED, "e")
        )
        assertEquals(QueueTile.State(QueueTileMode.ACTIVE, 1), QueueTile.state(list, held = false))
        assertEquals(QueueTile.State(QueueTileMode.IDLE, 0), QueueTile.state(list.dropLast(1), held = false))
    }

    @Test fun `a held queue is paused whatever is in it, and a tap resumes`() {
        val held = QueueTile.state(listOf(item(DownloadStatus.QUEUED, "a"), item(DownloadStatus.QUEUED, "b")), held = true)
        assertEquals(QueueTile.State(QueueTileMode.PAUSED, 2), held)
        assertEquals(QueueTileTap.RESUME, QueueTile.tap(held))
        val heldEmpty = QueueTile.state(emptyList(), held = true)
        assertEquals(QueueTile.State(QueueTileMode.PAUSED, 0), heldEmpty)
        assertEquals(QueueTileTap.RESUME, QueueTile.tap(heldEmpty))
    }
}
