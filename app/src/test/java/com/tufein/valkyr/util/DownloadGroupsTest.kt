package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.util.DownloadGroups.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadGroupsTest {
    private fun row(name: String, status: DownloadStatus) = DownloadItemModel(name, "$name.zip", 0f, 0f, 1L, status = status)

    private val rows = listOf(
        row("done1", DownloadStatus.COMPLETED),
        row("failed1", DownloadStatus.FAILED),
        row("slot", DownloadStatus.DOWNLOADING),
        row("transfer", DownloadStatus.DOWNLOADING),
        row("paused", DownloadStatus.PAUSED),
        row("stopped", DownloadStatus.STOPPED),
        row("unpack", DownloadStatus.UNZIPPING),
        row("debrid", DownloadStatus.QUEUED),
        row("done2", DownloadStatus.COMPLETED)
    )
    private val waiting = setOf("slot.zip")

    @Test fun `sections come in the order active waiting failed done`() {
        val sections = DownloadGroups.sections(rows, waiting)
        assertEquals(listOf(Group.ACTIVE, Group.WAITING, Group.FAILED, Group.DONE), sections.map { it.group })
    }

    @Test fun `rows keep the queue order inside a section`() {
        val sections = DownloadGroups.sections(rows, waiting).associate { it.group to it.rows.map { r -> r.name } }
        assertEquals(listOf("transfer", "unpack"), sections.getValue(Group.ACTIVE))
        assertEquals(listOf("slot", "paused", "debrid"), sections.getValue(Group.WAITING))
        assertEquals(listOf("failed1", "stopped"), sections.getValue(Group.FAILED))
        assertEquals(listOf("done1", "done2"), sections.getValue(Group.DONE))
    }

    @Test fun `empty sections are left out and every row lands exactly once`() {
        val only = listOf(row("a", DownloadStatus.COMPLETED), row("b", DownloadStatus.FAILED))
        val sections = DownloadGroups.sections(only, emptySet())
        assertEquals(listOf(Group.FAILED, Group.DONE), sections.map { it.group })
        assertEquals(only.size, sections.sumOf { it.rows.size })
        assertTrue(DownloadGroups.sections(emptyList(), emptySet()).isEmpty())
    }

    @Test fun `a waiting name only matters for a row that is downloading`() {
        val done = row("done", DownloadStatus.COMPLETED)
        assertEquals(Group.DONE, DownloadGroups.groupOf(done, setOf("done.zip")))
        assertEquals(Group.WAITING, DownloadGroups.groupOf(row("slot", DownloadStatus.DOWNLOADING), setOf("slot.zip")))
        assertEquals(Group.ACTIVE, DownloadGroups.groupOf(row("slot", DownloadStatus.DOWNLOADING), emptySet()))
    }
}
