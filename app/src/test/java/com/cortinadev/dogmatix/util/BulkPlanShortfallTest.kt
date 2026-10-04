package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BulkPlanShortfallTest {
    @Test fun `bulk plan reports the shortfall and the split per console`() {
        fun c(id: Long, console: String, size: Long) = BulkCandidate(id, console, "g$id", "g$id.zip", size, emptyList(), owned = false, downloading = false)
        val plan = BulkPlanner.plan(listOf(c(1, "gba", 600), c(2, "snes", 300), c(3, "gba", 200)), false, emptyList(), emptySet(), freeBytes = 1000)
        assertFalse(plan.fits)
        assertEquals(1100L - (1000 - 20), plan.shortBytes)
        assertEquals(listOf(Triple("gba", 2, 800L), Triple("snes", 1, 300L)), plan.perConsole)
        assertEquals(0L, BulkPlanner.plan(listOf(c(1, "gba", 10)), false, emptyList(), emptySet(), freeBytes = null).shortBytes)
    }
}
