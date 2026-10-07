package com.cortinadev.dogmatix

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.DogmatixDatabase
import com.cortinadev.dogmatix.data.model.DownloadFailure
import com.cortinadev.dogmatix.data.model.DownloadFailureCategory
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.service.DownloadProgressTracker
import com.cortinadev.dogmatix.util.DownloadMetrics
import com.cortinadev.dogmatix.util.QueueEta
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real tracker/Room regressions without a network connection or device storage writes. */
class DownloadMetricsRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun stalledBulkTransfersLoseTheirRatesAndEtasWithoutAnotherCallback() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, DogmatixDatabase::class.java).build()
        val tracker = DownloadProgressTracker(database.downloadHistoryDao())
        val rows = (0 until 500).map { item("stall-$it.zip") }
        try {
            tracker.addDownloads(rows)
            rows.forEach { tracker.updateDownloadProgress(it.fileName, 0.25f, 2f, MIB) }
            tracker.flushProgress()
            assertTrue(tracker.downloads.value.all { it.downloadSpeed > 0f })
            assertEquals(1_000L * MIB, QueueEta.ofDownloads(tracker.downloads.value).bytesPerSecond)
            withTimeout(8_000L) {
                tracker.downloads.first { list -> list.size == rows.size && list.all { it.downloadSpeed == 0f } }
            }
            assertNull(QueueEta.ofDownloads(tracker.downloads.value).seconds)
            tracker.downloads.value.forEach { row ->
                assertEquals(MIB, row.downloadedBytes)
                assertNull(DownloadMetrics.of(row).etaSeconds)
            }
        } finally {
            rows.forEach { tracker.removeDownload(it.fileName) }
            database.close()
        }
    }

    @Test fun stateChangesClearRatesAndFailureDetailsAndRetryAcceptsItsFirstSample() {
        val database = Room.inMemoryDatabaseBuilder(context, DogmatixDatabase::class.java).build()
        val tracker = DownloadProgressTracker(database.downloadHistoryDao())
        val row = item("retry-metrics.zip")
        try {
            tracker.addDownload(row)
            tracker.updateDownloadProgress(row.fileName, 0.25f, 2f, MIB)
            tracker.updateDownloadStatus(row.fileName, DownloadStatus.STOPPED, failure = DownloadFailure(DownloadFailureCategory.STORAGE_FULL))
            assertEquals(0f, tracker.downloads.value.single().downloadSpeed, 0f)
            assertEquals(DownloadFailureCategory.STORAGE_FULL, tracker.downloads.value.single().failure?.category)
            tracker.resetDownloadForRetry(row.fileName)
            assertNull(tracker.downloads.value.single().failure)
            // The old attempt's throttle and moving average must not hide or skew this sample.
            tracker.updateDownloadProgress(row.fileName, 0.5f, 1f, 2L * MIB)
            tracker.flushProgress()
            assertEquals(1f, tracker.downloads.value.single().downloadSpeed, 0f)
            tracker.updateDownloadStatus(row.fileName, DownloadStatus.COPYING)
            assertEquals(0f, tracker.downloads.value.single().downloadSpeed, 0f)
        } finally {
            tracker.removeDownload(row.fileName)
            database.close()
        }
    }

    @Test fun rejectedTorrentFailureCannotEraseAStoppedReasonOrIntroduceAnOldRate() {
        val database = Room.inMemoryDatabaseBuilder(context, DogmatixDatabase::class.java).build()
        val tracker = DownloadProgressTracker(database.downloadHistoryDao())
        val row = item("stopped-metrics.zip")
        try {
            tracker.addDownload(row)
            val stoppedReason = DownloadFailure(DownloadFailureCategory.STORAGE_FULL)
            tracker.updateDownloadStatus(row.fileName, DownloadStatus.STOPPED, failure = stoppedReason)
            tracker.updateDownloadProgress(row.fileName, 0.5f, 3f, 2L * MIB)
            tracker.updateDownloadStatus(
                row.fileName, DownloadStatus.FAILED,
                allowedFrom = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING),
                failure = DownloadFailure(DownloadFailureCategory.TORRENT)
            )
            tracker.flushProgress()
            val actual = tracker.downloads.value.single()
            assertEquals(DownloadStatus.STOPPED, actual.status)
            assertEquals(stoppedReason, actual.failure)
            assertEquals(0f, actual.downloadSpeed, 0f)
        } finally {
            tracker.removeDownload(row.fileName)
            database.close()
        }
    }

    private fun item(name: String) = DownloadItemModel(
        name = name, fileName = name, downloadSpeed = 0f, progress = 0f,
        fileSize = 4L * MIB, status = DownloadStatus.DOWNLOADING
    )

    companion object { private const val MIB = 1_048_576L }
}
