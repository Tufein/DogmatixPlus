package com.tufein.valkyr.ui.screens.home

import com.tufein.valkyr.ui.screens.home.ShelvesModel.Game
import com.tufein.valkyr.ui.screens.home.ShelvesModel.Recent
import com.tufein.valkyr.ui.screens.home.ShelvesModel.RecentKind
import com.tufein.valkyr.ui.screens.home.ShelvesModel.ShelfKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelvesModelTest {

    private fun g(name: String, console: String = "snes", owned: Boolean = false) = Game(console, "$name.zip", name, owned)
    private fun played(game: Game, at: Long) = Recent(game, at, RecentKind.PLAYED)
    private fun downloaded(game: Game, at: Long) = Recent(game, at, RecentKind.DOWNLOADED)
    private fun titles(games: List<Game>) = games.map { it.title }
    private val names = mapOf("snes" to "Super Nintendo", "gba" to "Game Boy Advance", "md" to "Mega Drive")
    private val nameOf: (String) -> String = { names[it] ?: it }

    @Test
    fun `the start state is no search, no filter and the default order`() {
        assertTrue(ShelvesModel.isStartState("", 0, defaultSort = true))
        assertTrue(ShelvesModel.isStartState("   ", 0, defaultSort = true))
        assertFalse(ShelvesModel.isStartState("mario", 0, defaultSort = true))
        assertFalse(ShelvesModel.isStartState("", 1, defaultSort = true))
        assertFalse(ShelvesModel.isStartState("", 0, defaultSort = false))
    }

    @Test
    fun `recents are newest first, one per game, a start before a download at the same time`() {
        val a = g("A"); val b = g("B")
        val list = ShelvesModel.recents(listOf(downloaded(a, 10), played(b, 20), played(a, 5), downloaded(b, 20), played(g("NoTime"), 0)))
        assertEquals(listOf("B" to RecentKind.PLAYED, "A" to RecentKind.DOWNLOADED), list.map { it.game.title to it.kind })
        assertEquals(listOf(20L, 10L), list.map { it.at })
    }

    @Test
    fun `recents are capped`() {
        val many = (1..100).map { played(g("G$it"), it.toLong()) }
        assertEquals(ShelvesModel.RECENT_LOOK_BACK, ShelvesModel.recents(many).size)
        assertEquals(3, ShelvesModel.recents(many, limit = 3).size)
        assertTrue(ShelvesModel.recents(many, limit = -1).isEmpty())
    }

    @Test
    fun `the hero is the newest game on the device, else the newest at all`() {
        val gone = played(g("Gone"), 30)
        val here = downloaded(g("Here", owned = true), 20)
        assertEquals("Here", ShelvesModel.hero(listOf(gone, here))?.game?.title)
        assertEquals("Gone", ShelvesModel.hero(listOf(gone, played(g("AlsoGone"), 10)))?.game?.title)
        assertNull(ShelvesModel.hero(emptyList()))
    }

    @Test
    fun `new for you puts Up next first, drops the hero and duplicates, and is capped`() {
        val upNext = listOf(g("U1"), g("U2"))
        val suggestions = listOf(g("U2"), g("S1"), g("Hero"), g("S2"))
        val list = ShelvesModel.forYou(upNext, suggestions, exclude = setOf(g("Hero").key))
        assertEquals(listOf("U1", "U2", "S1", "S2"), titles(list))
        val long = (1..40).map { g("S$it") }
        assertEquals(ShelvesModel.FOR_YOU_CAP, ShelvesModel.forYou(emptyList(), long).size)
        assertEquals(listOf("S1", "S2"), titles(ShelvesModel.forYou(emptyList(), long, cap = 2)))
    }

    @Test
    fun `a console shelf has its recent games first, then A to Z, capped, with the full count`() {
        val games = listOf(g("zelda"), g("Contra"), g("Axelay"), g("Mario"), g("contra"))
        val recentAt = mapOf(g("Mario").key to 50L)
        val shelves = ShelvesModel.consoleShelves(mapOf("snes" to games), recentAt, nameOf, cap = 3)
        val shelf = shelves.single()
        assertEquals(ShelfKind.ON_DEVICE, shelf.kind)
        assertEquals(listOf("Mario", "Axelay", "Contra"), titles(shelf.games))
        assertEquals(5, shelf.total)
        assertTrue(shelf.hasMore)
        assertEquals("console:snes", shelf.key)
    }

    @Test
    fun `the same game twice counts once`() {
        val shelf = ShelvesModel.consoleShelves(mapOf("snes" to listOf(g("A"), g("A"), g("B"))), emptyMap(), nameOf).single()
        assertEquals(2, shelf.total)
        assertFalse(shelf.hasMore)
    }

    @Test
    fun `shelves follow the newest activity, then size, then name, and empty consoles are left out`() {
        val owned = mapOf(
            "md" to listOf(g("M1", "md")),
            "gba" to listOf(g("G1", "gba"), g("G2", "gba")),
            "snes" to listOf(g("S1"), g("S2")),
            "empty" to emptyList()
        )
        // Without any activity: the biggest first, ties by the console's name.
        assertEquals(listOf("gba", "snes", "md"), ShelvesModel.consoleShelves(owned, emptyMap(), nameOf).map { it.consoleId })
        // The console of the game played last comes first.
        val recentAt = mapOf(g("M1", "md").key to 99L, g("S2").key to 10L)
        assertEquals(listOf("md", "snes", "gba"), ShelvesModel.consoleShelves(owned, recentAt, nameOf).map { it.consoleId })
    }

    @Test
    fun `with nothing on the device the biggest consoles get a newest shelf`() {
        val counts = mapOf("snes" to 500, "gba" to 900, "md" to 500, "empty" to 0)
        assertEquals(listOf("gba", "md", "snes"), ShelvesModel.newestConsoles(counts, nameOf))
        assertEquals(listOf("gba"), ShelvesModel.newestConsoles(counts, nameOf, max = 1))
        val shelf = ShelvesModel.newestShelf("snes", (1..30).map { g("N$it") }, total = 500)!!
        assertEquals(ShelfKind.NEWEST, shelf.kind)
        assertEquals(ShelvesModel.SHELF_CAP, shelf.games.size)
        assertEquals("N1", shelf.games.first().title)
        assertEquals(500, shelf.total)
        assertNull(ShelvesModel.newestShelf("snes", emptyList(), total = 10))
        // A count lower than what is shown (a profile hides some) never says "fewer than shown".
        assertEquals(2, ShelvesModel.newestShelf("snes", listOf(g("A"), g("B")), total = 1)!!.total)
    }

    @Test
    fun `the state is empty only without hero, suggestions and shelves`() {
        assertTrue(ShelvesModel.State.EMPTY.isEmpty)
        assertFalse(ShelvesModel.State(null, listOf(g("A")), emptyList()).isEmpty)
        assertFalse(ShelvesModel.State(played(g("A"), 1), emptyList(), emptyList()).isEmpty)
    }
}
