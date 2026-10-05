package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionGoalsTest {

    @Test
    fun `percent rounds down and only reaches 100 when complete`() {
        assertEquals(0, CollectionGoals.percent(0, 0))
        assertEquals(99, CollectionGoals.percent(999, 1000))
        assertEquals(40, CollectionGoals.percent(412, 1018))
        assertEquals(100, CollectionGoals.percent(1018, 1018))
        assertEquals(100, CollectionGoals.percent(5, 4))
    }

    @Test
    fun `fraction is clamped and zero without a total`() {
        assertEquals(0f, CollectionGoals.fraction(3, 0), 0f)
        assertEquals(0.5f, CollectionGoals.fraction(1, 2), 0.0001f)
        assertEquals(1f, CollectionGoals.fraction(9, 3), 0f)
    }

    @Test
    fun `versions of a game count once and one owned version is enough`() {
        val files = listOf("Super Game (USA).zip", "Super Game (Europe).zip", "Other Game (USA).zip", "Third, The (Japan).zip")
        val tally = CollectionGoals.sourceTally(files) { it == "Super Game (Europe).zip" }
        assertEquals(3, tally.total)
        assertEquals(1, tally.owned)
        assertEquals(listOf("Other Game", "The Third"), tally.missing)
    }

    @Test
    fun `source tally of nothing is empty`() {
        val tally = CollectionGoals.sourceTally(emptyList()) { true }
        assertEquals(0, tally.total)
        assertTrue(tally.missing.isEmpty())
    }

    @Test
    fun `owned check uses the scopes of the console`() {
        val keys = setOf("gba|zelda (usa).gba", "gba|zelda (usa)", "snes|mario.sfc")
        val scopes = setOf("gba", "")
        assertTrue(CollectionGoals.isOwned(scopes, "Zelda (USA).gba", keys))
        assertTrue(CollectionGoals.isOwned(scopes, "zelda%20(usa).gba", keys))
        assertFalse(CollectionGoals.isOwned(scopes, "Mario.sfc", keys))
        assertFalse(CollectionGoals.isOwned(scopes, "Mario.sfc", emptySet()))
    }

    @Test
    fun `names are grouped by scope and joined for a console`() {
        val byScope = CollectionGoals.namesByScope(setOf("gba|a.gba", "gba|a", "|loose.gba", "snes|b"))
        assertEquals(setOf("a.gba", "a", "loose.gba"), CollectionGoals.ownedNames(setOf("gba", ""), byScope))
    }

    @Test
    fun `dat tally matches names with or without extension and sorts the missing ones`() {
        val owned = setOf("sonic (usa).md", "sonic (usa)", "alex kidd (usa)")
        val tally = CollectionGoals.datTally(listOf("Sonic (USA)", "Alex Kidd (USA)", "Zaxxon (USA)", "Altered Beast (USA)", "Sonic (USA)"), owned)
        assertEquals(4, tally.total)
        assertEquals(2, tally.owned)
        assertEquals(listOf("Altered Beast (USA)", "Zaxxon (USA)"), tally.missing)
    }

    @Test
    fun `report tally trusts the report`() {
        val tally = CollectionGoals.reportTally(10, listOf("b", "a"))
        assertEquals(10, tally.total)
        assertEquals(8, tally.owned)
        assertEquals(listOf("a", "b"), tally.missing)
    }

    @Test
    fun `overall counts only measured consoles and goals sort first`() {
        val rows = listOf(
            ConsoleProgress("b", "Beta", 5, 10, CollectionBasis.SOURCES),
            ConsoleProgress("a", "Alpha", 0, 0, CollectionBasis.SOURCES),
            ConsoleProgress("c", "Gamma", 10, 10, CollectionBasis.DAT_VERIFIED, goal = true)
        )
        assertEquals(15 to 20, CollectionGoals.overall(rows))
        assertEquals(listOf("c", "a", "b"), CollectionGoals.sort(rows).map { it.consoleId })
        assertTrue(rows[2].complete)
        assertFalse(rows[0].complete)
        assertEquals(5, rows[0].missing)
    }
}
