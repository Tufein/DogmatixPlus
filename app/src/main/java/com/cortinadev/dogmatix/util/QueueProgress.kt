package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus

/**
 * The Downloads header in numbers (5.0 queue card): how far the downloads still in progress are,
 * as bytes done of their total (the queue ring), and how many rows are in each state (the pills).
 * Pure JVM for the tests.
 */
object QueueProgress {

    data class Summary(
        /** Queued or downloading: the same count as the tab badge. */
        val active: Int = 0,
        val completed: Int = 0,
        val failed: Int = 0,
        /** Bytes fetched of the rows still in progress (queued, downloading, copying, unpacking). */
        val doneBytes: Long = 0L,
        /** Size of the rows still in progress; 0 when nothing is in progress or the sizes are unknown. */
        val totalBytes: Long = 0L
    ) {
        /** 0..1 share of [totalBytes] already fetched; 0 when there is nothing to measure. */
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f else (doneBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)

        /** Something is still queued, transferring or being unpacked. */
        val busy: Boolean get() = active > 0 || totalBytes > 0L
    }

    /**
     * Copying and unpacking rows count as fully fetched (their bytes are on the device); paused,
     * stopped, failed and finished rows are not part of the ring.
     */
    fun of(list: List<DownloadItemModel>): Summary {
        var active = 0
        var completed = 0
        var failed = 0
        var done = 0L
        var total = 0L
        for (item in list) {
            val size = item.fileSize.coerceAtLeast(0L)
            when (item.status) {
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> {
                    active++
                    total += size
                    done += item.downloadedBytes.coerceIn(0L, size)
                }
                DownloadStatus.COPYING, DownloadStatus.UNZIPPING -> {
                    total += size
                    done += size
                }
                DownloadStatus.COMPLETED -> completed++
                DownloadStatus.FAILED -> failed++
                DownloadStatus.STOPPED, DownloadStatus.PAUSED -> Unit
            }
        }
        return Summary(active, completed, failed, done, total)
    }
}
