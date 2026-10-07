package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus

/**
 * The download queue boiled down to one summary, for the places that only have room for a single
 * progress line: the home-screen widget and the second screen's dashboard. Running means queued,
 * downloading, copying or unpacking; paused and finished downloads do not count. Pure JVM for
 * the tests.
 */
object QueueGlance {

    /**
     * @property active number of running downloads.
     * @property percent overall progress, 0..100, weighted by file size.
     * @property fraction the same as 0..1.
     * @property bytesPerSecond what the transferring downloads reach together right now.
     * @property remainingBytes still to fetch.
     * @property etaSeconds time left at that speed, null while nothing transfers.
     */
    data class Glance(
        val active: Int,
        val percent: Int,
        val fraction: Float,
        val bytesPerSecond: Long,
        val remainingBytes: Long,
        val etaSeconds: Long?
    )

    val Idle = Glance(active = 0, percent = 0, fraction = 0f, bytesPerSecond = 0L, remainingBytes = 0L, etaSeconds = null)

    /** True for the downloads that count as running (the same rule the widget and the dashboard share). */
    fun isRunning(item: DownloadItemModel): Boolean = !item.isFinished && item.status != DownloadStatus.PAUSED

    fun of(list: List<DownloadItemModel>): Glance {
        val running = list.filter { isRunning(it) }
        if (running.isEmpty()) return Idle
        val total = running.fold(0L) { sum, item -> DownloadMetrics.addSaturated(sum, item.fileSize.coerceAtLeast(0L)) }
        val done = running.fold(0L) { sum, item ->
            DownloadMetrics.addSaturated(sum, item.downloadedBytes.coerceIn(0L, item.fileSize.coerceAtLeast(0L)))
        }
        // Without sizes (a torrent still fetching its metadata) the average of the progress values has to do.
        val percent = if (total > 0) {
            (done.coerceIn(0L, total).toDouble() * 100 / total).toInt()
        } else {
            (running.map { it.progress.coerceIn(0f, 1f) }.average() * 100).toInt().coerceIn(0, 100)
        }
        val eta = QueueEta.ofDownloads(running)
        return Glance(
            active = running.size,
            percent = percent,
            fraction = percent / 100f,
            bytesPerSecond = eta.bytesPerSecond,
            remainingBytes = eta.remainingBytes,
            etaSeconds = eta.seconds
        )
    }
}
