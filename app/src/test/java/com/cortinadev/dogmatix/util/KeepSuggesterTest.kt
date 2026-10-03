package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeepSuggesterTest {
    private fun entry(name: String, folder: String = "/ROMs/gba", size: Long = 100): GameEntry {
        val file = diskFile("$name.gba", folder = folder, size = size, consoleId = "gba", dirId = "$folder#$name")
        return GameEntry("gba", "gba", folder, name, listOf(file))
    }
    private fun group(kind: DuplicateGroup.Kind, vararg entries: GameEntry) = DuplicateGroup("gba", "gba", "Game", kind, entries.toList())
    private val eu = VersionPicker.regionPreference(setOf("NL", "EN"))

    @Test fun `variants keep the preferred region`() {
        val s = KeepSuggester.suggest(group(DuplicateGroup.Kind.VARIANT, entry("Game (USA)"), entry("Game (Europe)"), entry("Game (Japan)")), eu, setOf("NL", "EN"))!!
        assertEquals("Game (Europe)", s.keep.baseName)
        assertEquals(setOf("Game (USA)", "Game (Japan)"), s.remove.map { it.baseName }.toSet())
    }

    @Test fun `identical copies keep the one in the shallowest folder`() {
        val s = KeepSuggester.suggest(group(DuplicateGroup.Kind.IDENTICAL, entry("Game (USA)", "/ROMs/gba/old/backup"), entry("Game (USA)", "/ROMs/gba")), eu, setOf("EN"))!!
        assertEquals("/ROMs/gba", s.keep.folder)
        assertEquals(1, s.remove.size)
    }

    @Test fun `variants that rank the same give no suggestion`() {
        assertNull(KeepSuggester.suggest(group(DuplicateGroup.Kind.VARIANT, entry("Game (Rev 1)"), entry("Game (Rev 1) ")), eu, setOf("EN")))
    }

    @Test fun `a demo is removed in favour of the real game`() {
        val s = KeepSuggester.suggest(group(DuplicateGroup.Kind.VARIANT, entry("Game (Europe) (Demo)"), entry("Game (Europe)")), eu, setOf("EN"))!!
        assertEquals("Game (Europe)", s.keep.baseName)
    }

    @Test fun `reclaimable space is the size of what goes`() {
        val s = KeepSuggester.suggest(group(DuplicateGroup.Kind.IDENTICAL, entry("G", "/a", 50), entry("G", "/a/b/c", 70)), eu, setOf("EN"))!!
        assertEquals(70L, s.reclaimable)
    }
}
