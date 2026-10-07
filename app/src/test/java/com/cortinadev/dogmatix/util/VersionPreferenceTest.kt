package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionPreferenceTest {

    @Test fun `the default picks as the favourite languages always did`() {
        val p = VersionPreferences.defaultFor(setOf("en", "NL"), "nl", "nl")
        assertEquals(listOf("NL", "EN"), p.languages)
        assertEquals(VersionPicker.regionPreference(setOf("NL", "EN")), p.regions)
        assertEquals(listOf("EN", "DE", "FR"), VersionPreferences.defaultFor(setOf("FR", "DE", "EN"), null, "en").languages)
        assertTrue(VersionPreferences.defaultFor(emptySet(), "nl", "nl").languages.isEmpty())
    }

    @Test fun `stored lists are cleaned of repeats`() {
        val p = VersionPreferences.fromJson("""{"lang":["en","EN","jp","JA",""],"reg":["Europe","europe"," USA ",""],"rev":false}""")!!
        assertEquals(listOf("EN", "JA"), p.languages)
        assertEquals(listOf("Europe", "USA"), p.regions)
        assertEquals(false, p.preferLatestRevision)
        assertEquals(true, p.preferFinal)
        assertNull(VersionPreferences.fromJson("not json"))
        assertEquals(p, VersionPreferences.fromJson(VersionPreferences.toJson(p)))
    }

    @Test fun `one broken console override does not lose the others`() {
        val o = VersionPreferences.overridesFromJson("""{"snes":{"reg":["USA","usa"]},"gba":"broken","psx":{"lang":"EN"},"n64":{}}""")
        assertEquals(mapOf("snes" to ConsoleOverride(regions = listOf("USA"))), o)
        assertEquals(o, VersionPreferences.overridesFromJson(VersionPreferences.overridesToJson(o)))
        assertTrue(VersionPreferences.overridesFromJson("[1,2]").isEmpty())
    }

    @Test fun `editing works on values so a repeated press cannot hit another entry`() {
        val list = listOf("World", "Europe", "USA")
        assertEquals(listOf("World", "USA", "Europe"), VersionPreferences.move(list, "USA", -1))
        assertEquals(listOf("USA", "World", "Europe"), VersionPreferences.move(VersionPreferences.move(list, "USA", -1), "usa", -1))
        assertSame(list, VersionPreferences.move(list, "World", -1))
        assertEquals(listOf("World", "USA"), VersionPreferences.remove(VersionPreferences.remove(list, "Europe"), "Europe"))
        assertEquals(list, VersionPreferences.add(list, "usa"))
    }

    @Test fun `a console override replaces only its own lists`() {
        val base = VersionPreference(languages = listOf("NL"), regions = listOf("Europe"))
        assertEquals(VersionPreference(languages = listOf("NL"), regions = listOf("Japan")), VersionPreferences.withOverride(base, ConsoleOverride(regions = listOf("Japan"))))
        assertSame(base, VersionPreferences.withOverride(base, ConsoleOverride()))
    }

    @Test fun `the fixed version still wins and the old pick keeps working`() {
        val a = VersionPicker.Candidate("a", "Game (USA).gba")
        val b = VersionPicker.Candidate("b", "Game (Japan).gba")
        val english = VersionPreferences.defaultFor(setOf("EN"), null, null)
        assertEquals(b, VersionPreference.pick(listOf(a, b), english, "b"))
        assertEquals(a, VersionPreference.pick(listOf(a, b), english, null))
        assertEquals(a, VersionPreference.pick(listOf(a, b), listOf("USA", "Japan"), setOf("EN"), "gone"))
    }
}
