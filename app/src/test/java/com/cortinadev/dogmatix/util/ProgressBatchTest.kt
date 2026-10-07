package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProgressBatchTest {
    private fun item(name: String) =
        DownloadItemModel(name = name, fileName = name, downloadSpeed = 0f, progress = 0f, fileSize = 100L, status = DownloadStatus.DOWNLOADING)

    @Test fun appliesEveryRowOfTheBatchInOnePass() {
        val list = listOf(item("a"), item("b"), item("c"))
        val out = ProgressBatch.apply(list, mapOf("a" to ProgressBatch.Progress(0.5f, 2f, 50), "c" to ProgressBatch.Progress(0.1f, 1f, 10)))
        assertEquals(0.5f, out[0].progress)
        assertEquals(50L, out[0].downloadedBytes)
        assertEquals(2f, out[0].downloadSpeed)
        assertSame(list[1], out[1])
        assertEquals(10L, out[2].downloadedBytes)
    }

    @Test fun rowsThatAreGoneLeaveTheListAsItIs() {
        val list = listOf(item("a"))
        assertSame(list, ProgressBatch.apply(list, mapOf("gone" to ProgressBatch.Progress(1f, 0f, 100))))
        assertSame(list, ProgressBatch.apply(list, emptyMap()))
    }

    @Test fun pendingNetworkRateCannotLeakIntoPausedOrPostProcessingRows() {
        val statuses = listOf(DownloadStatus.PAUSED, DownloadStatus.COPYING, DownloadStatus.UNZIPPING, DownloadStatus.COMPLETED)
        val list = statuses.map { item(it.name).copy(status = it) }
        val out = ProgressBatch.apply(list, statuses.associate { it.name to ProgressBatch.Progress(0.5f, 2f, 50L) })
        out.forEach { row ->
            assertEquals(0f, row.downloadSpeed)
            assertEquals(50L, row.downloadedBytes)
        }
    }
}
