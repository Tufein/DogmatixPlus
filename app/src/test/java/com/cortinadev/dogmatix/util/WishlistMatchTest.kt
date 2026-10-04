package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WishlistMatchTest {
    private val romm = RommMarks.keys("nintendo_gba", listOf("Golden Sun (USA).gba", "Advance Wars (Europe).zip")) +
        RommMarks.keys("nintendo_snes", listOf("Chrono Trigger (USA).sfc"))

    @Test fun findsAWishOnTheServerByAllItsWords() {
        assertTrue(WishlistMatch.inRomm("Golden Sun", null, romm))
        assertTrue(WishlistMatch.inRomm("golden sun", "nintendo_gba", romm))
        assertTrue(WishlistMatch.inRomm("Advance Wars", "nintendo_gba", romm))
        assertFalse("other console", WishlistMatch.inRomm("Golden Sun", "nintendo_snes", romm))
        assertFalse("one word missing", WishlistMatch.inRomm("Golden Sun Lost Age", null, romm))
        assertFalse(WishlistMatch.inRomm("Golden Sun", null, emptySet()))
    }

    @Test fun findsAWishOnTheDeviceWithinTheConsolesFolders() {
        val owned = LibraryKeys.keysFor("gba", "Golden Sun (USA).gba").toSet() + LibraryKeys.keysFor("snes", "Chrono Trigger (USA).sfc")
        assertTrue(WishlistMatch.onDevice("Golden Sun", setOf("gba"), owned))
        assertTrue(WishlistMatch.onDevice("Chrono Trigger", null, owned))
        assertFalse(WishlistMatch.onDevice("Golden Sun", setOf("snes"), owned))
        assertFalse(WishlistMatch.onDevice("Secret of Mana", null, owned))
    }

    @Test fun havingTheGameBeatsFindingIt() {
        assertEquals(WishlistMatch.State.ON_DEVICE, WishlistMatch.state(onDevice = true, inRomm = true, sourceMatches = 3))
        assertEquals(WishlistMatch.State.IN_ROMM, WishlistMatch.state(onDevice = false, inRomm = true, sourceMatches = 3))
        assertEquals(WishlistMatch.State.IN_SOURCES, WishlistMatch.state(onDevice = false, inRomm = false, sourceMatches = 1))
        assertEquals(WishlistMatch.State.WANTED, WishlistMatch.state(onDevice = false, inRomm = false, sourceMatches = 0))
        assertTrue(WishlistMatch.stillWanted(WishlistMatch.State.IN_SOURCES))
        assertFalse(WishlistMatch.stillWanted(WishlistMatch.State.IN_ROMM))
        assertFalse(WishlistMatch.stillWanted(WishlistMatch.State.ON_DEVICE))
    }
}
