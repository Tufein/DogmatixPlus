package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus

/** Queue views distinguish a transfer from a download waiting for a slot or a condition. */
enum class QueueFilter { ALL, ACTIVE, WAITING, PAUSED, PROBLEMS, COMPLETED }

/** Pure projection: filtering never reorders the queue or changes its whole-queue summary. */
object DownloadQueueFilter {
    data class Criteria(val query: String = "", val status: QueueFilter = QueueFilter.ALL)

    data class View(
        val criteria: Criteria = Criteria(),
        val rows: List<DownloadItemModel> = emptyList(),
        val counts: Map<QueueFilter, Int> = emptyMap(),
        val total: Int = 0,
        val visibleNames: Set<String> = emptySet()
    ) {
        val filtered: Boolean get() = criteria.query.isNotBlank() || criteria.status != QueueFilter.ALL
    }

    /** The caller supplies actual wait membership; a queued web download still has DOWNLOADING status. */
    fun group(item: DownloadItemModel, waitingNames: Set<String>): QueueFilter = when (item.status) {
        DownloadStatus.DOWNLOADING -> if (item.fileName in waitingNames) QueueFilter.WAITING else QueueFilter.ACTIVE
        DownloadStatus.QUEUED -> QueueFilter.WAITING
        DownloadStatus.COPYING, DownloadStatus.UNZIPPING -> QueueFilter.ACTIVE
        DownloadStatus.PAUSED -> QueueFilter.PAUSED
        DownloadStatus.FAILED, DownloadStatus.STOPPED -> QueueFilter.PROBLEMS
        DownloadStatus.COMPLETED -> QueueFilter.COMPLETED
    }

    fun project(list: List<DownloadItemModel>, criteria: Criteria, waitingNames: Set<String>): View {
        val query = criteria.query.trim()
        val counts = QueueFilter.entries.associateWithTo(LinkedHashMap()) { 0 }
        val rows = ArrayList<DownloadItemModel>()
        val names = HashSet<String>()
        for (item in list) {
            if (query.isNotEmpty() && !item.name.contains(query, ignoreCase = true) &&
                !item.fileName.contains(query, ignoreCase = true)) continue
            val group = group(item, waitingNames)
            counts[QueueFilter.ALL] = counts.getValue(QueueFilter.ALL) + 1
            counts[group] = counts.getValue(group) + 1
            if (criteria.status == QueueFilter.ALL || criteria.status == group) {
                rows += item
                names += item.fileName
            }
        }
        return View(criteria, rows, counts, list.size, names)
    }

    /** Selecting all replaces the pick with the visible rows; hidden rows never join a batch action. */
    fun toggleVisibleSelection(selected: Set<String>, visibleNames: Set<String>): Set<String> =
        if (selected.containsAll(visibleNames)) emptySet() else visibleNames.toSet()
}
