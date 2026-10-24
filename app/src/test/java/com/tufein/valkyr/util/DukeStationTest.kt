package com.tufein.valkyr.util

import com.tufein.valkyr.data.service.GameHandler
import com.tufein.valkyr.data.service.GameLaunch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DukeStationTest {
    private val doc = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fpsx%2FGame.chd"
    private val file = FileRef("Game.chd", doc, "/storage/emulated/0/ROMs/psx/Game.chd")
    private val duke = EmulatorCatalog.byId("dukestation")!!
    private val variant = duke.variants.single()

    private fun build(slot: Int? = null, f: FileRef = file) = PlayRecipe.build(duke, variant, null, f, "/storage/emulated/0", resumeSlot = slot)

    @Test fun `catalog entry is the EmuCoreR package for the PS1`() {
        assertEquals("com.sbro.emucorer", variant.packageName)
        assertEquals(setOf(PlaySystem.PS1), duke.systems)
        assertTrue(EmulatorCatalog.emulatorsFor(PlaySystem.PS1).any { it.id == "dukestation" })
        assertTrue("com.sbro.emucorer" in EmulatorCatalog.allPackages())
        // Read from its source, never run on a device here.
        assertEquals(Confidence.LIKELY, duke.confidence)
    }

    @Test fun `launch intent names MainActivity, the action and the game as a string extra`() {
        val spec = build().attempts.first()
        assertEquals("com.sbro.emucorer.MainActivity", spec.className)
        assertEquals("com.sbro.emucorer", spec.packageName)
        assertEquals("com.sbro.emucorer.action.LAUNCH_GAME", spec.action)
        assertNull(spec.dataUri)
        assertEquals(listOf(SpecExtra.Text("com.sbro.emucorer.extra.GAME_PATH", doc)), spec.extras)
        assertEquals(listOf(doc), spec.grantUris)
        // The plain VIEW fallback stays behind the template.
        assertEquals(PlayRecipe.ACTION_VIEW, build().attempts.last().action)
    }

    @Test fun `resume slot is an integer extra within 1 to 10, only when asked`() {
        assertEquals(SpecExtra.Number("com.sbro.emucorer.extra.SAVE_SLOT", 3), build(3).attempts.first().extras.last())
        assertEquals(1, build().attempts.first().extras.size)
        for (bad in listOf(0, -1, 11)) assertEquals(1, build(bad).attempts.first().extras.size)
        assertTrue(build(3).attempts.last().extras.isEmpty())
    }

    @Test fun `other emulators ignore a resume slot`() {
        val e = EmulatorCatalog.byId("duckstation")!!
        val spec = PlayRecipe.build(e, e.variants.first(), null, file, "/x", resumeSlot = 2).attempts.first()
        assertTrue(spec.extras.none { it is SpecExtra.Number })
        assertEquals(0, EmulatorCatalog.maxResumeSlot(listOf("duckstation", null)))
        assertEquals(10, EmulatorCatalog.maxResumeSlot(listOf("duckstation", "dukestation")))
    }

    @Test fun `slot control steps off then 1 to max then off`() {
        assertEquals(1, EmulatorCatalog.nextResumeSlot(null, 10))
        assertEquals(5, EmulatorCatalog.nextResumeSlot(4, 10))
        assertNull(EmulatorCatalog.nextResumeSlot(10, 10))
        assertNull(EmulatorCatalog.nextResumeSlot(null, 0))
    }

    @Test fun `ps1 disc formats are capabilities of dukestation`() {
        for (name in listOf("a.cue", "a.BIN", "a.iso", "a.chd", "a.pbp", "a.ecm", "a.mds", "a.m3u")) assertTrue(name, EmulatorCatalog.supportsFormat(duke, name))
        assertFalse(EmulatorCatalog.supportsFormat(duke, "a.rvz"))
        // An archive is needsExtract's business; an emulator without declared formats is not second-guessed.
        assertTrue(EmulatorCatalog.supportsFormat(duke, "a.zip"))
        assertTrue(EmulatorCatalog.supportsFormat(EmulatorCatalog.byId("duckstation")!!, "a.rvz"))
        assertTrue(PlayRecipe.needsExtract(duke, PlaySystem.PS1, "a.zip"))
        assertFalse(PlayRecipe.needsExtract(duke, PlaySystem.PS1, "a.pbp"))
    }

    private val handler = GameHandler(GameLaunchKeys.catalogue("dukestation", packageName = "com.sbro.emucorer"), "DukeStation", "com.sbro.emucorer", emulatorId = "dukestation")
    private fun game(name: String) = GameLaunch("content://t/$name", name, listOf(handler), system = PlaySystem.PS1)

    @Test fun `chd pbp and m3u are not flagged for dukestation`() {
        for (name in listOf("Game.chd", "Game.pbp", "Game.m3u", "Multi Disc.pbp")) {
            val state = GameReadiness.emulatorState(listOf(game(name)), handler.key, null)
            assertTrue(name, state.available)
            assertFalse(name, state.needsExtract)
            assertFalse(name, state.formatUnsupported)
        }
    }

    @Test fun `a format dukestation does not list is reported, an archive still means extract`() {
        assertTrue(GameReadiness.emulatorState(listOf(game("Game.rvz")), handler.key, null).formatUnsupported)
        val zip = GameReadiness.emulatorState(listOf(game("Game.zip")), handler.key, null)
        assertTrue(zip.needsExtract)
        assertFalse(zip.formatUnsupported)
    }

    @Test fun `ps1 entry files rank the playlist first and keep chd and pbp as entries`() {
        assertEquals(listOf("Game.m3u", "Game (Disc 1).chd", "Game (Disc 2).pbp"),
            PlayRecipe.entryFiles(listOf("Game (Disc 2).pbp", "Game (Disc 1).chd", "Game.m3u")))
        assertTrue(GameArtifacts.safeLaunchReference("Game.pbp") && GameArtifacts.safeLaunchReference("Game.ecm"))
    }
}
