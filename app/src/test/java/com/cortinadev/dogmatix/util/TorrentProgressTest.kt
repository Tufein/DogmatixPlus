package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentProgressTest {
    @Test fun finishingOneSiblingDoesNotCompleteAnotherSelectedFile() {
        val verified = longArrayOf(100L, 25L, 0L)
        assertEquals(DownloadStatus.COMPLETED,
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
        assertEquals(DownloadStatus.COMPLETED, TorrentProgress.statusUpdate(DownloadStatus.QUEUED, 100L, 100L))
    }
}
