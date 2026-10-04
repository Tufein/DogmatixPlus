package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.LibraryMove.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMoveTest {
    @Test fun `the original goes only after a copy of the same size`() {
        assertTrue(LibraryMove.mayDeleteOriginal(Outcome.COPIED, 1000, 1000))
        assertTrue(LibraryMove.mayDeleteOriginal(Outcome.ALREADY_THERE, 1000, 1000))
        assertFalse(LibraryMove.mayDeleteOriginal(Outcome.COPIED, 1000, 999))
        assertFalse(LibraryMove.mayDeleteOriginal(Outcome.COPIED, 1000, -1))
        assertFalse(LibraryMove.mayDeleteOriginal(Outcome.FAILED, 1000, 1000))
    }

    @Test fun `a file already at the target counts only with the exact size`() {
        assertTrue(LibraryMove.alreadyThere(1000, 1000))
        assertFalse(LibraryMove.alreadyThere(400, 1000))
        assertFalse(LibraryMove.alreadyThere(null, 1000))
        assertFalse(LibraryMove.alreadyThere(0, 0))
    }

    @Test fun `room is checked with a reserve and unknown space refuses nothing`() {
        val gb = 1024L * 1024 * 1024
        assertEquals(true, LibraryMove.fits(10 * gb, 11 * gb))
        assertEquals(false, LibraryMove.fits(10 * gb, 10 * gb))
        assertNull(LibraryMove.fits(10 * gb, null))
    }

    @Test fun `folders inside one another overlap`() {
        assertTrue(LibraryMove.overlaps("/storage/emulated/0/roms", "/storage/emulated/0/roms/gba"))
        assertTrue(LibraryMove.overlaps("/storage/emulated/0/Roms/", "/storage/emulated/0/roms"))
        assertTrue(LibraryMove.overlaps("/storage/emulated/0/roms/gba", "/storage/emulated/0/roms"))
        assertFalse(LibraryMove.overlaps("/storage/emulated/0/roms", "/storage/1234-ABCD/roms"))
        assertFalse(LibraryMove.overlaps("/storage/emulated/0/roms", "/storage/emulated/0/roms2"))
    }

    @Test fun `total ignores sizes the provider did not report`() {
        val items = listOf(LibraryMove.Item("", "a.gba", 100), LibraryMove.Item("gba", "b.gba", -1), LibraryMove.Item("gba/x", "c.gba", 50))
        assertEquals(150L, LibraryMove.totalBytes(items))
        assertEquals("gba/x", LibraryMove.join("gba", "x"))
        assertEquals("a.gba", LibraryMove.join("", "a.gba"))
    }
}
