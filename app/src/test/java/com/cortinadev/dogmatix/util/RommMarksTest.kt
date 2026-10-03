package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RommMarksTest {
    @Test fun `a game on the server matches whatever the extension is`() {
        val keys = RommMarks.keys("gba", listOf("Pokemon Emerald (USA).zip", "Dr. Mario (World).gb"))
        assertTrue(RommMarks.isOnServer(keys, "gba", "Pokemon Emerald (USA).gba"))
        assertTrue(RommMarks.isOnServer(keys, "gba", "pokemon%20emerald%20(usa).GBA"))
        assertTrue(RommMarks.isOnServer(keys, "gba", "Dr. Mario (World).nes"))
    }

    @Test fun `the same name on another console does not match`() {
        val keys = RommMarks.keys("gba", listOf("Tetris.gba"))
        assertFalse(RommMarks.isOnServer(keys, "gb", "Tetris.gb"))
    }

    @Test fun `rows that come from the server itself always count`() {
        val url = RommSource.downloadUrl("https://romm.example", 7, "Game.gba")
        assertTrue(RommMarks.isOnServer(emptySet(), "gba", "Game.gba", url, "https://romm.example"))
        assertFalse(RommMarks.isOnServer(emptySet(), "gba", "Game.gba", "https://other/Game.gba", "https://romm.example"))
    }

    @Test fun `finds what the server still lacks`() {
        val keys = RommMarks.keys("snes", listOf("A.sfc", "B.sfc"))
        assertEquals(listOf("C.sfc"), RommMarks.missingOnServer(keys, "snes", listOf("A.zip", "B.sfc", "C.sfc")))
    }
}
