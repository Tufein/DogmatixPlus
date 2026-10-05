package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDiscoveryTest {

    @Test
    fun `genre spellings of RAWG and TheGamesDB become one name`() {
        assertEquals("Role-Playing", LibraryDiscovery.canonicalGenre("Role-playing games (RPG)"))
        assertEquals("Role-Playing", LibraryDiscovery.canonicalGenre("Role-Playing"))
        assertEquals("Platform", LibraryDiscovery.canonicalGenre("Platformer"))
        assertEquals("Board", LibraryDiscovery.canonicalGenre("Board Games"))
        assertEquals("Action", LibraryDiscovery.canonicalGenre(" action "))
        assertEquals("", LibraryDiscovery.canonicalGenre("  "))
    }

    @Test
    fun `genres are split on the cache separator and de-duplicated`() {
        assertEquals(setOf("Action", "Platform"), LibraryDiscovery.parseGenres("Action|Platformer|Platform"))
        assertTrue(LibraryDiscovery.parseGenres("").isEmpty())
    }

    @Test
    fun `years come from the first four digits only`() {
        assertEquals(1996, LibraryDiscovery.parseYear("1996"))
        assertEquals(1996, LibraryDiscovery.parseYear("1996-06-23"))
        assertNull(LibraryDiscovery.parseYear(""))
        assertNull(LibraryDiscovery.parseYear("TBA"))
        assertNull(LibraryDiscovery.parseYear("0000"))
        assertEquals(1990, LibraryDiscovery.decadeOf(1996))
    }

    @Test
    fun `lookup key follows the metadata cache format`() {
        assertEquals("super_nintendo_entertainment_system|super mario world", LibraryDiscovery.lookupKey("nintendo_super_nintendo_entertainment_system", "Super Mario World (USA).sfc"))
        assertEquals("snes|the zelda", LibraryDiscovery.lookupKey("snes", "Zelda, The"))
    }

    private fun index() = LibraryDiscovery.buildIndex(
        listOf(
            LibraryDiscovery.RawRow("gba|a", "Action|Platformer", "1996-01-01", "Nintendo"),
            LibraryDiscovery.RawRow("gba|b", "Role-playing games (RPG)", "2004", ""),
            LibraryDiscovery.RawRow("gba|c", "Action", "", ""),
            LibraryDiscovery.RawRow("gba|d", "", "", "Nobody")
        )
    )

    @Test
    fun `rows with neither genre nor year are not counted as known`() {
        val index = index()
        assertEquals(3, index.knownTitles)
        assertEquals(listOf("Action" to 2), index.genres.take(1))
        assertEquals(listOf(1990 to 1, 2000 to 1), index.decades)
    }

    @Test
    fun `filter ors inside a row and ands between rows`() {
        val index = index()
        val a = index.byKey["gba|a"]
        val b = index.byKey["gba|b"]
        val c = index.byKey["gba|c"]
        assertTrue(LibraryDiscovery.Filter(genres = setOf("Action", "Role-Playing")).matches(b))
        assertTrue(LibraryDiscovery.Filter(genres = setOf("Action"), decades = setOf(1990)).matches(a))
        assertFalse(LibraryDiscovery.Filter(genres = setOf("Action"), decades = setOf(2000)).matches(a))
        // No year known: a decade filter cannot claim it.
        assertFalse(LibraryDiscovery.Filter(decades = setOf(1990)).matches(c))
        assertFalse(LibraryDiscovery.Filter(genres = setOf("Action")).matches(null))
        assertTrue(LibraryDiscovery.Filter().matches(null))
        assertEquals(2, LibraryDiscovery.Filter(setOf("x"), setOf(1990)).activeCount)
    }

    @Test
    fun `match stops at the limit and reports how many rows it looked at`() {
        val index = index()
        val rows = listOf("gba|x", "gba|a", "gba|c", "gba|b", "gba|c")
        val result = LibraryDiscovery.match(rows, index, LibraryDiscovery.Filter(genres = setOf("Action")), limit = 2) { it }
        assertEquals(listOf("gba|a", "gba|c"), result.rows)
        assertEquals(3, result.consumed)
    }

    @Test
    fun `fetch targets skip known tried and duplicate titles and respect the cap`() {
        fun t(k: String) = LibraryDiscovery.Target(k, k, "gba")
        val out = LibraryDiscovery.fetchTargets(
            listOf(t("a"), t("b"), t("b"), t("c"), t("d"), t("e")),
            known = setOf("a"), tried = setOf("c"), cap = 2
        )
        assertEquals(listOf("b", "d"), out.map { it.key })
    }
}
