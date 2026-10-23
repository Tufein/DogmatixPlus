package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.DownloadItemModel

/**
 * The Downloads tab's sections: what is transferring now, what waits (for a slot, a condition or
 * the user), what failed and what is done. Built on [DownloadQueueFilter.group] so a row sits in
 * the same state here as under the filter chips. Pure JVM for the tests.
 */
object DownloadGroups {
    enum class Group { ACTIVE, WAITING, FAILED, DONE }

    /** One section of the list; [rows] keep the queue's own order. */
    data class Section(val group: Group, val rows: List<DownloadItemModel>)

    fun groupOf(item: DownloadItemModel, waitingNames: Set<String>): Group = when (DownloadQueueFilter.group(item, waitingNames)) {
        QueueFilter.ACTIVE -> Group.ACTIVE
        QueueFilter.WAITING, QueueFilter.PAUSED -> Group.WAITING
        QueueFilter.PROBLEMS -> Group.FAILED
        QueueFilter.COMPLETED -> Group.DONE
        // ALL is a filter, never a state of one row.
        QueueFilter.ALL -> Group.WAITING
    }

    /** Sections in the order Active, Waiting, Failed, Done; a section without rows is left out. */
    fun sections(rows: List<DownloadItemModel>, waitingNames: Set<String>): List<Section> {
        val byGroup = Group.entries.associateWith { ArrayList<DownloadItemModel>() }
        for (row in rows) byGroup.getValue(groupOf(row, waitingNames)) += row
        return Group.entries.mapNotNull { group -> byGroup.getValue(group).takeIf { it.isNotEmpty() }?.let { Section(group, it) } }
    }
}
