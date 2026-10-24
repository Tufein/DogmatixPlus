package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedGamesTest {

    private fun e(name: String, console: String = "snes", at: Long = 1L) = CompletedEntry(console, "$name.zip", at)

    @Test
    fun `toggle marks and unmarks, and a second mark keeps the first date`() {
        var list = CompletedGames.toggle(emptyList(), e("A", at = 5L))
        assertEquals(listOf(e("A", at = 5L)), list)
        assertEquals(list, CompletedGames.mark(list, e("A", at = 99L)))
        list = CompletedGames.toggle(list, e("A", at = 7L))
        assertTrue(list.isEmpty())
    }

    @Test
    fun `the same file name on two consoles is two games`() {
        val list = CompletedGames.mark(CompletedGames.mark(emptyList(), e("Tetris", "gb")), e("Tetris", "nes"))
        assertEquals(2, list.size)
        assertEquals(1, CompletedGames.unmark(list, CompletedGames.key("gb", "Tetris.zip")).size)
    }

    @Test
    fun `json round trip keeps entries and drops broken ones`() {
        val list = listOf(e("B", at = 2L), e("A", at = 1L))
        assertEquals(list.toSet(), CompletedGames.fromJson(CompletedGames.toJson(list)).toSet())
        val broken = """[{"console":"snes","file":"A.zip","at":3},{"console":"","file":"x"},{"file":"y"},5,{"console":"snes","file":"A.zip"}]"""
        assertEquals(listOf(CompletedEntry("snes", "A.zip", 3L)), CompletedGames.fromJson(broken))
        assertTrue(CompletedGames.fromJson("not json").isEmpty())
        assertTrue(CompletedGames.fromJson(null).isEmpty())
    }

    @Test
    fun `the list is bounded`() {
        val full = (0 until CompletedGames.MAX_ENTRIES).map { CompletedEntry("c", "f$it", 1L) }
        assertEquals(full, CompletedGames.mark(full, e("New")))
    }

    @Test
    fun `file names are safe and distinct per profile`() {
        assertEquals("completed_default.json", CompletedGames.fileNameFor(""))
        assertEquals("completed_p_abc-1.json", CompletedGames.fileNameFor("abc-1"))
        val odd1 = CompletedGames.fileNameFor("a/b")
        val odd2 = CompletedGames.fileNameFor("a?b")
        assertFalse(odd1.contains('/'))
        assertTrue(odd1 != odd2)
    }

    @Test
    fun `completed filter matches`() {
        assertTrue(CompletedFilter.ALL.matches(true) && CompletedFilter.ALL.matches(false))
        assertTrue(CompletedFilter.COMPLETED.matches(true) && !CompletedFilter.COMPLETED.matches(false))
        assertTrue(!CompletedFilter.NOT_COMPLETED.matches(true) && CompletedFilter.NOT_COMPLETED.matches(false))
        assertFalse(CompletedFilter.ALL.isActive)
        assertTrue(CompletedFilter.NOT_COMPLETED.isActive)
    }
}
