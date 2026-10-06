package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {
    private fun doc(id: String, title: String, hint: String = "", vararg keywords: String) = SearchDoc(id, title, hint, keywords.toList())

    private val docs = listOf(
        doc("wifi", "Download only on Wi-Fi", "Not over mobile data", "wlan", "metered"),
        doc("battery", "Pause on low battery", "Off the charger, downloads pause", "accu", "batterij"),
        doc("theme", "Theme", "Dark, light or follow the device", "dark mode"),
        doc("duplicates", "Duplicates", "The same game more than once"),
        doc("pokemon", "Pokémon colours", "")
    )

    private fun ids(query: String) = SearchMatch.rank(query, docs).map { it.doc.id }

    @Test fun `a title that starts with the query ranks first`() {
        assertEquals(SearchMatch.TITLE_PREFIX, SearchMatch.score("dupl", docs[3]))
        assertEquals("duplicates", ids("dup").first())
    }

    @Test fun `ranking goes title prefix, word prefix, hint, keyword`() {
        assertEquals(SearchMatch.TITLE_PREFIX, SearchMatch.score("theme", docs[2]))
        assertEquals(SearchMatch.TITLE_WORDS, SearchMatch.score("low batt", docs[1]))
        assertEquals(SearchMatch.HINT, SearchMatch.score("charger", docs[1]))
        assertTrue(SearchMatch.score("accu", docs[1]) in 1 until SearchMatch.HINT)
        assertTrue(SearchMatch.score("dark", docs[2]) > SearchMatch.score("dark mode", doc("x", "Other", "", "dark mode")))
    }

    @Test fun `synonyms find the row`() {
        assertEquals(listOf("battery"), ids("accu"))
        assertEquals(listOf("wifi"), ids("wlan"))
    }

    @Test fun `accents, case and punctuation do not matter`() {
        assertEquals(listOf("pokemon"), ids("POKEMON"))
        assertEquals(listOf("wifi"), ids("wifi"))
        assertEquals(listOf("wifi"), ids("wi fi"))
    }

    @Test fun `a typo still matches, a little lower`() {
        val typo = SearchMatch.score("dupilcates", docs[3])
        assertTrue(typo > 0)
        assertTrue(typo < SearchMatch.score("duplicates", docs[3]))
        assertEquals("battery", ids("batery").first())
        assertTrue(SearchMatch.typoPrefix("batery", "battery"))
        assertTrue(!SearchMatch.typoPrefix("cat", "car"))
    }

    @Test fun `unrelated or blank queries find nothing`() {
        assertTrue(ids("zzzz").isEmpty())
        assertTrue(ids("   ").isEmpty())
        assertTrue(ids("!!").isEmpty())
    }

    @Test fun `keyword lists split on commas`() {
        assertEquals(listOf("dark mode", "night"), SearchMatch.keywordList("dark mode, night,"))
    }

    @Test fun `distance counts swaps as one`() {
        assertEquals(1, SearchMatch.distance("teh", "the"))
        assertEquals(0, SearchMatch.distance("abc", "abc"))
        assertEquals(2, SearchMatch.distance("abc", "a"))
    }

    @Test fun `games collapse versions and put prefix matches first`() {
        val rows = listOf(
            "Super Mario World (USA).zip" to "snes",
            "Super Mario World (Europe).zip" to "snes",
            "New Super Mario Bros (USA).nds" to "nds",
            "Super Mario World (USA).gba" to "gba",
            "Mario Kart (Disc 1).chd" to "psx",
            "Mario Kart (Disc 2).chd" to "psx"
        )
        val hits = GameHits.pick("mario", rows, 10)
        assertEquals(listOf(GameHit("Mario Kart", "psx")), hits.take(1))
        assertEquals(4, hits.size)
        assertEquals(2, GameHits.pick("mario", rows, 2).size)
    }

    @Test fun `every screen except Settings, the search itself and the tabs' own is indexed, ids are unique`() {
        val ids = SearchIndex.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val routes = SearchIndex.entries.map { it.target.route }.toSet()
        val missing = NavRoutes.allRoutes.map { it.route }.filter { it !in routes && it != NavRoutes.Settings.route && it != NavRoutes.SearchAll.route }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun `settings results always name a row`() {
        SearchIndex.entries.filter { it.group == SearchGroup.SETTINGS }.forEach {
            assertEquals(NavRoutes.Settings.route, it.target.route)
            assertTrue(it.id, !it.target.rowKey.isNullOrEmpty())
            assertTrue(it.id, it.section != null)
        }
    }
}
