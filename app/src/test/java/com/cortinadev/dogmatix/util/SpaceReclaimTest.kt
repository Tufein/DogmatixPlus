package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceReclaimTest {

    private val gb = SpaceReclaim.GB
    private val mb = 1024L * 1024L

    private fun file(
        console: String?,
        folder: String,
        name: String,
        size: Long,
        modified: Long = 0L,
        level: Int = 0
    ) = DiskFile(
        scope = console ?: "",
        consoleId = console,
        folder = ".../ROMs/$folder",
        name = name,
        size = size,
        uri = "content://x/$folder/$name",
        dirId = "/storage/$folder",
        fileId = "/storage/$folder/$name",
        inSubfolder = level > 0,
        level = level,
        lastModified = modified
    )

    private fun entriesOf(vararg files: DiskFile) = DuplicateFinder.entries(files.toList())

    private fun play(system: String, path: String, count: Int = 1, last: Long? = 1_000L) =
        EsdePlay(system, path, path.substringAfterLast('/'), count, last)

    private fun ids(candidates: List<SpaceCandidate>) = candidates.map { it.title }

    // ---- ranking ----

    @Test
    fun `without play data every game is offered, biggest first, and says so`() {
        val entries = entriesOf(
            file("gba", "gba", "Small (USA).gba", 8 * mb),
            file("gba", "gba", "Big (USA).gba", 32 * mb),
            file("snes", "snes", "Mid (USA).sfc", 16 * mb)
        )
        val report = SpaceReclaim.build(entries, SpaceFacts())
        assertEquals(listOf("Big (USA)", "Mid (USA)", "Small (USA)"), ids(report.candidates))
        assertTrue(report.candidates.all { it.play == SpacePlay.UNKNOWN })
        assertFalse(report.hasPlayData)
        assertEquals(0, report.playedCount)
        assertEquals(56 * mb, report.bytes)
    }

    @Test
    fun `equal sizes rank the older file first and unknown ages last`() {
        val entries = entriesOf(
            file("gba", "gba", "Newer.gba", 8 * mb, modified = 2_000L),
            file("gba", "gba", "Older.gba", 8 * mb, modified = 1_000L),
            file("gba", "gba", "Undated.gba", 8 * mb),
            file("gba", "gba", "Largest.gba", 9 * mb, modified = 5_000L)
        )
        val report = SpaceReclaim.build(entries, SpaceFacts())
        assertEquals(listOf("Largest", "Older", "Newer", "Undated"), ids(report.candidates))
    }

    @Test
    fun `a download time stands in for a missing modified time`() {
        val entries = entriesOf(
            file("gba", "gba", "Game (USA).gba", 8 * mb),
            file("gba", "gba", "Other (USA).gba", 8 * mb, modified = 5_000L)
        )
        val facts = SpaceFacts(downloadedAt = mapOf("gba|game (usa)" to 1_000L))
        val report = SpaceReclaim.build(entries, facts)
        assertEquals(1_000L, report.candidates.first { it.title == "Game (USA)" }.addedAt)
        assertEquals(listOf("Game (USA)", "Other (USA)"), ids(report.candidates))
    }

    // ---- play data ----

    @Test
    fun `played games are left out and counted`() {
        val entries = entriesOf(
            file("gba", "gba", "Played (USA).gba", 32 * mb),
            file("gba", "gba", "Never (USA).gba", 16 * mb)
        )
        val facts = SpaceFacts(plays = listOf(play("gba", "./Played (USA).gba")))
        val report = SpaceReclaim.build(entries, facts)
        assertEquals(listOf("Never (USA)"), ids(report.candidates))
        assertEquals(SpacePlay.NEVER, report.candidates.single().play)
        assertEquals(1, report.playedCount)
        assertEquals(32 * mb, report.playedBytes)
        assertTrue(report.hasPlayData)
    }

    @Test
    fun `a console ES-DE has no records for is unknown, not never played`() {
        val entries = entriesOf(
            file("gba", "gba", "Played (USA).gba", 32 * mb),
            file("snes", "snes", "Other (USA).sfc", 16 * mb)
        )
        val facts = SpaceFacts(plays = listOf(play("gba", "./Played (USA).gba")))
        val report = SpaceReclaim.build(entries, facts)
        assertEquals(SpacePlay.UNKNOWN, report.candidates.single().play)
        assertEquals(1, report.unknownCount)
    }

    @Test
    fun `an ES-DE set up without any record leaves every game unknown`() {
        val entries = entriesOf(file("gba", "gba", "Game (USA).gba", 8 * mb))
        val report = SpaceReclaim.build(entries, SpaceFacts(plays = emptyList()))
        assertEquals(SpacePlay.UNKNOWN, report.candidates.single().play)
        assertFalse(report.hasPlayData)
    }

    @Test
    fun `a record matches by name ignoring case and by name without extension`() {
        val entries = entriesOf(
            file("gba", "gba", "Zelda (USA).gba", 8 * mb),
            file("gba", "gba", "Metroid (USA).zip", 8 * mb),
            file("gba", "gba", "Kirby (USA).gba", 8 * mb)
        )
        val facts = SpaceFacts(
            plays = listOf(
                play("gba", "./zelda (usa).GBA"),
                play("gba", "./Metroid (USA).gba")
            )
        )
        assertEquals(listOf("Kirby (USA)"), ids(SpaceReclaim.build(entries, facts).candidates))
    }

    @Test
    fun `playing one region does not mark another region as played`() {
        val entries = entriesOf(
            file("gba", "gba", "Game (USA).gba", 8 * mb),
            file("gba", "gba", "Game (Europe).gba", 8 * mb)
        )
        val facts = SpaceFacts(plays = listOf(play("gba", "./Game (USA).gba")))
        assertEquals(listOf("Game (Europe)"), ids(SpaceReclaim.build(entries, facts).candidates))
    }

    @Test
    fun `a system folder alias counts as the console`() {
        val entries = entriesOf(file("nintendo_gameboy_advance", "gba", "Game (USA).gba", 8 * mb))
        val facts = SpaceFacts(plays = listOf(play("gba", "./Game (USA).gba")))
        assertEquals(1, SpaceReclaim.build(entries, facts).playedCount)
    }

    @Test
    fun `a record of a disc matches every disc of a game by its title`() {
        val entries = entriesOf(
            file("sony_playstation", "psx", "Final Fantasy VII (USA) (Disc 1).cue", 1 * mb),
            file("sony_playstation", "psx", "Final Fantasy VII (USA) (Disc 1).bin", 600 * mb),
            file("sony_playstation", "psx", "Final Fantasy VII (USA) (Disc 2).cue", 1 * mb),
            file("sony_playstation", "psx", "Final Fantasy VII (USA) (Disc 2).bin", 600 * mb),
            file("sony_playstation", "psx", "Other Game (USA).chd", 300 * mb)
        )
        val facts = SpaceFacts(plays = listOf(play("psx", "./Final Fantasy VII (USA).m3u")))
        val report = SpaceReclaim.build(entries, facts)
        assertEquals(listOf("Other Game (USA)"), ids(report.candidates))
        assertEquals(2, report.playedCount)
    }

    @Test
    fun `a per-game folder matches a record inside that folder`() {
        val entries = entriesOf(
            file("sega_dreamcast", "dreamcast/Crazy Taxi (USA)", "disc.gdi", 1 * mb, level = 1),
            file("sega_dreamcast", "dreamcast/Crazy Taxi (USA)", "track01.bin", 500 * mb, level = 1),
            file("sega_dreamcast", "dreamcast/Shenmue (USA)", "disc.gdi", 1 * mb, level = 1),
            file("sega_dreamcast", "dreamcast/Shenmue (USA)", "track01.bin", 500 * mb, level = 1)
        )
        val facts = SpaceFacts(plays = listOf(play("dreamcast", "./Crazy Taxi (USA)/disc.gdi")))
        val report = SpaceReclaim.build(entries, facts)
        assertEquals(listOf("Shenmue (USA)"), ids(report.candidates))
    }

    // ---- what is never offered ----

    @Test
    fun `files outside a console folder, playlists, updates and BIOS are never offered`() {
        val entries = entriesOf(
            file(null, "loose", "Loose (USA).gba", 8 * mb),
            file("sony_playstation", "psx", "Game (Disc 1).m3u", 1_000L),
            file("nintendo_switch", "switch", "Game [UPD] (v1.1).nsp", 1 * gb),
            file("sony_playstation", "psx", "scph1001.bin", 512 * 1024L),
            file("sony_playstation", "psx", "Real Game (USA).chd", 300 * mb)
        )
        val report = SpaceReclaim.build(entries, SpaceFacts())
        assertEquals(listOf("Real Game (USA)"), ids(report.candidates))
    }

    // ---- protection ----

    @Test
    fun `favourites, collections, saves and achievements protect a game`() {
        val entries = entriesOf(
            file("gba", "gba", "Fav (USA).gba", 8 * mb),
            file("gba", "gba", "Listed (USA).zip", 8 * mb),
            file("gba", "gba", "Saved (USA).gba", 8 * mb),
            file("gba", "gba", "Trophy (USA).gba", 8 * mb),
            file("gba", "gba", "Plain (USA).gba", 8 * mb)
        )
        val facts = SpaceFacts(
            favourites = setOf(SpaceReclaim.key("gba", "Fav (USA).gba")),
            collections = setOf(SpaceReclaim.key("gba", "Listed (USA).gba")),
            saves = setOf(SpaceReclaim.key("gba", "saved%20(usa).zip")),
            achievements = setOf(SpaceReclaim.stemKey("gba", "Trophy (USA)"))
        )
        val byTitle = SpaceReclaim.build(entries, facts).candidates.associateBy { it.title }
        assertEquals(setOf(SpaceProtection.FAVOURITE), byTitle.getValue("Fav (USA)").protections)
        assertEquals(setOf(SpaceProtection.COLLECTION), byTitle.getValue("Listed (USA)").protections)
        assertEquals(setOf(SpaceProtection.SAVE), byTitle.getValue("Saved (USA)").protections)
        assertEquals(setOf(SpaceProtection.ACHIEVEMENT), byTitle.getValue("Trophy (USA)").protections)
        assertFalse(byTitle.getValue("Plain (USA)").isProtected)
    }

    @Test
    fun `a title with dots in it keeps its full name in the key`() {
        val entries = entriesOf(file("nes", "nes", "Super Mario Bros. 3 (USA).nes", 1 * mb))
        val facts = SpaceFacts(favourites = setOf(SpaceReclaim.key("nes", "Super Mario Bros. 3 (USA).nes")))
        assertTrue(SpaceReclaim.build(entries, facts).candidates.single().isProtected)
        assertEquals("nes|super mario bros. 3 (usa)", SpaceReclaim.stemKey("nes", "Super Mario Bros. 3 (USA)"))
        assertEquals("nes|super mario bros. 3 (usa)", SpaceReclaim.key("nes", "Super Mario Bros. 3 (USA).nes"))
    }

    @Test
    fun `any file of a multi-file game protects it`() {
        val entries = entriesOf(
            file("sony_playstation", "psx", "Game (USA).cue", 1_000L),
            file("sony_playstation", "psx", "Game (USA).bin", 500 * mb)
        )
        val facts = SpaceFacts(favourites = setOf(SpaceReclaim.key("sony_playstation", "Game (USA).cue")))
        assertTrue(SpaceReclaim.build(entries, facts).candidates.single().isProtected)
    }

    // ---- filter, console list, totals ----

    @Test
    fun `the console filter keeps the ranking and the console list leads with the most room`() {
        val entries = entriesOf(
            file("gba", "gba", "A (USA).gba", 8 * mb),
            file("snes", "snes", "B (USA).sfc", 32 * mb),
            file("gba", "gba", "C (USA).gba", 16 * mb),
            file("nes", "nes", "D (USA).nes", 1 * mb)
        )
        val candidates = SpaceReclaim.build(entries, SpaceFacts()).candidates
        assertEquals(listOf("C (USA)", "A (USA)"), ids(SpaceReclaim.forConsole(candidates, "gba")))
        assertEquals(4, SpaceReclaim.forConsole(candidates, null).size)
        val consoles = SpaceReclaim.consoles(candidates)
        assertEquals(listOf("snes", "gba", "nes"), consoles.map { it.consoleId })
        assertEquals(SpaceConsole("gba", 2, 24 * mb), consoles[1])
    }

    @Test
    fun `totals add up the selection only`() {
        val entries = entriesOf(
            file("gba", "gba", "A (USA).gba", 8 * mb),
            file("gba", "gba", "B (USA).gba", 16 * mb)
        )
        val candidates = SpaceReclaim.build(entries, SpaceFacts()).candidates
        val b = candidates.first { it.title == "B (USA)" }
        assertEquals(16 * mb, SpaceReclaim.totalBytes(candidates, setOf(b.id)))
        assertEquals(0L, SpaceReclaim.totalBytes(candidates, emptySet()))
    }

    // ---- "free N GB" ----

    private fun sized(vararg sizes: Pair<String, Long>, protectedTitles: Set<String> = emptySet()): List<SpaceCandidate> {
        val files = sizes.map { (name, size) -> file("snes", "snes", "$name.sfc", size) }
        val facts = SpaceFacts(favourites = protectedTitles.map { SpaceReclaim.key("snes", "$it.sfc") }.toSet())
        return SpaceReclaim.build(DuplicateFinder.entries(files), facts).candidates
    }

    @Test
    fun `select biggest takes the biggest first until the target is met`() {
        val candidates = sized("A (USA)" to 3 * gb, "B (USA)" to 2 * gb, "C (USA)" to 1 * gb, "D (USA)" to 512 * mb)
        val pick = SpaceReclaim.selectBiggest(candidates, 4 * gb)
        assertTrue(pick.reached)
        // 3 GB, then the smallest game that still covers the missing 1 GB.
        assertEquals(setOf("A (USA)", "C (USA)"), candidates.filter { it.id in pick.ids }.map { it.title }.toSet())
        assertEquals(4 * gb, pick.bytes)
    }

    @Test
    fun `the last pick is the smallest game that still reaches the target`() {
        val candidates = sized("A (USA)" to 4800 * mb, "B (USA)" to 4000 * mb, "C (USA)" to 1000 * mb, "D (USA)" to 400 * mb)
        val pick = SpaceReclaim.selectBiggest(candidates, 5 * gb)
        // 4800 MB + 400 MB reaches 5 GB (5120 MB); the 1000 MB game would also do but frees more than needed.
        assertEquals(setOf("A (USA)", "D (USA)"), candidates.filter { it.id in pick.ids }.map { it.title }.toSet())
        assertEquals(5200 * mb, pick.bytes)
    }

    @Test
    fun `one game that is enough is picked alone, the smallest such game`() {
        val candidates = sized("A (USA)" to 9 * gb, "B (USA)" to 6 * gb, "C (USA)" to 1 * gb)
        val pick = SpaceReclaim.selectBiggest(candidates, 5 * gb)
        assertEquals(setOf("B (USA)"), candidates.filter { it.id in pick.ids }.map { it.title }.toSet())
    }

    @Test
    fun `select biggest skips protected games`() {
        val candidates = sized("A (USA)" to 9 * gb, "B (USA)" to 6 * gb, "C (USA)" to 1 * gb, protectedTitles = setOf("A (USA)"))
        val pick = SpaceReclaim.selectBiggest(candidates, 5 * gb)
        assertEquals(setOf("B (USA)"), candidates.filter { it.id in pick.ids }.map { it.title }.toSet())
    }

    @Test
    fun `when everything is not enough all of it is picked and the target is not reached`() {
        val candidates = sized("A (USA)" to 1 * gb, "B (USA)" to 1 * gb, "C (USA)" to 5 * gb, protectedTitles = setOf("C (USA)"))
        val pick = SpaceReclaim.selectBiggest(candidates, 10 * gb)
        assertFalse(pick.reached)
        assertEquals(2, pick.ids.size)
        assertEquals(2 * gb, pick.bytes)
    }

    @Test
    fun `nothing to free picks nothing`() {
        val candidates = sized("A (USA)" to 1 * gb)
        val pick = SpaceReclaim.selectBiggest(candidates, 0L)
        assertTrue(pick.ids.isEmpty())
        assertTrue(pick.reached)
        assertTrue(SpaceReclaim.selectBiggest(emptyList(), 1 * gb).ids.isEmpty())
    }

    @Test
    fun `select all leaves out the protected games`() {
        val candidates = sized("A (USA)" to 1 * gb, "B (USA)" to 1 * gb, protectedTitles = setOf("B (USA)"))
        val ids = SpaceReclaim.unprotectedIds(candidates)
        assertEquals(listOf("A (USA)"), candidates.filter { it.id in ids }.map { it.title })
    }

    // ---- stepper and wish titles ----

    @Test
    fun `the stepper walks the steps and stops at both ends`() {
        assertEquals(10, SpaceReclaim.stepGb(5, +1))
        assertEquals(2, SpaceReclaim.stepGb(5, -1))
        assertEquals(1, SpaceReclaim.stepGb(1, -1))
        assertEquals(200, SpaceReclaim.stepGb(200, +1))
        // A value between two steps goes to the nearest step on the side asked for.
        assertEquals(10, SpaceReclaim.stepGb(7, +1))
        assertEquals(5, SpaceReclaim.stepGb(7, -1))
    }

    @Test
    fun `a suggested amount is a fifth of what is on offer, rounded down to a step`() {
        assertEquals(1, SpaceReclaim.suggestedFreeGb(0L))
        assertEquals(1, SpaceReclaim.suggestedFreeGb(3 * gb))
        assertEquals(10, SpaceReclaim.suggestedFreeGb(60 * gb))
        assertEquals(200, SpaceReclaim.suggestedFreeGb(5000L * gb))
    }

    @Test
    fun `a wish title drops tags, regions and discs`() {
        val candidate = sized("Final Fantasy VII (USA) (Disc 1) [!]" to 1 * gb).single()
        assertEquals("Final Fantasy VII", SpaceReclaim.wishTitle(candidate))
    }
}
