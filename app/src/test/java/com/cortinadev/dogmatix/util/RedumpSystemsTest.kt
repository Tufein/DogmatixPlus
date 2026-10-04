package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedumpSystemsTest {
    @Test fun `redump systems prefer the specific console`() {
        assertEquals("ps2", RedumpSystems.systemFor("sony_playstation_2"))
        assertEquals("psx", RedumpSystems.systemFor("sony_playstation"))
        assertEquals("ss", RedumpSystems.systemFor("sega_saturn"))
        assertEquals("mcd", RedumpSystems.systemFor("sega_mega_cd"))
        assertNull(RedumpSystems.systemFor("nintendo_gameboy_advance"))
        assertEquals("http://redump.org/datfile/psx/", RedumpSystems.url("psx"))
    }
}
