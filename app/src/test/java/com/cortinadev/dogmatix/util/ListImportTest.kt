package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListImportTest {
    @Test fun `one game per line, without numbering, bullets, quotes and comments`() {
        val text = "\uFEFF# my favourites\n1. Super Mario World\n2) F-Zero\n3-D Worldrunner\n- Zelda: A Link to the Past\n\n• \"Metroid\"\n// skip\nChrono Trigger\tSNES\t1995\nsuper mario world\n"
        assertEquals(listOf("Super Mario World", "F-Zero", "3-D Worldrunner", "Zelda: A Link to the Past", "Metroid", "Chrono Trigger"), ListImport.titles(text))
    }

    @Test fun `a title is only the same game with exactly the same words`() {
        assertTrue(GameTitleCleaner.sameTitle("The Legend of Zelda: A Link to the Past", "Legend of Zelda, The - A Link to the Past (USA).zip"))
        assertTrue(GameTitleCleaner.sameTitle("Super Mario World", "Super Mario World (USA) (Rev 1).sfc"))
        assertTrue(GameTitleCleaner.sameTitle("Pokemon Emerald", "Pokémon - Emerald Version (USA, Europe).gba"))
        assertTrue(GameTitleCleaner.sameTitle("Final Fantasy VII (USA)", "Final Fantasy VII (Europe) (Disc 1).chd"))
        assertFalse(GameTitleCleaner.sameTitle("Super Mario World", "Super Mario World 2 - Yoshi's Island (USA).zip"))
        assertFalse(GameTitleCleaner.sameTitle("Final Fantasy II", "Final Fantasy III (USA).sfc"))
        assertFalse(GameTitleCleaner.sameTitle("", "Anything (USA).zip"))
    }

    @Test fun `the longest word narrows the search`() {
        assertEquals("trigger", ListImport.searchWord("Chrono Trigger"))
        assertNull(ListImport.searchWord("The"))
    }
}
