package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.util.SecondScreenControls.HoldButton
import com.cortinadev.dogmatix.util.SecondScreenControls.Mode
import com.cortinadev.dogmatix.util.SecondScreenControls.RowAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecondScreenControlsTest {

    private fun item(name: String, status: DownloadStatus) =
        DownloadItemModel(name = name, fileName = name, downloadSpeed = 0f, progress = 0f, fileSize = 100L, status = status)

    @Test
    fun `empty queue without hold is idle`() {
        val view = SecondScreenControls.view(emptyList(), held = false)
        assertEquals(Mode.IDLE, view.mode)
        assertNull(view.holdButton)
    }

    @Test
    fun `finished downloads alone stay idle`() {
        val list = listOf(item("a", DownloadStatus.COMPLETED), item("b", DownloadStatus.FAILED), item("c", DownloadStatus.STOPPED))
        val view = SecondScreenControls.view(list, held = false)
        assertEquals(Mode.IDLE, view.mode)
        assertEquals(emptyList<DownloadItemModel>(), view.rows)
    }

    @Test
    fun `running queue offers pause all`() {
        val list = listOf(item("a", DownloadStatus.DOWNLOADING), item("b", DownloadStatus.QUEUED))
        val view = SecondScreenControls.view(list, held = false)
        assertEquals(Mode.ACTIVE, view.mode)
        assertEquals(HoldButton.PAUSE_ALL, view.holdButton)
        assertEquals(1, view.waiting)
    }

    @Test
    fun `held queue shows paused with resume all even when empty`() {
        assertEquals(Mode.HELD, SecondScreenControls.view(emptyList(), held = true).mode)
        assertEquals(HoldButton.RESUME_ALL, SecondScreenControls.view(emptyList(), held = true).holdButton)
    }

    @Test
    fun `held queue with waiting items is held, not idle`() {
        val view = SecondScreenControls.view(listOf(item("a", DownloadStatus.QUEUED), item("b", DownloadStatus.QUEUED)), held = true)
        assertEquals(Mode.HELD, view.mode)
        assertEquals(HoldButton.RESUME_ALL, view.holdButton)
        assertEquals(2, view.waiting)
        assertEquals(2, view.rows.size)
    }

    @Test
    fun `only paused downloads show paused without the queue button`() {
        val view = SecondScreenControls.view(listOf(item("a", DownloadStatus.PAUSED), item("b", DownloadStatus.COMPLETED)), held = false)
        assertEquals(Mode.PAUSED, view.mode)
        assertNull(view.holdButton)
        assertEquals(listOf("a"), view.rows.map { it.fileName })
        assertEquals(1, view.paused)
    }

    @Test
    fun `rows list running downloads before paused ones`() {
        val list = listOf(item("p", DownloadStatus.PAUSED), item("d", DownloadStatus.DOWNLOADING), item("q", DownloadStatus.QUEUED))
        assertEquals(listOf("d", "q", "p"), SecondScreenControls.view(list, held = false).rows.map { it.fileName })
    }

    @Test
    fun `row actions follow the downloads screen`() {
        assertEquals(RowAction.RESUME, SecondScreenControls.rowAction(DownloadStatus.PAUSED, isTorrent = true))
        assertEquals(RowAction.RESUME, SecondScreenControls.rowAction(DownloadStatus.PAUSED, isTorrent = null))
        assertEquals(RowAction.PAUSE, SecondScreenControls.rowAction(DownloadStatus.DOWNLOADING, isTorrent = false))
        assertEquals(RowAction.PAUSE, SecondScreenControls.rowAction(DownloadStatus.QUEUED, isTorrent = false))
        assertEquals(RowAction.PAUSE, SecondScreenControls.rowAction(DownloadStatus.DOWNLOADING, isTorrent = true))
        // A torrent with its debrid service (queued) cannot pause.
        assertEquals(RowAction.NONE, SecondScreenControls.rowAction(DownloadStatus.QUEUED, isTorrent = true))
        assertEquals(RowAction.NONE, SecondScreenControls.rowAction(DownloadStatus.UNZIPPING, isTorrent = false))
        assertEquals(RowAction.NONE, SecondScreenControls.rowAction(DownloadStatus.COPYING, isTorrent = false))
        // Unknown source: no button rather than one that does nothing.
        assertEquals(RowAction.NONE, SecondScreenControls.rowAction(DownloadStatus.DOWNLOADING, isTorrent = null))
    }
}
