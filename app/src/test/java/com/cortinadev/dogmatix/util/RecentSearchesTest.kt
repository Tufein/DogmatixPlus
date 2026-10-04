package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {
    @Test fun `newest first, no repeats, capped`() {
        var list = emptyList<String>()
        for (q in listOf("mario", "zelda", "metroid", "Mario")) list = RecentSearches.add(list, q)
        assertEquals(listOf("Mario", "metroid", "zelda"), list)
        for (q in listOf("a1", "b1", "c1", "d1", "e1")) list = RecentSearches.add(list, q)
        assertEquals(RecentSearches.MAX, list.size)
        assertEquals("e1", list.first())
    }

    @Test fun `the steps typed on the way are replaced by the longer search`() {
        var list = RecentSearches.add(emptyList(), "zel")
        list = RecentSearches.add(list, "zelda")
        assertEquals(listOf("zelda"), list)
        // A shorter search later is a search of its own.
        assertEquals(listOf("zel", "zelda"), RecentSearches.add(list, "zel"))
    }

    @Test fun `too short or blank searches are not kept, spaces are tidied`() {
        assertEquals(emptyList<String>(), RecentSearches.add(emptyList(), " a "))
        assertEquals(listOf("final fantasy"), RecentSearches.add(emptyList(), "  final   fantasy "))
    }

    @Test fun `round trip through the stored text`() {
        val list = listOf("final fantasy", "zelda")
        assertEquals(list, RecentSearches.decode(RecentSearches.encode(list)))
        assertEquals(emptyList<String>(), RecentSearches.decode(""))
    }
}
