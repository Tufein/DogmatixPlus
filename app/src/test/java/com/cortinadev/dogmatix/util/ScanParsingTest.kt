package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The name, tag and size parsing every scanned row goes through (its patterns are compiled once now). */
class ScanParsingTest {
    @Test fun `names lose their tags and keep the words`() {
        val (name, tags) = FileParsingUtils.extractNameAndTags("Legend of Zelda, The (USA, Europe) (En,Fr)  (Rev 1)")
        assertEquals("Legend of Zelda, The", name)
        assertTrue(tags.containsAll(listOf("USA", "Europe", "EN", "FR")))
    }

    @Test fun `repeated calls give the same result`() {
        repeat(3) { assertEquals("Tetris" to listOf("World"), FileParsingUtils.extractNameAndTags("Tetris (World)")) }
    }

    @Test fun `sizes in every notation`() {
        assertEquals(1536L, FileSizeUtils.parseFileSize("1.5 KiB"))
        assertEquals(0L, FileSizeUtils.parseFileSize("?"))
        assertEquals(0L, FileSizeUtils.parseFileSize(""))
        assertTrue(FileSizeUtils.parseFileSize("1,234.5 MB") > 1_000_000_000L)
        assertEquals(FileSizeUtils.parseFileSize("700 MiB"), FileSizeUtils.parseFileSize("700mib"))
    }

    @Test fun `parsing a big listing stays fast`() {
        val names = (1..20_000).map { "Game Number $it (USA) (En,Fr,De) (Rev $it)" }
        val started = System.nanoTime()
        names.forEach { FileParsingUtils.extractNameAndTags(it); FileSizeUtils.parseFileSize("${it.length}.5 MiB") }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("20 000 names took $ms ms", ms < 3_000)
    }
}
