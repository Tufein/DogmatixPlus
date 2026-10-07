package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus

/**
 * Progress of running downloads, gathered between two list updates. Every running download used
 * to publish a new copy of the whole list once a second; with a queue of hundreds that meant
 * several full copies per second, and every screen watching the list redrawing each time. Now
 * progress is collected here and applied to the list in one pass. Pure JVM for the tests.
 */
object ProgressBatch {
    data class Progress(val progress: Float, val speed: Float, val downloadedBytes: Long)

    /** [list] with [batch] applied; the same instance when no row is in [batch]. */
    fun apply(list: List<DownloadItemModel>, batch: Map<String, Progress>): List<DownloadItemModel> {
        if (batch.isEmpty()) return list
        var changed = false
        val out = list.map { item ->
            val p = batch[item.fileName] ?: return@map item
            changed = true
            item.copy(
                progress = p.progress,
                downloadSpeed = if (item.status == DownloadStatus.DOWNLOADING) p.speed else 0f,
                downloadedBytes = p.downloadedBytes
            )
        }
        return if (changed) out else list
    }
}
