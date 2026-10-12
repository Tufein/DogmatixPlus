package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ActionHistoryFixesTest {
    private val zone = ZoneId.of("UTC")
    private fun at(date: String, hour: Int = 12): Long = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun entry(id: String, at: Long, kind: ActionKind = ActionKind.PLAYED, console: String? = null, file: String? = null, op: String? = null) =
        ActionEntry(id, at, kind, "Game", consoleId = console, fileName = file, opId = op)

    // ---- Day groups after the clock went back ---------------------------------------------------

    @Test fun `a day comes out once when the file order is not the time order`() {
        // Oldest first in the file: a line of the 7th, then (clock set back over a restart) the 6th, then the 7th again.
        val file = listOf(entry("a", at("2026-10-07", 9)), entry("b", at("2026-10-06", 20)), entry("c", at("2026-10-07", 10)))
        val days = ActionHistory.groupByDay(file.asReversed(), at("2026-10-07", 18), zone)
        assertEquals(listOf(LocalDate.parse("2026-10-07"), LocalDate.parse("2026-10-06")), days.map { it.date })
        assertEquals(listOf("c", "a"), days[0].entries.map { it.id })
        assertEquals(listOf("b"), days[1].entries.map { it.id })
        assertEquals(days.size, days.map { it.date }.distinct().size)
    }

    @Test fun `lines of the same moment keep their order`() {
        val t = at("2026-10-07")
        val days = ActionHistory.groupByDay(listOf(entry("x", t), entry("y", t), entry("z", t)), t, zone)
        assertEquals(listOf("x", "y", "z"), days.single().entries.map { it.id })
    }

    @Test fun `repeated keys are made unique, the first stays as it is`() {
        assertEquals(listOf("d1", "d2", "d1#2", "d1#3"), ActionHistory.uniqueKeys(listOf("d1", "d2", "d1", "d1")))
        // A suffix that is itself taken is skipped.
        val keys = ActionHistory.uniqueKeys(listOf("a", "a#2", "a"))
        assertEquals(listOf("a", "a#2", "a#3"), keys)
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(ActionHistory.uniqueKeys(emptyList()).isEmpty())
    }

    // ---- Restore and purge lines get the game of their removal ----------------------------------

    @Test fun `a purge or restore line takes the console and file of its removal`() {
        val log = listOf(
            entry("r1", 1, ActionKind.REMOVED, "snes", "Mario.zip", "op1"),
            entry("r2", 2, ActionKind.REMOVED, "n64", "Zelda.zip", "op2")
        )
        val purged = ActionLogFormat.withRemovalInfo(entry("p", 3, ActionKind.PURGED, op = "op1"), log)
        assertEquals("snes", purged.consoleId)
        assertEquals("Mario.zip", purged.fileName)
        val restored = ActionLogFormat.withRemovalInfo(entry("s", 3, ActionKind.RESTORED, op = "op2"), log)
        assertEquals("n64", restored.consoleId)
        assertEquals("Zelda.zip", restored.fileName)
    }

    @Test fun `other lines and unknown operations are left alone`() {
        val log = listOf(entry("r1", 1, ActionKind.REMOVED, "snes", "Mario.zip", "op1"))
        val played = entry("x", 2, ActionKind.PLAYED, op = "op1")
        assertEquals(played, ActionLogFormat.withRemovalInfo(played, log))
        val unknown = entry("p", 2, ActionKind.PURGED, op = "other")
        assertNull(ActionLogFormat.withRemovalInfo(unknown, log).fileName)
        val own = entry("p2", 2, ActionKind.PURGED, "gba", "Own.gba", "op1")
        assertEquals(own, ActionLogFormat.withRemovalInfo(own, log))
    }

    // ---- Failed downloads -----------------------------------------------------------------------

    @Test fun `failures of the first list are not news, later ones are recorded once`() {
        val watch = DownloadFailureWatch()
        assertTrue(watch.update(listOf("old.zip" to true, "a.zip" to false)).isEmpty())
        assertTrue(watch.update(listOf("old.zip" to true, "a.zip" to false)).isEmpty())
        assertEquals(listOf("a.zip"), watch.update(listOf("old.zip" to true, "a.zip" to true)))
        assertTrue(watch.update(listOf("old.zip" to true, "a.zip" to true)).isEmpty())
    }

    @Test fun `a download that failed before it was ever seen is still recorded`() {
        val watch = DownloadFailureWatch()
        watch.update(emptyList())
        // Queued and failed between two snapshots: the list never showed it running.
        assertEquals(listOf("quick.zip"), watch.update(listOf("quick.zip" to true)))
    }

    @Test fun `a retry that fails again is a new failure`() {
        val watch = DownloadFailureWatch()
        watch.update(listOf("a.zip" to false))
        assertEquals(listOf("a.zip"), watch.update(listOf("a.zip" to true)))
        watch.update(listOf("a.zip" to false))
        assertEquals(listOf("a.zip"), watch.update(listOf("a.zip" to true)))
        // Gone from the list and back failed: new as well.
        watch.update(emptyList())
        assertEquals(listOf("a.zip"), watch.update(listOf("a.zip" to true)))
    }
}
