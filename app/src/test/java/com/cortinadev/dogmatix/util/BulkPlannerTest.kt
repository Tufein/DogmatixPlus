package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkPlannerTest {
    private fun c(id: Long, title: String, file: String, size: Long = 100, owned: Boolean = false, downloading: Boolean = false, tags: List<String> = emptyList()) =
        BulkCandidate(id, "snes", title, file, size, tags, owned, downloading)

    @Test fun `owned and running downloads are skipped and the size is summed`() {
        val plan = BulkPlanner.plan(listOf(c(1, "a", "a.zip"), c(2, "b", "b.zip", owned = true), c(3, "c", "c.zip", downloading = true), c(4, "d", "d.zip", 50)),
            bestOnly = false, regionPreference = emptyList(), languages = emptySet(), freeBytes = null)
        assertEquals(listOf(1L, 4L), plan.chosen.map { it.id })
        assertEquals(150L, plan.totalBytes)
        assertEquals(1, plan.skippedOwned)
        assertEquals(1, plan.skippedActive)
        assertTrue(plan.fits)
    }

    @Test fun `best only keeps one version per game`() {
        val rows = listOf(c(1, "mario", "Mario (Japan).zip", tags = listOf("Japan")), c(2, "mario", "Mario (Europe).zip", tags = listOf("Europe")), c(3, "zelda", "Zelda.zip"))
        val plan = BulkPlanner.plan(rows, bestOnly = true, regionPreference = listOf("Europe", "USA", "World"), languages = setOf("En"), freeBytes = null)
        assertEquals(setOf(2L, 3L), plan.chosen.map { it.id }.toSet())
        assertEquals(1, plan.skippedVersions)
    }

    @Test fun `not enough room is reported`() {
        val plan = BulkPlanner.plan(listOf(c(1, "a", "a", 1000)), false, emptyList(), emptySet(), freeBytes = 1000)
        assertFalse(plan.fits)
        assertTrue(BulkPlanner.plan(listOf(c(1, "a", "a", 900)), false, emptyList(), emptySet(), freeBytes = 1000).fits)
    }

    @Test fun `at most the cap is queued`() {
        val many = (1..(BulkPlanner.MAX_FILES + 20)).map { c(it.toLong(), "g$it", "g$it.zip", 1) }
        assertEquals(BulkPlanner.MAX_FILES, BulkPlanner.plan(many, false, emptyList(), emptySet(), null).chosen.size)
    }
}
