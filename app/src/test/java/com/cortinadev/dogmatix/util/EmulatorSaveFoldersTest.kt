package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.SaveSyncPlanner.RomCandidate
import com.cortinadev.dogmatix.util.SaveSyncPlanner.RomMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorSaveFoldersTest {
    private val drastic = EmulatorSaveFolder("DraStic (standalone)", setOf("nds"), "content://tree/drastic")

    @Test fun pathsPointIntoTheEmulatorsOwnFolder() {
        assertEquals(drastic to "Game (USA).dsv", EmulatorSaveFolders.locate("DraStic (standalone)/Game (USA).dsv", listOf(drastic)))
        assertEquals(drastic to "sub/Game.dsv", EmulatorSaveFolders.locate("drastic (STANDALONE)/sub/Game.dsv", listOf(drastic)))
        assertNull(EmulatorSaveFolders.locate("mGBA/Game.srm", listOf(drastic)))
        assertNull(EmulatorSaveFolders.locate("Game.srm", listOf(drastic)))
    }

    @Test fun jsonRoundTripDropsBrokenEntriesAndDuplicates() {
        val json = EmulatorSaveFolders.toJson(listOf(drastic, drastic.copy(uri = "content://other")))
        assertEquals(listOf(drastic), EmulatorSaveFolders.fromJson(json))
        assertEquals(emptyList<EmulatorSaveFolder>(), EmulatorSaveFolders.fromJson("""[{"label":"","uri":"x"},{"label":"A"}]"""))
        assertEquals(emptyList<EmulatorSaveFolder>(), EmulatorSaveFolders.fromJson("not json"))
        assertEquals(emptyList<EmulatorSaveFolder>(), EmulatorSaveFolders.fromJson(null))
    }

    @Test fun labelsStayUniqueAndUsableAsRommEmulatorNames() {
        assertEquals("DraStic", EmulatorSaveFolders.uniqueLabel("DraStic", listOf("mGBA")))
        assertEquals("DraStic 2", EmulatorSaveFolders.uniqueLabel("DraStic", listOf("drastic")))
        assertEquals("DraStic 3", EmulatorSaveFolders.uniqueLabel("DraStic", listOf("DraStic", "DraStic 2")))
        assertEquals("ab", EmulatorSaveFolders.cleanLabel(" a/b "))
        EmulatorSaveFolders.presets.forEach { p ->
            assertEquals(p.label, EmulatorSaveFolders.cleanLabel(p.label))
            assertEquals(p.label, SaveSyncPlanner.emulatorFor(LocalSaveFile(SaveKind.SAVE, "${p.label}/x.sav", 1, 1)))
        }
    }

    @Test fun theFoldersPlatformPicksTheGameWhenTheNameIsOnSeveralPlatforms() {
        val candidates = listOf(RomCandidate(1, "Tetris.nds", "nds"), RomCandidate(2, "Tetris.gb", "gb"))
        val plain = LocalSaveFile(SaveKind.SAVE, "DraStic (standalone)/Tetris.dsv", 1, 1)
        assertEquals(RomMatch.Ambiguous, SaveSyncPlanner.matchRom(plain, candidates))
        assertEquals(RomMatch.Found(1), SaveSyncPlanner.matchRom(plain.copy(platformHints = setOf("nds")), candidates))
    }

    @Test fun withoutAFolderOfItsOwnOnlyTheEmulatorsServerSavesComeDown() {
        val remotes = listOf(
            RemoteSaveFile(SaveKind.SAVE, 1, 10, "Tetris.dsv", "DraStic (standalone)", "2026-10-01T10:00:00+00:00", 1, "/a"),
            RemoteSaveFile(SaveKind.SAVE, 2, 11, "Zelda.srm", "Snes9x", "2026-10-01T10:00:00+00:00", 1, "/b")
        )
        val actions = SaveSyncPlanner.plan(
            locals = emptyList(), remotes = remotes, records = emptyList(),
            topFolders = mapOf(SaveKind.SAVE to setOf("DraStic (standalone)")),
            noRootFolder = setOf(SaveKind.SAVE)
        )
        val download = actions.single()
        assertTrue(download is SaveSyncAction.Download)
        assertEquals("DraStic (standalone)/Tetris.dsv", (download as SaveSyncAction.Download).path)
    }
}
