package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueFilterTest {
    private fun row(name: String, status: DownloadStatus, title: String = name) =
        DownloadItemModel(title, "$name.zip", 0f, 0f, 1L, status = status)

    private val rows = listOf(
        row("transfer", DownloadStatus.DOWNLOADING),
        row("slot", DownloadStatus.DOWNLOADING),
        row("schedule", DownloadStatus.DOWNLOADING),
        row("debrid", DownloadStatus.QUEUED),
        row("copy", DownloadStatus.COPYING),
        row("unpack", DownloadStatus.UNZIPPING),
        row("paused", DownloadStatus.PAUSED),
        row("failed", DownloadStatus.FAILED),
        row("stopped", DownloadStatus.STOPPED),
        row("done", DownloadStatus.COMPLETED)
    )
    private val waiting = setOf("slot.zip", "schedule.zip", "done.zip")

    @Test fun allStatusesAppearInOneGroupAndWaitMembershipDoesNotHideFinishedRows() {
        val all = DownloadQueueFilter.project(rows, DownloadQueueFilter.Criteria(), waiting)
        assertEquals(rows, all.rows)
        assertEquals(10, all.counts[QueueFilter.ALL])
        assertEquals(3, all.counts[QueueFilter.ACTIVE])
        assertEquals(3, all.counts[QueueFilter.WAITING])
        assertEquals(1, all.counts[QueueFilter.PAUSED])
        assertEquals(2, all.counts[QueueFilter.PROBLEMS])
        assertEquals(1, all.counts[QueueFilter.COMPLETED])
        assertFalse(all.filtered)
    }

    @Test fun waitingFilterUsesSlotAndScheduleMembershipAndPreservesOriginalOrder() {
        val view = DownloadQueueFilter.project(rows, DownloadQueueFilter.Criteria(status = QueueFilter.WAITING), waiting)
        assertEquals(listOf("slot.zip", "schedule.zip", "debrid.zip"), view.rows.map { it.fileName })
        assertEquals(10, view.total)
        assertTrue(view.filtered)
    }

    @Test fun queryMatchesTitleAndFilenameWithoutCaseOrSurroundingWhitespace() {
        val list = listOf(row("first", DownloadStatus.FAILED, "Mario World"), row("MARIO-USA", DownloadStatus.COMPLETED), row("sonic", DownloadStatus.FAILED))
        val view = DownloadQueueFilter.project(list, DownloadQueueFilter.Criteria("  mario  ", QueueFilter.PROBLEMS), emptySet())
        assertEquals(listOf("first.zip"), view.rows.map { it.fileName })
        assertEquals(2, view.counts[QueueFilter.ALL])
        assertEquals(1, view.counts[QueueFilter.COMPLETED])
        assertEquals(1, view.counts[QueueFilter.PROBLEMS])
        assertEquals(3, view.total)
    }

    @Test fun blankQueryAndEmptyQueueHaveNoPhantomRowsOrCounts() {
        val view = DownloadQueueFilter.project(emptyList(), DownloadQueueFilter.Criteria("  "), emptySet())
        assertFalse(view.filtered)
        assertTrue(view.rows.isEmpty())
        assertTrue(view.visibleNames.isEmpty())
        assertEquals(0, view.counts.values.sum())
    }

    @Test fun selectAllOnlyPicksVisibleNamesAndSecondPressClearsThePick() {
        val visible = setOf("failed.zip", "stopped.zip")
        val selected = DownloadQueueFilter.toggleVisibleSelection(setOf("hidden.zip", "failed.zip"), visible)
        assertEquals(visible, selected)
        assertTrue(DownloadQueueFilter.toggleVisibleSelection(selected, visible).isEmpty())
        assertTrue(DownloadQueueFilter.toggleVisibleSelection(setOf("hidden.zip"), emptySet()).isEmpty())
    }

    @Test fun largeQueueProjectsNamesInTheSameOrderAndFiltersByLiveState() {
        val list = (0 until 3000).map { row("Game $it", if (it % 2 == 0) DownloadStatus.DOWNLOADING else DownloadStatus.FAILED) }
        val held = (0 until 3000 step 4).mapTo(HashSet()) { "Game $it.zip" }
        val view = DownloadQueueFilter.project(list, DownloadQueueFilter.Criteria(status = QueueFilter.ACTIVE), held)
        assertEquals((2 until 3000 step 4).map { "Game $it.zip" }, view.rows.map { it.fileName })
        assertEquals(750, view.visibleNames.size)
        assertEquals(3000, view.total)
    }
}
