package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.util.NotificationActions.RunFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationActionsTest {
    private fun item(status: DownloadStatus, name: String = status.name) = DownloadItemModel(
        name = name, fileName = "$name.zip", downloadSpeed = 0f, progress = 0f, fileSize = 100, status = status
    )

    @Test fun `a held queue only offers resume`() {
        val list = listOf(item(DownloadStatus.DOWNLOADING, "a"), item(DownloadStatus.QUEUED, "b"))
        assertEquals(listOf(NotifAction.RESUME_ALL), NotificationActions.ongoing(list, held = true))
        assertEquals(listOf(NotifAction.RESUME_ALL), NotificationActions.ongoing(emptyList(), held = true))
    }

    @Test fun `a running queue offers pause and stop`() {
        val list = listOf(item(DownloadStatus.DOWNLOADING, "a"), item(DownloadStatus.COMPLETED, "b"))
        assertEquals(listOf(NotifAction.PAUSE_ALL, NotifAction.STOP_ALL), NotificationActions.ongoing(list, held = false))
    }

    @Test fun `copying cannot be stopped so only pause shows`() {
        assertEquals(listOf(NotifAction.PAUSE_ALL), NotificationActions.ongoing(listOf(item(DownloadStatus.COPYING)), held = false))
    }

    @Test fun `nothing to act on gives no buttons`() {
        val list = listOf(item(DownloadStatus.COMPLETED, "a"), item(DownloadStatus.PAUSED, "b"), item(DownloadStatus.FAILED, "c"))
        assertTrue(NotificationActions.ongoing(list, held = false).isEmpty())
    }

    @Test fun `queue done opens the downloads`() {
        assertEquals(listOf(NotifAction.OPEN_DOWNLOADS), NotificationActions.queueDone())
    }

    @Test fun `one completed file is one game with a library link`() {
        val game = NotificationActions.singleGame(listOf(RunFile("nintendo_snes", "Chrono Trigger (USA).zip", completed = true)))
        assertNotNull(game)
        game!!
        assertEquals("Chrono Trigger", game.title)
        assertEquals(1, game.files)
        assertEquals("dogmatix://library?console=nintendo_snes&q=Chrono%20Trigger", game.link)
        assertEquals(listOf(NotifAction.OPEN_IN_LIBRARY, NotifAction.OPEN_DOWNLOADS), NotificationActions.finishedGame(game))
        // The link is what the library reads back.
        val request = DeepLinkParser.parse(game.link)!!
        assertEquals(setOf("nintendo_snes"), request.consoles)
        assertEquals("Chrono Trigger", request.query)
    }

    @Test fun `the discs of one game are one game`() {
        val game = NotificationActions.singleGame(listOf(
            RunFile("sony_psx", "Final Fantasy VII (Europe) (Disc 1).chd", true),
            RunFile("sony_psx", "Final Fantasy VII (Europe) (Disc 2).chd", true)
        ))
        assertEquals("Final Fantasy VII", game?.title)
        assertEquals(2, game?.files)
    }

    @Test fun `two games, two consoles, a failure or nothing are not one game`() {
        assertNull(NotificationActions.singleGame(listOf(RunFile("snes", "Chrono Trigger.zip", true), RunFile("snes", "Secret of Mana.zip", true))))
        assertNull(NotificationActions.singleGame(listOf(RunFile("snes", "Tetris.zip", true), RunFile("gb", "Tetris.zip", true))))
        assertNull(NotificationActions.singleGame(listOf(RunFile("snes", "Chrono Trigger.zip", false))))
        assertNull(NotificationActions.singleGame(emptyList()))
    }

    @Test fun `a game without a title to search only opens the downloads`() {
        val game = NotificationActions.FinishedGame("snes", "x", 1, link = null)
        assertEquals(listOf(NotifAction.OPEN_DOWNLOADS), NotificationActions.finishedGame(game))
    }

    @Test fun `notice ids are stable per game and stay in their range`() {
        val a = NotificationActions.singleGame(listOf(RunFile("snes", "Chrono Trigger (USA).zip", true)))!!
        val b = NotificationActions.singleGame(listOf(RunFile("snes", "Chrono Trigger (Europe).zip", true)))!!
        assertEquals(NotificationActions.gameNotificationId(a), NotificationActions.gameNotificationId(b))
        listOf("a", "b", "Zelda", "Ω").forEach {
            val id = NotificationActions.gameNotificationId(NotificationActions.FinishedGame("c", it, 1, null))
            assertTrue(id in NotificationActions.GAME_ID_BASE until NotificationActions.GAME_ID_BASE + NotificationActions.GAME_ID_SPAN)
        }
    }

    @Test fun `queue summary remembers the run that ended, even a single download`() {
        val s = QueueSummary()
        assertNull(s.update(listOf(item(DownloadStatus.DOWNLOADING, "a"))))
        assertNull(s.update(listOf(item(DownloadStatus.COMPLETED, "a"))))
        assertEquals(listOf("a.zip"), s.lastRun)
    }
}
