package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuePlayingTest {

    private fun save(
        id: Int,
        romId: Int,
        millis: Long?,
        kind: SaveKind = SaveKind.SAVE,
        emulator: String? = "mGBA",
        device: String? = null,
        missing: Boolean = false
    ) = CloudSaveEntry(
        kind, id, romId, "Game$romId.srm", emulator, millis?.toString().orEmpty(), millis, 10, "/d/$id",
        missingFromFs = missing, device = device
    )

    @Test fun `newest save per game first, one entry per rom`() {
        val recent = ContinuePlaying.recentServerSaves(
            listOf(
                save(1, romId = 42, millis = 1_000),
                save(2, romId = 42, millis = 5_000, kind = SaveKind.STATE, device = "Thor"),
                save(3, romId = 7, millis = 3_000),
                save(4, romId = 9, millis = 9_000, missing = true),
                save(5, romId = 11, millis = null),
                save(6, romId = 12, millis = 3_000)
            )
        )
        assertEquals(listOf(42, 7, 12), recent.map { it.romId })
        assertEquals(RecentSave(42, 5_000, "Thor", SaveKind.STATE), recent.first())
    }

    @Test fun `the device or emulator comes from an older save when the newest does not say`() {
        val recent = ContinuePlaying.recentServerSaves(
            listOf(
                save(1, romId = 42, millis = 1_000, device = "Odin"),
                save(2, romId = 42, millis = 5_000, kind = SaveKind.STATE, emulator = null)
            )
        ).single()
        assertEquals("Odin", recent.via)
        assertEquals(SaveKind.STATE, recent.kind)
        assertNull(ContinuePlaying.recentServerSaves(listOf(save(1, 42, 1_000, emulator = null))).single().via)
    }

    @Test fun `the shelf is capped`() {
        val many = (1..30).map { save(it, romId = it, millis = it * 1_000L) }
        val recent = ContinuePlaying.recentServerSaves(many, limit = 5)
        assertEquals(listOf(30, 29, 28, 27, 26), recent.map { it.romId })
        assertTrue(ContinuePlaying.recentServerSaves(many, limit = -1).isEmpty())
    }

    @Test fun `rom ids map back to every library key of the game`() {
        val games = mapOf("nintendo_gba|pokemon emerald (usa)" to 42, "gba_hacks|pokemon emerald (usa)" to 42, "snes|zelda" to 7)
        val byRom = ContinuePlaying.keysByRomId(games) { it }
        assertEquals(listOf("gba_hacks|pokemon emerald (usa)", "nintendo_gba|pokemon emerald (usa)"), byRom[42])
        assertEquals(listOf("snes|zelda"), byRom[7])
        assertNull(byRom[1])
    }

    @Test fun `keys split into console and name`() {
        assertEquals("nintendo_gba" to "pokemon emerald (usa)", ContinuePlaying.splitKey("nintendo_gba|pokemon emerald (usa)"))
        assertEquals("arcade" to "a|b", ContinuePlaying.splitKey("arcade|a|b"))
        assertNull(ContinuePlaying.splitKey("|name"))
        assertNull(ContinuePlaying.splitKey("console|"))
        assertNull(ContinuePlaying.splitKey("no bar"))
    }

    private data class Row(val console: String, val file: String)

    @Test fun `a key finds its library row whatever the extension or encoding`() {
        val rows = listOf(
            Row("nintendo_snes", "Pokemon Emerald (USA).zip"),
            Row("nintendo_gba", "Pokemon%20Emerald%20(USA).zip"),
            Row("nintendo_gba", "Pokemon Emerald (USA).7z")
        )
        val found = ContinuePlaying.rowForKey("nintendo_gba|pokemon emerald (usa)", rows, { it.console }, { it.file })
        assertSame(rows[1], found)
        assertNull(ContinuePlaying.rowForKey("nintendo_gba|ruby", rows, { it.console }, { it.file }))
    }

    private fun play(system: String, path: String, last: Long?) = EsdePlay(system, path, path, 1, last)

    @Test fun `es-de plays are newest first, once per game, never-played ones left out`() {
        val plays = ContinuePlaying.recentPlays(
            listOf(
                play("gba", "./Pokemon Emerald (USA).zip", 1_000),
                play("gba", "./sub/Pokemon Emerald (USA).zip", 4_000),
                play("snes", "./Zelda.sfc", 3_000),
                play("snes", "./Never.sfc", null),
                play("snes", "./Zero.sfc", 0),
                play("n64", "", 9_000)
            )
        )
        assertEquals(listOf("./sub/Pokemon Emerald (USA).zip", "./Zelda.sfc"), plays.map { it.path })
        assertEquals("Game (USA).zip", ContinuePlaying.playFileName(play("gba", ".\\sub\\Game (USA).zip ", 1)))
    }

    @Test fun `an es-de play finds its row by system and name`() {
        val rows = listOf(
            Row("nintendo_gba", "Pokemon Emerald (USA).zip"),
            Row("nintendo_snes", "Pokemon Emerald (USA).zip"),
            Row("nintendo_snes", "Zelda.7z"),
            Row("sega_md", "Sonic.md")
        )
        val systems = mapOf("gba" to "nintendo_gba", "snes" to "nintendo_snes")
        val matches = { console: String, system: String -> systems[system] == console }
        fun find(p: EsdePlay) = ContinuePlaying.rowForPlay(p, rows, { it.console }, { it.file }, matches)

        assertSame(rows[1], find(play("snes", "./Pokemon Emerald (USA).zip", 1)))
        // Extracted on the device (.sfc) while the library has the archive (.7z).
        assertSame(rows[2], find(play("snes", "./Zelda.sfc", 1)))
        // Unknown system, but only one row has that exact name.
        assertSame(rows[3], find(play("megadrive", "./Sonic.md", 1)))
        // Unknown system and two rows of that name: no guess.
        assertNull(find(play("handheld", "./Pokemon Emerald (USA).zip", 1)))
        assertNull(find(play("gba", "./", 1)))
    }

    @Test fun `shelf entries keep the first of each game`() {
        val entries = listOf(
            ShelfEntry("nintendo_gba", "Pokemon Emerald (USA).zip", 5_000, "Thor"),
            ShelfEntry("nintendo_gba", "Pokemon Emerald (USA).7z", 4_000),
            ShelfEntry("nintendo_snes", "Zelda.sfc", 3_000)
        )
        assertEquals(listOf(entries[0], entries[2]), ContinuePlaying.dedupe(entries))
        assertEquals(listOf(entries[0]), ContinuePlaying.dedupe(entries, limit = 1))
    }
}
