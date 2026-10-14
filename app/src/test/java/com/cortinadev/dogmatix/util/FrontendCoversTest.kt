package com.cortinadev.dogmatix.util

import org.junit.Assert.*
import org.junit.Test

class FrontendCoversTest {
    private fun game(system: String, file: String, console: String = "nintendo_gameboy_advance") =
        FrontendCovers.Game(console, system, file, LibraryKeys.baseName(file))

    @Test fun `cover names follow the chosen layout and are always png`() {
        val gba = game("gba", "Game (Europe).gba")
        assertEquals("gba/Game (Europe).png", FrontendCovers.coverPath(gba, FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertEquals("gba/Game (Europe).gba.png", FrontendCovers.coverPath(gba, FrontendCovers.Layout.NAME_WITH_EXTENSION))
        assertEquals("gba/Disc Game.png", FrontendCovers.coverPath(game("gba", "Disc Game.m3u"), FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertEquals(FrontendCovers.Layout.NAME_WITHOUT_EXTENSION, FrontendCovers.Layout.of(null))
        assertEquals(FrontendCovers.Layout.NAME_WITH_EXTENSION, FrontendCovers.Layout.of("full"))
    }

    @Test fun `every layout puts the cover where its launcher style expects it`() {
        val gba = game("gba", "Game (Europe).gba")
        val expected = mapOf(
            FrontendCovers.Layout.NAME_WITHOUT_EXTENSION to FrontendCovers.Place("gba", "Game (Europe).png"),
            FrontendCovers.Layout.NAME_WITH_EXTENSION to FrontendCovers.Place("gba", "Game (Europe).gba.png"),
            FrontendCovers.Layout.FLAT to FrontendCovers.Place("", "Game (Europe).png"),
            FrontendCovers.Layout.NAMED_BOXARTS to FrontendCovers.Place("gba/Named_Boxarts", "Game (Europe).png"),
            FrontendCovers.Layout.PEGASUS to FrontendCovers.Place("Game (Europe)", "boxFront.png"),
            FrontendCovers.Layout.GAMELIST_IMAGES to FrontendCovers.Place("gba/images", "Game (Europe)-image.png")
        )
        assertEquals(FrontendCovers.Layout.entries.toSet(), expected.keys)
        expected.forEach { (layout, place) -> assertEquals(layout.name, place, FrontendCovers.place(gba, layout)) }
        assertEquals("Game (Europe).png", FrontendCovers.coverPath(gba, FrontendCovers.Layout.FLAT))
        assertEquals("Game (Europe)/boxFront.png", FrontendCovers.coverPath(gba, FrontendCovers.Layout.PEGASUS))
        // Only the flat and Pegasus layouts leave the console folders out.
        assertEquals(
            setOf(FrontendCovers.Layout.FLAT, FrontendCovers.Layout.PEGASUS),
            FrontendCovers.Layout.entries.filterNot { it.perSystem }.toSet()
        )
    }

    @Test fun `layout keys are stable and each launcher offers its own layouts`() {
        assertEquals(listOf("stem", "full", "flat", "boxarts", "pegasus", "gamelist"), FrontendCovers.Layout.entries.map { it.key })
        FrontendCovers.Layout.entries.forEach { assertEquals(it, FrontendCovers.Layout.of(it.key)) }
        assertEquals(FrontendCovers.Layout.entries, FrontendCovers.layoutsFor(FrontendCovers.Target.OTHER))
        for (t in listOf(FrontendCovers.Target.IISU, FrontendCovers.Target.DAIJISHO)) {
            assertEquals(
                listOf(FrontendCovers.Layout.NAME_WITHOUT_EXTENSION, FrontendCovers.Layout.NAME_WITH_EXTENSION, FrontendCovers.Layout.FLAT),
                FrontendCovers.layoutsFor(t)
            )
        }
        FrontendCovers.Target.entries.forEach { assertEquals(FrontendCovers.Layout.NAME_WITHOUT_EXTENSION, FrontendCovers.layoutsFor(it).first()) }
        // A stored layout the launcher does not offer falls back to its default.
        assertEquals(FrontendCovers.Layout.NAME_WITHOUT_EXTENSION, FrontendCovers.layoutOf(FrontendCovers.Target.IISU, "pegasus"))
        assertEquals(FrontendCovers.Layout.FLAT, FrontendCovers.layoutOf(FrontendCovers.Target.DAIJISHO, "flat"))
        assertEquals(FrontendCovers.Layout.PEGASUS, FrontendCovers.layoutOf(FrontendCovers.Target.OTHER, "pegasus"))
        assertEquals(FrontendCovers.Layout.NAME_WITHOUT_EXTENSION, FrontendCovers.layoutOf(FrontendCovers.Target.OTHER, "unknown"))
        assertEquals(FrontendCovers.Target.OTHER, FrontendCovers.Target.of("other"))
        assertEquals(FrontendCovers.Target.IISU, FrontendCovers.Target.of(null))
    }

    @Test fun `the RetroArch layout applies libretro's character rule`() {
        val game = game("snes", "Tom & Jerry (USA).sfc", "nintendo_snes")
        assertEquals("snes/Named_Boxarts/Tom _ Jerry (USA).png", FrontendCovers.coverPath(game, FrontendCovers.Layout.NAMED_BOXARTS))
        // The other layouts keep the file name as it is.
        assertEquals("snes/Tom & Jerry (USA).png", FrontendCovers.coverPath(game, FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertEquals("Tom & Jerry (USA)/boxFront.png", FrontendCovers.coverPath(game, FrontendCovers.Layout.PEGASUS))
    }

    @Test fun `unsafe or too long names are refused`() {
        assertNull(FrontendCovers.gameName(".dogmatix-write-1.part", FrontendCovers.Layout.NAME_WITH_EXTENSION))
        assertNull(FrontendCovers.gameName("a:b.gba", FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertNull(FrontendCovers.coverPath(game("gba", "x".repeat(260) + ".gba"), FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertNull(FrontendCovers.coverPath(game("..", "Game.gba"), FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertNull(FrontendCovers.coverPath(game("a\\b", "Game.gba"), FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertNull(FrontendCovers.coverPath(game("gba", "a:b.gba"), FrontendCovers.Layout.FLAT))
        assertNull(FrontendCovers.coverPath(game("gba", "a:b.gba"), FrontendCovers.Layout.PEGASUS))
        assertNull(FrontendCovers.coverPath(game("gba", ".."), FrontendCovers.Layout.PEGASUS))
        // libretro turns ':' into '_', so the RetroArch layout can still name it.
        assertEquals("gba/Named_Boxarts/a_b.png", FrontendCovers.coverPath(game("gba", "a:b.gba"), FrontendCovers.Layout.NAMED_BOXARTS))
        // The console folder only matters to the layouts that use it.
        assertNull(FrontendCovers.coverPath(game("..", "Game.gba"), FrontendCovers.Layout.GAMELIST_IMAGES))
        assertNull(FrontendCovers.coverPath(game("..", "Game.gba"), FrontendCovers.Layout.NAMED_BOXARTS))
        assertEquals("Game.png", FrontendCovers.coverPath(game("..", "Game.gba"), FrontendCovers.Layout.FLAT))
        // 248 bytes fit as a name, but not with the "-image.png" of the EmulationStation layout.
        val long = game("gba", "y".repeat(248) + ".gba")
        assertNotNull(FrontendCovers.coverPath(long, FrontendCovers.Layout.NAME_WITHOUT_EXTENSION))
        assertNotNull(FrontendCovers.coverPath(long, FrontendCovers.Layout.PEGASUS))
        assertNull(FrontendCovers.coverPath(long, FrontendCovers.Layout.GAMELIST_IMAGES))
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

    @Test fun `a cover already there is found in each layout's own folder`() {
        val games = listOf(game("gba", "One.gba"), game("gba", "Two.gba"))
        fun keptAndWritten(layout: FrontendCovers.Layout, existing: Map<String, Set<String>>) =
            FrontendCovers.plan(games, layout, existing, overwrite = false).let { it.kept to it.items.map { i -> i.path } }

        assertEquals(1 to listOf("Two.png"), keptAndWritten(FrontendCovers.Layout.FLAT, mapOf("" to setOf("one.webp"))))
        assertEquals(1 to listOf("gba/Named_Boxarts/Two.png"), keptAndWritten(FrontendCovers.Layout.NAMED_BOXARTS, mapOf("GBA/named_boxarts" to setOf("one.png"))))
        assertEquals(1 to listOf("Two/boxFront.png"), keptAndWritten(FrontendCovers.Layout.PEGASUS, mapOf("one" to setOf("boxfront.jpg"), "two" to setOf("screenshot.png"))))
        assertEquals(1 to listOf("gba/images/Two-image.png"), keptAndWritten(FrontendCovers.Layout.GAMELIST_IMAGES, mapOf("gba/images" to setOf("one-image.jpg", "two.png"))))
        // An image in another layout's folder is not this layout's cover.
        assertEquals(0, keptAndWritten(FrontendCovers.Layout.NAMED_BOXARTS, mapOf("gba" to setOf("one.png", "two.png"))).first)
        assertEquals(0, keptAndWritten(FrontendCovers.Layout.FLAT, mapOf("gba" to setOf("one.png"))).first)
        // Replacing touches only an image of exactly the same name, here too.
        val replace = FrontendCovers.plan(games, FrontendCovers.Layout.PEGASUS, mapOf("One" to setOf("boxfront.png"), "Two" to setOf("boxfront.jpg")), overwrite = true)
        assertEquals(mapOf("One/boxFront.png" to true, "Two/boxFront.png" to false), replace.items.associate { it.path to it.replaces })
    }

    @Test fun `two games that would share a cover file write it once`() {
        val games = listOf(game("gba", "Game.gba"), game("gba", "Game.zip"), game("gba", "bad:name.gba"))
        val plan = FrontendCovers.plan(games, FrontendCovers.Layout.NAME_WITHOUT_EXTENSION, emptyMap(), overwrite = false)
        assertEquals(1, plan.items.size)
        assertEquals(2, plan.skipped)
        assertEquals(0, plan.shared)
        // With the extension kept they are two covers.
        assertEquals(2, FrontendCovers.plan(games, FrontendCovers.Layout.NAME_WITH_EXTENSION, emptyMap(), overwrite = false).items.size)
    }

    @Test fun `in one folder for all consoles, games of the same name on two consoles are skipped`() {
        val games = listOf(
            game("gb", "Tetris.gb", "nintendo_gameboy"),
            game("nes", "Tetris.nes", "nintendo_nes"),
            game("gba", "Advance Wars.gba"),
            game("gba", "Advance Wars.zip"),
            game("nes", "Zelda.nes", "nintendo_nes")
        )
        val flat = FrontendCovers.plan(games, FrontendCovers.Layout.FLAT, emptyMap(), overwrite = false)
        // Neither Tetris cover would be right for the other console; the same game twice on one console is written once.
        assertEquals(listOf("Advance Wars.png", "Zelda.png"), flat.items.map { it.path })
        assertEquals(3, flat.skipped)
        assertEquals(2, flat.shared)
        assertEquals("Advance Wars.png", flat.example)
        // Case does not separate them either.
        val cased = FrontendCovers.plan(listOf(game("gb", "TETRIS.gb", "nintendo_gameboy"), game("nes", "tetris.nes", "nintendo_nes")), FrontendCovers.Layout.FLAT, emptyMap(), overwrite = false)
        assertEquals(0 to 2, cased.items.size to cased.shared)
        // Even with replacing on, a file both consoles would use is left alone.
        val again = FrontendCovers.plan(games, FrontendCovers.Layout.FLAT, mapOf("" to setOf("tetris.png")), overwrite = true)
        assertTrue(again.items.none { it.path == "Tetris.png" })
        // Pegasus folders are shared the same way.
        val pegasus = FrontendCovers.plan(games, FrontendCovers.Layout.PEGASUS, emptyMap(), overwrite = false)
        assertEquals(listOf("Advance Wars/boxFront.png", "Zelda/boxFront.png"), pegasus.items.map { it.path })
        assertEquals(2, pegasus.shared)
        // With console folders every console keeps its own Tetris.
        for (layout in FrontendCovers.Layout.entries.filter { it.perSystem }) {
            val plan = FrontendCovers.plan(games, layout, emptyMap(), overwrite = false)
            val withExtension = layout == FrontendCovers.Layout.NAME_WITH_EXTENSION
            assertEquals(layout.name, if (withExtension) 5 else 4, plan.items.size)
            assertEquals(layout.name, 0, plan.shared)
            assertEquals(layout.name, if (withExtension) 0 else 1, plan.skipped)
        }
    }

    @Test fun `the sample game can be named in every layout`() {
        val games = listOf(game("gba", "a:b.gba"), game("gba", "Beta.gba"), game("..", "Alpha.gba"))
        assertEquals("Beta.gba", FrontendCovers.sampleOf(games)?.fileName)
        assertEquals("a:b.gba", FrontendCovers.sampleOf(listOf(game("gba", "a:b.gba")))?.fileName)
        assertNull(FrontendCovers.sampleOf(emptyList()))
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
        // The 3.1.0 keys keep their names, so a restored backup still finds them.
        assertEquals("covers31_iisu_layout", FrontendCovers.layoutKey(FrontendCovers.Target.IISU))
        assertEquals("covers31_other_folder", FrontendCovers.folderKey(FrontendCovers.Target.OTHER))
        for (t in FrontendCovers.Target.entries) {
            assertEquals("s", SettingSchema.expectedType(FrontendCovers.folderKey(t)))
            assertEquals("s", SettingSchema.expectedType(FrontendCovers.layoutKey(t)))
            assertEquals("b", SettingSchema.expectedType(FrontendCovers.overwriteKey(t)))
            assertEquals("s", SettingSchema.expectedType("personal:child:" + FrontendCovers.layoutKey(t)))
        }
        assertFalse(SettingSchema.compatible(FrontendCovers.overwriteKey(FrontendCovers.Target.OTHER), "true"))
        assertTrue(SettingSchema.compatible(FrontendCovers.layoutKey(FrontendCovers.Target.OTHER), "pegasus"))
    }
}
