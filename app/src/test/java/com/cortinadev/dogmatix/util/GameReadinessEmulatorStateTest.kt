package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.service.GameHandler
import com.cortinadev.dogmatix.data.service.GameLaunch
import org.junit.Assert.*
import org.junit.Test

class GameReadinessEmulatorStateTest {
    private val duck = GameHandler(GameLaunchKeys.catalogue("duckstation", packageName = "com.github.stenzek.duckstation"),
        "DuckStation", "com.github.stenzek.duckstation", emulatorId = "duckstation")
    private val generic = GameHandler("org.example/org.example.Player", "Another player", "org.example")
    private fun game(name: String = "Game.zip", handlers: List<GameHandler> = listOf(duck, generic)) =
        GameLaunch("content://test/$name", name, handlers, system = PlaySystem.PS1)

    @Test fun `selected emulator needs extraction even when another installed handler accepts archives`() {
        val actual = GameReadiness.emulatorState(listOf(game()), duck.key, generic.key)
        assertTrue(actual.available)
        assertTrue(actual.needsExtract)
        assertFalse(actual.needsChoice)
    }

    @Test fun `supported effective handler clears extraction condition`() {
        val actual = GameReadiness.emulatorState(listOf(game()), generic.key, duck.key)
        assertTrue(actual.available)
        assertFalse(actual.needsExtract)
        assertFalse(actual.needsChoice)
    }

    @Test fun `missing explicit game emulator cannot silently mark console fallback ready`() {
        val actual = GameReadiness.emulatorState(listOf(game("Game.chd")), "catalog:missing@org.missing", duck.key)
        assertFalse(actual.available)
        assertFalse(actual.needsExtract)
        assertTrue(actual.needsChoice)
    }

    @Test fun `installed emulators with an ask preference require a choice first`() {
        val actual = GameReadiness.emulatorState(listOf(game("Game.chd")), null, null)
        assertFalse(actual.available)
        assertTrue(actual.needsChoice)
        val automatic = GameReadiness.emulatorState(listOf(game("Game.chd")), null, GameLaunchKeys.AUTOMATIC)
        assertTrue(automatic.available)
        assertFalse(automatic.needsExtract)
    }

    @Test fun `entry order does not hide a supported configured launch entry`() {
        val archived = game()
        val extracted = game("Game.cue")
        val first = GameReadiness.emulatorState(listOf(archived, extracted), null, duck.key)
        assertEquals(first, GameReadiness.emulatorState(listOf(extracted, archived), null, duck.key))
        assertTrue(first.available)
        assertFalse(first.needsExtract)
    }

    @Test fun `no installed handler differs from a pending choice`() {
        val actual = GameReadiness.emulatorState(listOf(game(handlers = emptyList())), null, null)
        assertFalse(actual.available)
        assertFalse(actual.needsChoice)
    }
}
