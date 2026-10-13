package com.cortinadev.dogmatix.util

import org.junit.Assert.*
import org.junit.Test

class FrontendCoversTest {
    private fun game(system: String, file: String, console: String = "nintendo_gameboy_advance") =
        FrontendCovers.Game(console, system, file, LibraryKeys.baseName(file))

    @Test fun `cover names follow the chosen layout and are always png`() {
        assertEquals("Game (Europe).png", FrontendCovers.coverName("Game (Europe).gba", FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertEquals("Game (Europe).gba.png", FrontendCovers.coverName("Game (Europe).gba", FrontendCovers.Layout.NAME_WITH_EXTENSION))
        assertEquals("Disc Game.png", FrontendCovers.coverName("Disc Game.m3u", FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertEquals("gba/Game.png", FrontendCovers.coverPath(game("gba", "Game.gba"), FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertEquals(FrontendCovers.Layout.NAME_WITHOUT_EXTENSION, FrontendCovers.Layout.of(null))
        assertEquals(FrontendCovers.Layout.NAME_WITH_EXTENSION, FrontendCovers.Layout.of("full"))
    }

    @Test fun `unsafe or too long names are refused`() {
        assertNull(FrontendCovers.coverName(".dogmatix-write-1.part", FrontendCovers.Layout.NAME_WITH_EXTENSION))
        assertNull(FrontendCovers.coverName("a:b.gba", FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertNull(FrontendCovers.coverName("x".repeat(260) + ".gba", FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertNull(FrontendCovers.coverPath(game("..", "Game.gba"), FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertNull(FrontendCovers.coverPath(game("a\\b", "Game.gba"), FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
    }

    @Test fun `an image already there is kept, whatever its extension`() {
        val games = listOf(game("gba", "One.gba"), game("gba", "Two.gba"), game("gba", "Three.gba"), game("snes", "Four.sfc", "nintendo_snes"))
        val existing = mapOf("GBA" to setOf("one.png", "two.jpg", "notes.txt"))
        val plan = FrontendCovers.plan(games, FrontendCovers.Layout.NAME_WITHOUT_EXTENSION, existing, overwrite = false)
        assertEquals(listOf("gba/Three.png", "snes/Four.png"), plan.items.map { it.path })
        assertEquals(2, plan.kept)
        assertEquals(2, plan.systems)
        assertTrue(plan.items.none { it.replaces })
        assertEquals("gba/Three.png", plan.example)
    }

    @Test fun `replacing only touches an image of exactly the same name`() {
        val games = listOf(game("gba", "One.gba"), game("gba", "Two.gba"))
        val existing = mapOf("gba" to setOf("one.png", "two.jpg"))
        val plan = FrontendCovers.plan(games, FrontendCovers.Layout.NAME_WITHOUT_EXTENSION, existing, overwrite = true)
        assertEquals(0, plan.kept)
        assertEquals(mapOf("gba/One.png" to true, "gba/Two.png" to false), plan.items.associate { it.path to it.replaces })
    }

    @Test fun `the full-name layout looks for its own names`() {
        val games = listOf(game("gba", "One.gba"))
        val existing = mapOf("gba" to setOf("one.png"))
        val plan = FrontendCovers.plan(games, FrontendCovers.Layout.NAME_WITH_EXTENSION, existing, overwrite = false)
        assertEquals(listOf("gba/One.gba.png"), plan.items.map { it.path })
        val again = FrontendCovers.plan(games, FrontendCovers.Layout.NAME_WITH_EXTENSION, mapOf("gba" to setOf("one.gba.jpg")), overwrite = false)
        assertTrue(again.items.isEmpty())
        assertEquals(1, again.kept)
    }

    @Test fun `two games that would share a cover file write it once`() {
        val games = listOf(game("gba", "Game.gba"), game("gba", "Game.zip"), game("gba", "bad:name.gba"))
        val plan = FrontendCovers.plan(games, FrontendCovers.Layout.NAME_WITHOUT_EXTENSION, emptyMap(), overwrite = false)
        assertEquals(1, plan.items.size)
        assertEquals(2, plan.skipped)
        // With the extension kept they are two covers.
        assertEquals(2, FrontendCovers.plan(games, FrontendCovers.Layout.NAME_WITH_EXTENSION, emptyMap(), overwrite = false).items.size)
    }

    @Test fun `games come from console folders, one per game, without hidden consoles`() {
        fun file(folder: String, name: String, console: String?) = DiskFile("root", console, folder, name, 1_000, "content://$folder/$name")
        val files = listOf(
            file("ROMs/gba", "Advance Wars (Europe).gba", "nintendo_gameboy_advance"),
            file("ROMs/psx", "Big Game (Disc 1).cue", "sony_playstation"),
            file("ROMs/psx", "Big Game (Disc 1).bin", "sony_playstation"),
            file("ROMs/psx", "Big Game.m3u", "sony_playstation"),
            file("ROMs/snes", "Hidden (USA).sfc", "nintendo_super_nintendo_entertainment_system"),
            file("ROMs/Misc", "Loose (USA).gba", "nintendo_gameboy_advance")
        )
        val found = FrontendCovers.gamesOf(files, hiddenConsoles = setOf("nintendo_super_nintendo_entertainment_system"))
        val bySystem = found.games.groupBy({ it.system }, { it.fileName })
        assertEquals(listOf("Advance Wars (Europe).gba"), bySystem["gba"])
        assertTrue(bySystem["psx"].orEmpty().all { it.endsWith(".m3u") || it.endsWith(".cue") })
        assertNull(bySystem["snes"])
        assertEquals(1, found.outside)
    }

    @Test fun `png detection and the size cap`() {
        assertTrue(FrontendCovers.isPng(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertFalse(FrontendCovers.isPng(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertFalse(FrontendCovers.isPng(ByteArray(0)))
        assertEquals(1, FrontendCovers.sampleSize(800, 1100))
        assertEquals(2, FrontendCovers.sampleSize(4000, 3000))
        assertEquals(8, FrontendCovers.sampleSize(3000, 9000))
    }

    @Test fun `settings keys are typed for backups`() {
        assertEquals("s", SettingSchema.expectedType(FrontendCovers.folderKey(FrontendCovers.Target.IISU)))
        assertEquals("s", SettingSchema.expectedType(FrontendCovers.layoutKey(FrontendCovers.Target.DAIJISHO)))
        assertEquals("b", SettingSchema.expectedType(FrontendCovers.overwriteKey(FrontendCovers.Target.DAIJISHO)))
    }
}
