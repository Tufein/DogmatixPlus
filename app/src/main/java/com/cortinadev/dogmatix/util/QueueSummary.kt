package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus

/**
 * Follows the download list and says how a run of downloads ended once the last one is done:
 * a run starts when something becomes active and ends when nothing is active any more (paused
 * downloads do not keep it open). Rows removed in between are left out. Pure JVM for the tests.
 */
class QueueSummary(private val minSize: Int = 2) {
    data class Summary(val completed: Int, val failed: Int, val stopped: Int) {
        val total: Int get() = completed + failed + stopped
    }

    private val run = LinkedHashSet<String>()

    /** Feed every new list; returns the summary when a run of at least [minSize] downloads just ended. */
    fun update(list: List<DownloadItemModel>): Summary? {
        var anyActive = false
        for (item in list) if (!item.isFinished && item.status != DownloadStatus.PAUSED) { anyActive = true; run += item.fileName }
        if (anyActive || run.isEmpty()) return null
        val byName = list.associateBy { it.fileName }
        val rows = run.mapNotNull { byName[it] }
        run.clear()
        if (rows.size < minSize) return null
        return Summary(
            completed = rows.count { it.status == DownloadStatus.COMPLETED },
            failed = rows.count { it.status == DownloadStatus.FAILED },
            stopped = rows.count { it.status == DownloadStatus.STOPPED || it.status == DownloadStatus.PAUSED }
        )
    }
}
