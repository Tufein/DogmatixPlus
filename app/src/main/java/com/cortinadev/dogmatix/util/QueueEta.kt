package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus

/**
 * What is left of the download queue and roughly how long it takes: the bytes still to fetch of
 * every queued or running download, at the speed the running ones reach together right now.
 */
object QueueEta {
    /** One download: bytes still to fetch, its speed in MiB/s (as the list shows it), and whether it runs. */
    class Item(val remainingBytes: Long, val mibPerSecond: Float, val running: Boolean, val sizeKnown: Boolean = true)

    /** [seconds] is null while nothing transfers (all waiting, or the speed is not known yet). */
    data class Eta(val remainingBytes: Long, val bytesPerSecond: Long, val seconds: Long?, val unknownSizes: Int = 0)

    fun of(items: List<Item>): Eta {
        var remaining = 0L
        var speed = 0L
        var unknown = 0
        for (item in items) {
            remaining = DownloadMetrics.addSaturated(remaining, item.remainingBytes.coerceAtLeast(0L))
            if (!item.sizeKnown) unknown++
            if (item.running) speed = DownloadMetrics.addSaturated(speed, DownloadMetrics.bytesPerSecond(item.mibPerSecond))
        }
        val seconds = if (unknown == 0 && speed > 0 && remaining > 0) DownloadMetrics.ceilDivide(remaining, speed) else null
        return Eta(remaining, speed, seconds, unknown)
    }

    /** Unknown-size links and torrent metadata never produce an optimistic whole-queue ETA. */
    fun ofDownloads(items: List<DownloadItemModel>): Eta = of(items.mapNotNull { item ->
        if (item.status != DownloadStatus.DOWNLOADING && item.status != DownloadStatus.QUEUED) return@mapNotNull null
        val metrics = DownloadMetrics.of(item)
        Item(metrics.remainingBytes ?: 0L, item.downloadSpeed, item.status == DownloadStatus.DOWNLOADING, item.fileSize > 0L)
    })

    /** Whole hours and the minutes after them, rounded up to the next minute (never "0 min"). */
    fun hoursMinutes(seconds: Long): Pair<Long, Long> {
        val minutes = DownloadMetrics.ceilDivide(seconds.coerceAtLeast(0L), 60L).coerceAtLeast(1)
        return minutes / 60 to minutes % 60
    }
}
