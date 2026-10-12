package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.ForYou.ReasonKind
import com.cortinadev.dogmatix.util.ForYou.SeedKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouTest {

    private var nextId = 1L
    private fun row(name: String, console: String = "nintendo_snes", owned: Boolean = false) =
        ForYou.Row(nextId++, console, "$name.zip", name, owned)

    private fun meta(vararg pairs: Pair<String, LibraryDiscovery.Meta>): (String, String) -> LibraryDiscovery.Meta? {
        val map = pairs.associate { (title, m) -> ForYou.lookupKey("nintendo_snes", GameTitleCleaner.clean(title)) to m }
        return { _, key -> map[key] }
    }

    private fun m(vararg genres: String, year: Int? = null, dev: String = "") = LibraryDiscovery.Meta(genres.toSet(), year, dev)

    @Test
    fun `lookup key matches the metadata cache key`() {
        val name = "Legend of Zelda, The - A Link to the Past (USA) [!].sfc"
        assertEquals(LibraryDiscovery.lookupKey("nintendo_snes", name), ForYou.lookupKey("nintendo_snes", GameTitleCleaner.clean(name)))
    }

    @Test
    fun `series key needs two words or one long one`() {
        assertEquals("super mario", ForYou.seriesKey(ForYou.seriesWords("Super Mario World 2")))
        assertEquals("tetris", ForYou.seriesKey(ForYou.seriesWords("Tetris")))
        assertEquals(null, ForYou.seriesKey(ForYou.seriesWords("Ys III")))
    }

    @Test
    fun `same series as a played game, never the game itself or an owned title`() {
        val seeds = listOf(ForYou.Seed("nintendo_snes", "Super Mario World (USA).zip", "Super Mario World", SeedKind.PLAYED))
        val rows = listOf(
            row("Super Mario World (USA)", owned = true),
            row("Super Mario World (Europe)"),
            row("Super Mario Kart (USA)"),
            row("Super Mario All-Stars (USA)", owned = true),
            row("Super Mario All-Stars (Europe)"),
            row("Pilotwings (USA)")
        )
        val out = ForYou.suggest(seeds, rows, { _, _ -> null })
        assertEquals(listOf("Super Mario Kart (USA)"), out.map { it.row.name })
        val reason = out.single().reasons.first()
        assertEquals(ReasonKind.SERIES, reason.kind)
        assertEquals("Super Mario World", reason.title)
        assertEquals(SeedKind.PLAYED, reason.seedKind)
    }

    @Test
    fun `because you starred, from shared genres on the same console`() {
        val seeds = listOf(ForYou.Seed("nintendo_snes", "Chrono Trigger (USA).zip", "Chrono Trigger", SeedKind.FAVOURITE))
        val rows = listOf(row("Secret of Mana (USA)"), row("F-Zero (USA)"), row("Secret of Mana (USA)", console = "sega_md"))
        val out = ForYou.suggest(
            seeds, rows,
            meta("Chrono Trigger" to m("Role-Playing", "Action"), "Secret of Mana" to m("Role-Playing", "Action"), "F-Zero" to m("Racing"))
        )
        assertEquals(listOf("nintendo_snes"), out.map { it.row.consoleId })
        val because = out.single().reasons.first { it.kind == ReasonKind.BECAUSE }
        assertEquals("Chrono Trigger", because.title)
        assertEquals(SeedKind.FAVOURITE, because.seedKind)
    }

    @Test
    fun `nothing to go on gives nothing, and a console match alone is not enough`() {
        val seeds = listOf(ForYou.Seed("nintendo_snes", "A Game.zip", "A Game", SeedKind.FAVOURITE))
        val rows = (1..50).map { row("Unrelated $it") }
        assertTrue(ForYou.suggest(seeds, rows, { _, _ -> null }).isEmpty())
        assertTrue(ForYou.suggest(emptyList(), rows, { _, _ -> null }).isEmpty())
    }

    @Test
    fun `popular games on a console with few owned games say both`() {
        val owned = row("Only Game (USA)", owned = true)
        val rows = listOf(owned, row("Big Hit (USA)")) + (1..30).map { row("Filler $it (USA)") }
        val popular = mapOf("nintendo_snes" to mapOf(BestGames.titleKey("Big Hit") to ForYou.Popular(rank = 1, byPlayers = true)))
        val out = ForYou.suggest(emptyList(), rows, { _, _ -> null }, popular)
        assertEquals("Big Hit (USA)", out.single().row.name)
        val kinds = out.single().reasons.map { it.kind }
        assertEquals(listOf(ReasonKind.POPULAR, ReasonKind.FEW_ON_CONSOLE), kinds)
        assertEquals(1, out.single().reasons[1].count)
        assertTrue(out.single().reasons[0].byPlayers)
    }

    @Test
    fun `a game on the device brings its series without seeds`() {
        val rows = listOf(row("Donkey Kong Country (USA)", owned = true), row("Donkey Kong Country 2 (USA)"), row("Uniracers (USA)"))
        val out = ForYou.suggest(emptyList(), rows, { _, _ -> null })
        assertEquals(listOf("Donkey Kong Country 2 (USA)"), out.map { it.row.name })
        val reason = out.single().reasons.single()
        assertEquals(ReasonKind.SERIES, reason.kind)
        assertEquals(SeedKind.OWNED, reason.seedKind)
        assertEquals("Donkey Kong Country", reason.title)
    }

    @Test
    fun `excluded keys, hidden consoles and seed titles are left out`() {
        val seeds = listOf(ForYou.Seed("nintendo_snes", "Mega Man X (USA).zip", "Mega Man X", SeedKind.PLAYED))
        val x2 = row("Mega Man X2 (USA)")
        val x3 = row("Mega Man X3 (USA)")
        val gb = row("Mega Man V (USA)", console = "nintendo_gb")
        val out = ForYou.suggest(
            seeds, listOf(x2, x3, gb), { _, _ -> null },
            excluded = setOf(UpNext.key(x2.consoleId, x2.fileName)), hiddenConsoles = setOf("nintendo_gb")
        )
        assertEquals(listOf("Mega Man X3 (USA)"), out.map { it.row.name })
    }

    @Test
    fun `versions of one title are one suggestion and a series is capped`() {
        val seeds = listOf(ForYou.Seed("nintendo_snes", "Final Fantasy (USA).zip", "Final Fantasy Mystic Quest", SeedKind.FAVOURITE))
        val rows = listOf(row("Final Fantasy II (USA)"), row("Final Fantasy II (Japan)")) + (3..9).map { row("Final Fantasy Part $it (USA)") }
        val out = ForYou.suggest(seeds, rows, { _, _ -> null })
        assertEquals(ForYou.MAX_PER_SERIES, out.size)
        val ff2 = out.first { it.row.name.startsWith("Final Fantasy II") }
        assertEquals(2, ff2.versions.size)
    }

    @Test
    fun `a genre the player plays a lot is a reason, with the decade as extra`() {
        val seeds = listOf(
            ForYou.Seed("nintendo_snes", "P1.zip", "Puzzle One", SeedKind.FAVOURITE),
            ForYou.Seed("nintendo_snes", "P2.zip", "Puzzle Two", SeedKind.FAVOURITE),
            ForYou.Seed("nintendo_snes", "P3.zip", "Puzzle Three", SeedKind.PLAYED)
        )
        val out = ForYou.suggest(
            seeds, listOf(row("Brain Lord (USA)"), row("Other Thing (USA)")),
            meta(
                "Puzzle One" to m("Puzzle", year = 1993), "Puzzle Two" to m("Puzzle", year = 1994), "Puzzle Three" to m("Strategy", year = 1995),
                "Brain Lord" to m("Puzzle", year = 1994), "Other Thing" to m("Sports", year = 1994)
            )
        )
        // Brain Lord shares a genre with a starred game on the same console: "because", plus the genre and the decade.
        assertEquals(listOf("Brain Lord (USA)"), out.map { it.row.name })
        val kinds = out.single().reasons.map { it.kind }
        assertTrue(ReasonKind.BECAUSE in kinds && ReasonKind.GENRE in kinds && ReasonKind.DECADE in kinds)
        assertEquals(1990, out.single().reasons.first { it.kind == ReasonKind.DECADE }.decade)
        assertFalse(out.single().reasons.any { it.kind == ReasonKind.SERIES })
    }

    @Test
    fun `fifty thousand rows stay quick`() {
        val seeds = (1..40).map { ForYou.Seed("nintendo_snes", "Seed $it.zip", "Series$it Saga", SeedKind.PLAYED) }
        val rows = (1..50_000).map { ForYou.Row(it.toLong(), "c${it % 20}", "Game $it (USA).zip", "Series${it % 60} Saga Part $it (USA)", it % 7 == 0) }
        val started = System.nanoTime()
        val out = ForYou.suggest(seeds, rows, { _, _ -> null }, limit = 30)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(out.size <= 30)
        assertTrue("took $ms ms", ms < 10_000)
    }
}
