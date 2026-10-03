package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFinderTest {

    private fun file(name: String, folder: String = ".../ROMs/gba", scope: String = "nintendo_gameboy_advance", size: Long = 100) =
        DiskFile(scope, scope.takeUnless { it.isEmpty() || it.startsWith("folder:") }, folder, name, size, "content://$folder/$name")

    @Test
    fun regionVariantsAreReported() {
        val groups = DuplicateFinder.find(listOf(
            file("Golden Sun (USA).gba", size = 8_000),
            file("Golden Sun (Europe) (En,Fr,De).gba", size = 8_000),
            file("Advance Wars (USA).gba")
        ))
        assertEquals(1, groups.size)
        assertEquals(DuplicateGroup.Kind.VARIANT, groups[0].kind)
        assertEquals("Golden Sun", groups[0].title)
        assertEquals(8_000L, groups[0].reclaimable)
    }

    @Test
    fun sameFileInTwoFoldersIsIdentical() {
        val groups = DuplicateFinder.find(listOf(
            file("Metroid Fusion (USA).gba", folder = ".../ROMs/gba"),
            file("Metroid Fusion (USA).gba", folder = ".../ROMs/Game Boy Advance")
        ))
        assertEquals(1, groups.size)
        assertEquals(DuplicateGroup.Kind.IDENTICAL, groups[0].kind)
    }

    @Test
    fun discsOfOneGameAreNotDuplicates() {
        val groups = DuplicateFinder.find(listOf(
            file("Final Fantasy VII (USA) (Disc 1).chd", scope = "sony_playstation"),
            file("Final Fantasy VII (USA) (Disc 2).chd", scope = "sony_playstation"),
            file("Final Fantasy VII (USA) (Disc 3).chd", scope = "sony_playstation")
        ))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun cueBinAndTracksCountAsOneGame() {
        val files = listOf(
            file("Rayman (USA).cue", scope = "sony_playstation"),
            file("Rayman (USA) (Track 1).bin", scope = "sony_playstation"),
            file("Rayman (USA) (Track 2).bin", scope = "sony_playstation")
        )
        assertEquals(1, DuplicateFinder.entries(files).size)
        assertTrue(DuplicateFinder.find(files).isEmpty())
    }

    @Test
    fun differentConsolesNeverMatch() {
        val groups = DuplicateFinder.find(listOf(
            file("Tetris (World).gb", scope = "nintendo_gameboy"),
            file("Tetris (World).nes", scope = "nintendo_nes")
        ))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun shortcutsArtworkAndSavesAreIgnored() {
        assertFalse(DuplicateFinder.isGameFile("★ Search for more games....dgmtx"))
        assertFalse(DuplicateFinder.isGameFile("Golden Sun (USA).sav"))
        assertFalse(DuplicateFinder.isGameFile("cover.png"))
        assertFalse(DuplicateFinder.isGameFile(".nomedia"))
        assertTrue(DuplicateFinder.isGameFile("Golden Sun (USA).gba"))
        val groups = DuplicateFinder.find(listOf(
            file("Golden Sun (USA).gba"),
            file("Golden Sun (USA).sav")
        ))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun versionSuffixDoesNotSplitTitles() {
        assertEquals(DuplicateFinder.titleKey("Pokemon Emerald v1.1"), DuplicateFinder.titleKey("Pokémon Emerald (USA)"))
    }

    @Test
    fun largestCopyLeadsTheGroup() {
        val groups = DuplicateFinder.find(listOf(
            file("Zelda (USA).gba", size = 10),
            file("Zelda (Europe).gba", size = 30),
            file("Zelda (Japan).gba", size = 20)
        ))
        assertEquals(listOf(30L, 20L, 10L), groups[0].entries.map { it.size })
        assertEquals(30L, groups[0].reclaimable)
    }

    @Test
    fun looseFilesOfDifferentSystemsDoNotMatch() {
        val groups = DuplicateFinder.find(listOf(
            file("Tetris (World).gb", folder = ".../ROMs", scope = ""),
            file("Tetris (World).nes", folder = ".../ROMs", scope = ""),
            file("Tetris (Japan).gb", folder = ".../ROMs", scope = "")
        ))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].entries.size)
    }
}
