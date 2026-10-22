package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchNormalizerTest {

    @Test
    fun keyStripsPunctuationAccentsAndDoubleLetters() {
        assertEquals("yugiohduelmonsters", SearchNormalizer.key("Yu-Gi-Oh! Duel Monsters"))
        assertEquals("pokemonrojo", SearchNormalizer.key("Pokémon Rojo"))
        assertEquals("virtuatenis2", SearchNormalizer.key("Virtua Tennis 2"))
    }

    @Test
    fun patternJoinsWordsInOrderAndForgivesWordEndings() {
        assertEquals("yugio", SearchNormalizer.likePattern("yugioh"))
        assertEquals("mega", SearchNormalizer.likePattern("mega"))
        assertEquals("yu%gi%oh", SearchNormalizer.likePattern("yu gi oh"))
        assertEquals("virtua%teni", SearchNormalizer.likePattern("virtual tenis"))
        assertEquals("mari", SearchNormalizer.likePattern("  Mario  "))
        assertEquals("", SearchNormalizer.likePattern("!!!"))
    }

    @Test
    fun lenientQueriesMatchTheirTargets() {
        assertTrue(matches("Yu-Gi-Oh! Duel Monsters", "yugioh"))
        assertTrue(matches("Yu-Gi-Oh! Duel Monsters", "yu-gi-oh!"))
        assertTrue(matches("Yu-Gi-Oh! Duel Monsters", "yu gi oh"))
        assertTrue(matches("Virtua Tennis", "virtual tenis"))
        assertTrue(matches("Virtua Tennis", "virtua tennis"))
        assertTrue(matches("Pokémon Rojo", "pokemon"))
        assertTrue(matches("Donkey Kong Country", "donky kong"))
        assertFalse(matches("Virtua Tennis", "virtua fighter"))
    }

    @Test
    fun wordsListEveryWordAndTheWholeTitle() {
        assertEquals("yu gi oh yugioh", SearchNormalizer.words("Yu-Gi-Oh!"))
        assertEquals("virtua tenis 2 virtuatenis2", SearchNormalizer.words("Virtua Tennis 2"))
        assertEquals("pokemon rojo pokemonrojo", SearchNormalizer.words("Pokémon Rojo"))
        assertEquals("zelda", SearchNormalizer.words("Zelda"))
        assertEquals("o brien obrien", SearchNormalizer.words("O'Brien"))
        assertEquals("", SearchNormalizer.words("!!!"))
        assertEquals("", SearchNormalizer.words(""))
        // the same word twice is indexed once
        assertEquals("mario kart mariomariokart", SearchNormalizer.words("Mario Mario Kart"))
    }

    @Test
    fun queryWordsShortenLikeThePatternDoes() {
        assertEquals(listOf("virtua", "teni"), SearchNormalizer.queryWords("virtual tenis"))
        assertEquals(listOf("mega", "man", "x"), SearchNormalizer.queryWords(" Mega  Man X "))
        assertEquals(emptyList<String>(), SearchNormalizer.queryWords("?!"))
    }

    /** Models FTS4 prefix matching over [SearchNormalizer.words]: every query word must be the start of some indexed word. */
    private fun ftsMatches(name: String, query: String): Boolean {
        val indexed = SearchNormalizer.words(name).split(' ').filter { it.isNotEmpty() }
        val words = SearchNormalizer.queryWords(query)
        return words.isNotEmpty() && words.all { w -> indexed.any { it.startsWith(w) } }
    }

    @Test
    fun ftsFindsWhatTheLenientLikeSearchFound() {
        val cases = listOf(
            "Yu-Gi-Oh! Duel Monsters" to "yugioh", "Yu-Gi-Oh! Duel Monsters" to "yu-gi-oh!", "Yu-Gi-Oh! Duel Monsters" to "yu gi oh",
            "Virtua Tennis" to "virtual tenis", "Virtua Tennis" to "virtua tennis", "Pokémon Rojo" to "pokemon",
            "Donkey Kong Country" to "donky kong", "Super Mario Bros." to "supermario", "Dr. Mario" to "dr.mario",
            "O'Brien" to "obrien", "The Legend of Zelda: Ocarina of Time" to "zelda ocarina", "Hot Tub" to "hot tub"
        )
        cases.forEach { (name, query) ->
            assertTrue("$query in $name", ftsMatches(name, query))
        }
        assertFalse(ftsMatches("Virtua Tennis", "virtua fighter"))
        assertFalse(ftsMatches("Donkey Kong Country", "diddy"))
    }

    @Test
    fun ftsIsLooserOnWordOrderButNeverLosesAWordStartMatch() {
        // LIKE needed the words in order; FTS accepts them in any order
        assertTrue(ftsMatches("Virtua Tennis", "tennis virtua"))
        assertFalse(matches("Virtua Tennis", "tennis virtua"))
        // everything LIKE finds by the start of a word or of the title, FTS finds too
        listOf("Mario Kart 64" to "mario", "Mario Kart 64" to "kart", "Mario Kart 64" to "mario kart 64", "Mario Kart 64" to "supermario")
            .filter { matches(it.first, it.second) }
            .forEach { (name, query) -> assertTrue("$query in $name", ftsMatches(name, query)) }
    }

    /** Mirrors the SQL `searchKey LIKE '%' || pattern || '%'` used by the DAO. */
    private fun matches(name: String, query: String): Boolean {
        val regex = SearchNormalizer.likePattern(query).split("%").joinToString(".*") { Regex.escape(it) }
        return Regex(".*$regex.*").matches(SearchNormalizer.key(name))
    }
}
