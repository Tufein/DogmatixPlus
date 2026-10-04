package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroAchievementsTest {
    @Test fun `retroachievements consoles and hashing rules`() {
        assertEquals(5, RetroAchievements.consoleFor("nintendo_gameboy_advance")?.id)
        assertEquals(6, RetroAchievements.consoleFor("gameboy_color")?.id)
        assertEquals(4, RetroAchievements.consoleFor("nintendo_gameboy")?.id)
        assertEquals(3, RetroAchievements.consoleFor("super_nintendo_entertainment_system")?.id)
        assertEquals(7, RetroAchievements.consoleFor("nintendo_entertainment_system")?.id)
        assertNull(RetroAchievements.consoleFor("sony_playstation"))

        val body = ByteArray(32768) { (it % 251).toByte() }
        val ines = byteArrayOf(0x4E, 0x45, 0x53, 0x1A) + ByteArray(12) + body
        assertEquals(RetroAchievements.md5(body), RetroAchievements.hash(ines, RetroAchievements.Rule.NES))
        val snesHeadered = ByteArray(512) + ByteArray(8192 * 4) { 7 }
        assertEquals(RetroAchievements.md5(ByteArray(8192 * 4) { 7 }), RetroAchievements.hash(snesHeadered, RetroAchievements.Rule.SNES))
        assertEquals(RetroAchievements.md5(body), RetroAchievements.hash(body, RetroAchievements.Rule.PLAIN))

        // N64: .v64 (byte-swapped) and .n64 (little-endian) hash like the .z64 they come from.
        val z64 = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40, 1, 2, 3, 4)
        val v64 = byteArrayOf(0x37, 0x80.toByte(), 0x40, 0x12, 2, 1, 4, 3)
        val n64 = byteArrayOf(0x40, 0x12, 0x37, 0x80.toByte(), 4, 3, 2, 1)
        val want = RetroAchievements.md5(z64)
        assertEquals(want, RetroAchievements.hash(z64, RetroAchievements.Rule.N64))
        assertEquals(want, RetroAchievements.hash(v64, RetroAchievements.Rule.N64))
        assertEquals(want, RetroAchievements.hash(n64, RetroAchievements.Rule.N64))

        val list = RetroAchievements.parseGameList("""[
            {"Title":"Pokemon Emerald Version","ID":515,"NumAchievements":76,"Hashes":["605B89B67018ABCEA91E693A4DD25BE3"]},
            {"Title":"No cheevos","ID":2,"NumAchievements":0,"Hashes":["aa"]}]""")
        assertEquals(1, list.size)
        assertEquals(setOf("605b89b67018abcea91e693a4dd25be3"), list[0].hashes)
        assertTrue(RetroAchievements.gameListUrl(5, "me", "k y").contains("i=5&h=1&f=1"))
    }
}
