package com.cortinadev.dogmatix.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRemovalTest {
    @Test fun `archive companion fallback never selects same-base unknown keys or configuration`() {
        listOf("Game.key", "Game.config", "Game.dat", "Game.secret", "Game.md", "Game.srm", "Game.state1").forEach { name ->
            assertFalse(name, GameRemoval.matches(name, "Game.zip"))
            assertFalse(name, GameRemoval.safeReference(name))
        }
        listOf("Game.gba", "Game.cue", "Game.bin", "Game.iso", "Game.7z").forEach { name ->
            assertTrue(name, GameRemoval.matches(name, "Game.zip"))
            assertTrue(name, GameRemoval.safeReference(name))
        }
    }

    @Test fun `exact requested unknown filenames keep their existing behavior while save metadata stays protected`() {
        assertTrue(GameRemoval.matches("Game.key", "Game.key"))
        assertTrue(GameRemoval.matches("Game.config", "Game.config"))
        assertFalse(GameRemoval.matches("Game.srm", "Game.srm"))
        assertFalse(GameRemoval.matches("README.md", "README.md"))
        assertFalse(GameRemoval.safeReference("../Game.bin"))
        assertFalse(GameRemoval.safeReference("sub/Game.bin"))
        assertFalse(GameRemoval.safeReference("sub\\Game.bin"))
        assertFalse(GameRemoval.safeReference("volume:Game.bin"))
    }
}
