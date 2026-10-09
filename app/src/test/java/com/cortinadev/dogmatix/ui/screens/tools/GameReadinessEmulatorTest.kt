package com.cortinadev.dogmatix.ui.screens.tools

import com.cortinadev.dogmatix.data.service.GameHandler
import com.cortinadev.dogmatix.data.service.GameLaunch
import com.cortinadev.dogmatix.util.EmulatorCatalog
import com.cortinadev.dogmatix.util.FileRef
import com.cortinadev.dogmatix.util.GameLaunchKeys
import com.cortinadev.dogmatix.util.PlayRecipe
import com.cortinadev.dogmatix.util.PlaySystem
import org.junit.Assert.*
import org.junit.Test

class GameReadinessEmulatorTest {
    private val duck = GameLaunchKeys.catalogue("duckstation", packageName = "com.github.stenzek.duckstation")
    private val epsxe = GameLaunchKeys.catalogue("epsxe", packageName = "com.epsxe.ePSXe")

    /** Real catalogue contracts: DuckStation accepts SAF; ePSXe requires a filesystem path. */
    private fun game(name: String, path: String?): GameLaunch {
        val ref = FileRef(name, "content://test/$name", path)
        val installed = setOf("com.github.stenzek.duckstation", "com.epsxe.ePSXe")
        val handlers = EmulatorCatalog.targetsFor(PlaySystem.PS1, installed).mapNotNull { target ->
            val emulator = EmulatorCatalog.byId(target.emulatorId)!!
            val variant = emulator.variants.single { it.packageName == target.packageName }
            if (!PlayRecipe.build(emulator, variant, target.core, ref, "/storage/emulated/0").usesTemplate) null
            else GameHandler(target.key, target.label, target.packageName, target.emulatorId, target.core)
        }
        return GameLaunch(ref.documentUri!!, name, handlers, ref, system = PlaySystem.PS1)
    }

    @Test fun `each entry resolves its own effective emulator and missing override`() {
        val cue = game("Game.cue", null)
        val chd = game("Game.chd", "/storage/emulated/0/Game.chd")
        assertEquals(listOf(duck), cue.handlers.map { it.key })
        assertEquals(listOf(duck, epsxe), chd.handlers.map { it.key })
        val ui = ReadyUi(loading = false, games = listOf(cue, chd), gameOverride = epsxe, consoleDefault = duck)

        assertEquals(duck, ui.effective(cue).handler?.key)
        assertTrue(ui.effective(cue).missingGameOverride)
        assertFalse(ui.effective(cue).fromGame)
        assertEquals(epsxe, ui.effective(chd).handler?.key)
        assertFalse(ui.effective(chd).missingGameOverride)
        assertTrue(ui.effective(chd).fromGame)
        // The result cannot depend on which launchable file is listed first.
        assertEquals(ui.effective(chd), ui.copy(games = listOf(chd, cue)).effective(chd))
    }

    @Test fun `an unresolved first entry does not hide another entry choice and resetting uses console defaults`() {
        val cue = game("Game.cue", null)
        val chd = game("Game.chd", "/storage/emulated/0/Game.chd")
        val ui = ReadyUi(loading = false, games = listOf(cue, chd), gameOverride = epsxe)
        assertNull(ui.effective(cue).handler)
        assertTrue(ui.effective(cue).missingGameOverride)
        assertEquals(epsxe, ui.effective(chd).handler?.key)
        assertFalse(ui.effective(chd).missingGameOverride)

        val reset = ui.copy(gameOverride = null, consoleDefault = duck)
        listOf(cue, chd).forEach { entry ->
            assertEquals(duck, reset.effective(entry).handler?.key)
            assertFalse(reset.effective(entry).missingGameOverride)
            assertFalse(reset.effective(entry).fromGame)
        }
    }
}
