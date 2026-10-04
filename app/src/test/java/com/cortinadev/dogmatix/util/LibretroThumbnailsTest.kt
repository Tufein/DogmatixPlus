package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibretroThumbnailsTest {
    @Test fun `libretro thumbnails names, systems and symlink stubs`() {
        assertEquals("Nintendo_-_Game_Boy_Advance", LibretroThumbnails.systemFor("nintendo_gameboy_advance")?.repo)
        assertEquals("Nintendo_-_Game_Boy", LibretroThumbnails.systemFor("gameboy")?.repo)
        assertEquals("Sony_-_PlayStation", LibretroThumbnails.systemFor("sony_playstation")?.repo)
        assertEquals("Sony_-_PlayStation_2", LibretroThumbnails.systemFor("playstation_2")?.repo)
        assertEquals(listOf("Final Fantasy VII (USA) (Disc 1)", "Final Fantasy VII (USA)"), LibretroThumbnails.candidates("Final Fantasy VII (USA) (Disc 1).chd"))
        assertEquals(listOf("Game (USA) (Rev 1) (En,Fr)", "Game (USA)"), LibretroThumbnails.candidates("Game (USA) (Rev 1) (En,Fr).zip"))
        assertEquals("Tom _ Jerry_ The Movie (USA)", LibretroThumbnails.thumbnailName("Tom & Jerry: The Movie (USA)"))
        val sys = LibretroThumbnails.systemFor("gba")!!
        assertEquals("https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy_Advance/master/Named_Boxarts/Advance%20Wars%20%28USA%29.png",
            LibretroThumbnails.boxartUrl(sys, "Advance Wars (USA)"))
        assertEquals("Final Fantasy VII (USA)", LibretroThumbnails.symlinkTarget("Final Fantasy VII (USA).png".toByteArray()))
        assertNull(LibretroThumbnails.symlinkTarget(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertNull(LibretroThumbnails.symlinkTarget("<html>not found</html>".toByteArray()))
    }
}
