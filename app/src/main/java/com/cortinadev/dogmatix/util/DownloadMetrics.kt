package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus

/** Transfer estimates exclude copying and extraction: their duration is not a network ETA. */
object DownloadMetrics {
    private const val MIB = 1_048_576.0

    data class Metrics(
        val remainingBytes: Long?,
        val bytesPerSecond: Long,
        val etaSeconds: Long?
    )

    fun of(item: DownloadItemModel): Metrics {
        val remaining = if (item.fileSize > 0L) {
            item.fileSize - item.downloadedBytes.coerceIn(0L, item.fileSize)
        } else null
        val speed = if (item.status == DownloadStatus.DOWNLOADING) bytesPerSecond(item.downloadSpeed) else 0L
        val eta = if (remaining != null && remaining > 0L && speed > 0L) ceilDivide(remaining, speed) else null
        return Metrics(remaining, speed, eta)
    }

    fun bytesPerSecond(mibPerSecond: Float): Long =
        if (mibPerSecond.isFinite() && mibPerSecond > 0f) (mibPerSecond.toDouble() * MIB).toLong() else 0L

    internal fun ceilDivide(value: Long, divisor: Long): Long =
        value / divisor + if (value % divisor == 0L) 0L else 1L

    internal fun addSaturated(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}
