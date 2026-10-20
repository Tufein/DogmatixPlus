package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentProgressTest {
    @Test fun finishingOneSiblingDoesNotCompleteAnotherSelectedFile() {
        val verified = longArrayOf(100L, 25L, 0L)
        assertEquals(DownloadStatus.COPYING,
            TorrentProgress.statusUpdate(DownloadStatus.DOWNLOADING, TorrentProgress.fileBytes(0, verified.size) { verified[it] }!!, 100L))
        assertNull(TorrentProgress.statusUpdate(DownloadStatus.DOWNLOADING, TorrentProgress.fileBytes(1, verified.size) { verified[it] }!!, 100L))
        assertNull(TorrentProgress.statusUpdate(DownloadStatus.DOWNLOADING, TorrentProgress.fileBytes(2, verified.size) { verified[it] }!!, 100L))
    }

    @Test fun lateTorrentFinishCannotEndCopyOrExtraction() {
        for (status in listOf(DownloadStatus.COPYING, DownloadStatus.UNZIPPING)) {
            assertFalse(TorrentProgress.acceptsUpdates(status))
            assertNull(TorrentProgress.statusUpdate(status, 100L, 100L))
            assertNull(TorrentProgress.statusUpdate(status, 20L, 100L))
        }
    }

    @Test fun latePollingCannotResurrectFailedStoppedPausedOrFinishedRows() {
        for (status in listOf(DownloadStatus.FAILED, DownloadStatus.STOPPED, DownloadStatus.PAUSED, DownloadStatus.COMPLETED)) {
            assertFalse(TorrentProgress.acceptsUpdates(status))
            assertNull(TorrentProgress.statusUpdate(status, 100L, 100L))
            assertNull(TorrentProgress.statusUpdate(status, 20L, 100L))
        }
        assertNull(TorrentProgress.statusUpdate(null, 100L, 100L))
    }

    @Test fun missingPerFileProgressNeverFallsBackToOverallTorrentCompletion() {
        assertNull(TorrentProgress.fileBytes(1, 1) { error("Must not read an invalid index") })
        assertNull(TorrentProgress.fileBytes(-1, 1) { error("Must not read an invalid index") })
        assertNull(TorrentProgress.fileBytes(0, 1) { -1L })
        assertNull(TorrentProgress.statusUpdate(DownloadStatus.DOWNLOADING, -1L, 100L))
        assertNull(TorrentProgress.statusUpdate(DownloadStatus.DOWNLOADING, 100L, 0L))
    }

    @Test fun largeCollectionsOnlyReadTheSelectedFileFromTheNativeSnapshot() {
        val readIndices = ArrayList<Int>()
        val bytes = TorrentProgress.fileBytes(123_456, 1_000_000) { index ->
            readIndices += index
            75L
        }
        assertEquals(75L, bytes)
        assertEquals(listOf(123_456), readIndices)
    }

    @Test fun queuedTransferBecomesDownloadingUntilItsOwnBytesAreVerified() {
        assertEquals(DownloadStatus.DOWNLOADING, TorrentProgress.statusUpdate(DownloadStatus.QUEUED, 50L, 100L))
        assertEquals(DownloadStatus.COPYING, TorrentProgress.statusUpdate(DownloadStatus.QUEUED, 100L, 100L))
    }

    @Test fun verifiedTorrentTransfersOnlyIncreaseQueueCompletionAfterLocalProcessing() {
        val queue = (1..2).map { index ->
            DownloadItemModel(name = "game-$index", fileName = "game-$index", downloadSpeed = 0f,
                progress = 0.5f, fileSize = 100L, downloadedBytes = 50L, status = DownloadStatus.DOWNLOADING)
        }.toMutableList()
        assertEquals(0, QueueProgress.of(queue).percent)

        queue.indices.forEach { index ->
            val transferStatus = TorrentProgress.statusUpdate(queue[index].status, 100L, 100L)
            assertEquals(DownloadStatus.COPYING, transferStatus)
            queue[index] = queue[index].copy(status = transferStatus!!, progress = 1f, downloadedBytes = 100L)
            val copying = QueueProgress.of(queue)
            assertEquals(index, copying.completed)
            assertEquals(index * 50, copying.percent)
            assertTrue(copying.busy)
            assertFalse(copying.allCompleted)
            assertNull(TorrentProgress.statusUpdate(queue[index].status, 100L, 100L))

            if (index == 1) {
                queue[index] = queue[index].copy(status = DownloadStatus.UNZIPPING)
                val extracting = QueueProgress.of(queue)
                assertEquals(index, extracting.completed)
                assertEquals(index * 50, extracting.percent)
                assertTrue(extracting.busy)
                assertNull(TorrentProgress.statusUpdate(queue[index].status, 100L, 100L))
            }

            queue[index] = queue[index].copy(status = DownloadStatus.COMPLETED)
            val finished = QueueProgress.of(queue)
            assertEquals(index + 1, finished.completed)
            assertEquals((index + 1) * 50, finished.percent)
        }

        assertTrue(QueueProgress.of(queue).allCompleted)
        assertFalse(QueueProgress.of(queue).busy)
    }
}
