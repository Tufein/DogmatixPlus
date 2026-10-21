package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfLogTest {
    @Test fun `ring keeps only the newest calls and ignores fast ones`() {
        val ops = PerfLog.SlowOps(capacity = 3, thresholdMs = 50)
        ops.record("fast", 49)
        assertEquals(0, ops.count())
        listOf(60L, 70L, 80L, 90L).forEachIndexed { i, ms -> ops.record("op$i", ms) }
        assertEquals(3, ops.count())
        assertEquals(listOf("op3", "op2", "op1"), ops.slowest(10).map { it.name })
    }

    @Test fun `slowest is sorted and limited`() {
        val ops = PerfLog.SlowOps(10, 10)
        listOf(30L, 500L, 20L, 120L).forEachIndexed { i, ms -> ops.record("q$i", ms) }
        assertEquals(listOf(500L, 120L), ops.slowest(2).map { it.ms })
    }

    @Test fun `first mark of a phase wins and phases come out in time order`() {
        val p = PerfLog.Phases()
        p.mark("first_frame", 900)
        p.mark("app_create", 300)
        p.mark("app_create", 999)
        assertEquals(listOf("app_create" to 300L, "first_frame" to 900L), p.all().map { it.name to it.ms })
        assertEquals(300L, p.get("app_create"))
        assertNull(p.get("nothing"))
    }

    @Test fun `labels never carry user text`() {
        assertEquals("db.search", PerfLog.clean("db.search"))
        assertEquals("Mario Kart/ROMs/x.iso".filter { it.isLetterOrDigit() || it in "_.:-" }.take(40), PerfLog.clean("Mario Kart/ROMs/x.iso"))
        assertTrue(PerfLog.clean("a".repeat(200)).length <= PerfLog.MAX_NAME)
        assertEquals("op", PerfLog.clean("///"))
    }

    @Test fun `cover hit rate counts memory and disk as hits`() {
        assertNull(PerfLog.CoverCounts(0, 0, 0).hitPercent)
        assertEquals(75, PerfLog.CoverCounts(2, 1, 1).hitPercent)
    }

    @Test fun `report has numbers and no free text`() {
        val text = PerfLog.report(
            listOf(PerfLog.Phase("app_create", 250), PerfLog.Phase("first_frame", 800)),
            listOf(PerfLog.Op("db.search", 140)),
            PerfLog.CoverCounts(8, 1, 1),
            dbBytes = 20 * 1024 * 1024L
        )
        assertTrue(text.contains("app_create=250, first_frame=800"))
        assertTrue(text.contains("db.search: 140 ms"))
        assertTrue(text.contains("90% without network"))
        assertTrue(text.contains("20480 KiB"))
    }

    @Test fun `empty report says so`() {
        val text = PerfLog.report(emptyList(), emptyList(), PerfLog.CoverCounts(0, 0, 0), -1)
        assertTrue(text.contains("no marks yet"))
        assertTrue(text.contains("none recorded"))
        assertTrue(text.contains("unknown"))
    }

    @Test fun `timed records a slow block and returns its value`() {
        PerfLog.ops.clear()
        val v = PerfLog.timed("test.slow") { Thread.sleep(60); 7 }
        assertEquals(7, v)
        assertTrue(PerfLog.ops.slowest(5).any { it.name == "test.slow" })
        PerfLog.ops.clear()
    }
}
