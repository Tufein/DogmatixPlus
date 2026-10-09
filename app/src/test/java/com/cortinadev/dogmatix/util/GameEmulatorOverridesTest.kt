package com.cortinadev.dogmatix.util

import org.junit.Assert.*
import org.junit.Test

class GameEmulatorOverridesTest {
    private data class Handler(val key: String, val pkg: String)
    private val standard = Handler(GameLaunchKeys.catalogue("ppsspp", packageName = "org.ppsspp.ppsspp"), "org.ppsspp.ppsspp")
    private val gold = Handler(GameLaunchKeys.catalogue("ppsspp", packageName = "org.ppsspp.ppssppgold"), "org.ppsspp.ppssppgold")
    private fun resolve(own: String?, console: String?, handlers: List<Handler> = listOf(standard, gold)) =
        GameEmulatorOverrides.resolve(own, console, handlers, { it.key }, { it.pkg })

    @Test fun `game identity survives source encoding region and version upgrades`() {
        assertEquals(GameEmulatorOverrides.identity(" Nintendo_PSP ", "Game (Europe) (Rev 1).iso"),
            GameEmulatorOverrides.identity("nintendo_psp", "Game%20%28USA%29%20%28Rev%202%29.zip"))
        assertEquals(GameEmulatorOverrides.identity("psp", "Café + Game.iso"),
            GameEmulatorOverrides.identity("PSP", "Cafe%CC%81%20%2B%20Game.iso"))
        assertNotEquals(GameEmulatorOverrides.identity("psp", "A+B.iso"), GameEmulatorOverrides.identity("psp", "A B.iso"))
    }

    @Test fun `same filenames remain isolated across consoles profiles sequels and ordered titles`() {
        assertNotEquals(GameEmulatorOverrides.identity("gba", "Game.zip"), GameEmulatorOverrides.identity("snes", "Game.zip"))
        assertNotEquals(GameEmulatorOverrides.identity("gba", "Super World.gba"), GameEmulatorOverrides.identity("gba", "World Super.gba"))
        assertNotEquals(GameEmulatorOverrides.identity("gba", "Game.gba"), GameEmulatorOverrides.identity("gba", "Game 2.gba"))
        assertNotEquals(GameEmulatorOverrides.storageKey("child", "gba", "Game.gba"), GameEmulatorOverrides.storageKey("", "gba", "Game.gba"))
        assertNotEquals(GameEmulatorOverrides.identity("a|b", "c.zip"), GameEmulatorOverrides.identity("a", "b|c.zip"))
    }

    @Test fun `disc side and tape exceptions remain distinct`() {
        val names = listOf("Game (Disc 1).cue", "Game (Disc 2).cue", "Game (Side 1).cue", "Game (Tape 1).cue")
        assertEquals(names.size, names.map { GameEmulatorOverrides.identity("psx", it) }.distinct().size)
    }

    @Test fun `explicit game choice wins without changing the console default`() {
        val result = resolve(gold.key, standard.key)
        assertEquals(gold, result.handler)
        assertTrue(result.fromGame)
        assertFalse(result.missingGameOverride)
        assertEquals(standard, resolve(null, standard.key).handler)
    }

    @Test fun `automatic game choice follows installed ranking independently of console default`() {
        assertEquals(standard, resolve(GameLaunchKeys.AUTOMATIC, gold.key).handler)
        assertEquals(gold, resolve(GameLaunchKeys.AUTOMATIC, standard.key, listOf(gold)).handler)
        assertTrue(resolve(GameLaunchKeys.AUTOMATIC, standard.key, listOf(gold)).fromGame)
    }

    @Test fun `uninstalled variant falls back to console and reports the missing override`() {
        val result = resolve(gold.key, standard.key, listOf(standard))
        assertEquals(standard, result.handler)
        assertFalse(result.fromGame)
        assertTrue(result.missingGameOverride)
        assertNull(resolve(gold.key, null, listOf(standard)).handler)
    }

    @Test fun `a missing core never silently chooses another core`() {
        val swan = Handler(GameLaunchKeys.catalogue("retroarch", "swanstation", "com.retroarch"), "com.retroarch")
        val pcsx = Handler(GameLaunchKeys.catalogue("retroarch", "pcsx_rearmed", "com.retroarch"), "com.retroarch")
        assertEquals(swan, resolve(pcsx.key, GameLaunchKeys.AUTOMATIC, listOf(swan)).handler)
        assertTrue(resolve(pcsx.key, GameLaunchKeys.AUTOMATIC, listOf(swan)).missingGameOverride)
        assertNull(resolve(pcsx.key, null, listOf(swan)).handler)
    }
}
