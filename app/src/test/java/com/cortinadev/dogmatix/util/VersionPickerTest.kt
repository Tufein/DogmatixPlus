package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionPickerTest {
    private fun c(name: String, vararg tags: String) = VersionPicker.Candidate(name, name, tags.toList())
    private fun best(prefs: List<String>, langs: Set<String>, vararg list: VersionPicker.Candidate) =
        VersionPicker.best(list.toList(), prefs, langs)?.name

    @Test fun `european players get the european release first`() {
        val prefs = VersionPicker.regionPreference(setOf("NL", "EN"))
        assertEquals("Europe", prefs[1])
        assertEquals("Zelda (Europe).gba", best(prefs, setOf("NL", "EN"), c("Zelda (USA).gba"), c("Zelda (Europe).gba"), c("Zelda (Japan).gba")))
    }

    @Test fun `english only players prefer the american release and japanese readers the japanese`() {
        assertEquals("Zelda (USA).gba", best(VersionPicker.regionPreference(setOf("EN")), setOf("EN"), c("Zelda (Europe).gba"), c("Zelda (USA).gba")))
        assertEquals("Zelda (Japan).gba", best(VersionPicker.regionPreference(setOf("JA")), setOf("JA"), c("Zelda (USA).gba"), c("Zelda (Japan).gba")))
    }

    @Test fun `world releases lead for everyone`() {
        assertEquals("Tetris (World).gb", best(VersionPicker.regionPreference(setOf("NL")), setOf("NL"), c("Tetris (Europe).gb"), c("Tetris (World).gb")))
    }

    @Test fun `prototypes demos and bad dumps never win over a normal release`() {
        val prefs = VersionPicker.regionPreference(setOf("EN"))
        assertEquals("Game (Europe).sfc", best(prefs, setOf("EN"), c("Game (USA) (Beta).sfc"), c("Game (Europe).sfc")))
        assertEquals("Game (Europe).sfc", best(prefs, setOf("EN"), c("Game (USA) (Demo).sfc"), c("Game (Europe).sfc")))
        assertEquals("Game (Europe).sfc", best(prefs, setOf("EN"), c("Game (USA) [b1].sfc"), c("Game (Europe).sfc")))
        assertEquals("Game (USA) (Rev 1).sfc", best(prefs, setOf("EN"), c("Game (USA) (Proto).sfc"), c("Game (USA) (Rev 1).sfc")))
    }

    @Test fun `a later revision beats an earlier one of the same release`() {
        val prefs = VersionPicker.regionPreference(setOf("EN"))
        assertEquals("Game (USA) (Rev 2).sfc", best(prefs, setOf("EN"), c("Game (USA).sfc"), c("Game (USA) (Rev 1).sfc"), c("Game (USA) (Rev 2).sfc")))
    }

    @Test fun `a wanted language in the tags counts`() {
        val prefs = VersionPicker.regionPreference(setOf("NL"))
        val ranked = VersionPicker.rank(listOf(c("Asterix (Europe).gba", "EN"), c("Asterix (Europe).gba", "NL")), prefs, setOf("NL"))
        assertTrue(ranked[0].candidate.tags.contains("NL"))
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test fun `nothing to choose from`() {
        assertNull(VersionPicker.best(emptyList(), listOf("World"), emptySet()))
        assertNotNull(VersionPicker.best(listOf(c("X.gba")), listOf("World"), emptySet()))
    }

    @Test fun `tags are read from brackets and split`() {
        assertEquals(listOf("USA", "Rev 1", "b1"), VersionPicker.tagsOf("Game (USA) (Rev 1) [b1].sfc"))
        assertEquals(listOf("En", "Fr", "De"), VersionPicker.tagsOf("Game (En,Fr,De).gba"))
    }
}
