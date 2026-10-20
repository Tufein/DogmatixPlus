package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus

/**
 * The Downloads header in numbers: completed downloads out of the entire list (the queue ring),
 * and how many rows are in each state (the pills). Completion keeps a row in both counts until
 * the user removes it; transferring, copying and unpacking are not successful completion.
 * Pure JVM for the tests.
 */
object QueueProgress {

    data class Summary(
        /** Queued or downloading: the same count as the tab badge. */
        val active: Int = 0,
        val completed: Int = 0,
        val failed: Int = 0,
        /** Every download in the list, including completed, failed, stopped and paused rows. */
        val total: Int = 0,
        /** Downloads whose transferred files are still being copied or unpacked. */
        val processing: Int = 0
    ) {
        /** 0..1 share of downloads successfully completed, independent of their file sizes. */
        val fraction: Float
            get() = if (total <= 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)

        val percent: Int
            get() = if (total <= 0) 0 else ((completed.toLong() * 100L) / total).toInt().coerceIn(0, 100)

        val allCompleted: Boolean get() = total > 0 && completed == total

        /** Something is still queued, transferring or being unpacked. */
        val busy: Boolean get() = active > 0 || processing > 0
    }

    /**
     * Every row has equal weight. Only COMPLETED contributes to the ring, so finishing one file
     * never removes its contribution or shrinks the denominator. Explicitly adding or removing
     * rows changes the scope of the total; retries of unfinished rows keep their place in it.
     */
    fun of(list: List<DownloadItemModel>): Summary {
        var active = 0
        var completed = 0
        var failed = 0
        var processing = 0
        for (item in list) {
            when (item.status) {
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> active++
                DownloadStatus.COPYING, DownloadStatus.UNZIPPING -> processing++
                DownloadStatus.COMPLETED -> completed++
                DownloadStatus.FAILED -> failed++
                DownloadStatus.STOPPED, DownloadStatus.PAUSED -> Unit
            }
        }
        return Summary(active, completed, failed, list.size, processing)
    }
}
