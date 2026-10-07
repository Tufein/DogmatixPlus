package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.gson.JsonParser

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

    @Test fun `encoded release names share one game key while consoles sequels and discs stay separate`() {
        assertEquals(VersionPreference.key("gba", "Game (Japan) (Rev 1).gba"),
            VersionPreference.key("gba", "Game%20%28USA%29%20%28Rev%202%29.gba"))
        assertNotEquals(VersionPreference.key("gba", "Game.gba"), VersionPreference.key("gba", "Game 2.gba"))
        assertNotEquals(VersionPreference.key("gba", "Game.gba"), VersionPreference.key("snes", "Game.sfc"))
        assertEquals(VersionPreference.key("psx", "Game (Disc II).cue"), VersionPreference.key("psx", "Game (Disc 2).chd"))
        assertNotEquals(VersionPreference.key("psx", "Game (Disc 1).cue"), VersionPreference.key("psx", "Game (Disc 10).cue"))
        assertNotEquals(VersionPreference.key("psx", "Game (Disc 1).cue"), VersionPreference.key("psx", "Game (Side 1).cue"))
        assertNotEquals(VersionPreference.key("psx", "Game (Tape 1).cue"), VersionPreference.key("psx", "Game (Tape 2).cue"))
        assertNotEquals(VersionPreference.key("psx", "Game (Disc 1) (Disc 2).cue"), VersionPreference.key("psx", "Game (Disc 1).cue"))
    }

    @Test fun `older encoded and disc pins are recovered by the file they actually fixed`() {
        val encoded = "Game%20%28Japan%29.gba"
        val side = "Game (Side 1).cue"
        val pins = VersionPreference.storedPins(mapOf(
            VersionPreference.legacyKey("gba", encoded) to encoded,
            VersionPreference.legacyKey("psx", side) to side
        ))
        assertEquals(encoded, pins[VersionPreference.key("gba", "Game (USA).gba")])
        assertEquals(side, pins[VersionPreference.key("psx", side)])
        assertFalse(pins.containsKey(VersionPreference.key("psx", "Game (Disc 1).cue")))
        assertFalse(pins.containsKey(VersionPreference.key("gba", "Game 2.gba")))
    }

    @Test fun `a canonical pin takes precedence over a stale legacy value`() {
        val old = "Game%20%28Japan%29.gba"
        val current = "Game (Europe).gba"
        val key = VersionPreference.key("gba", current)
        assertEquals(current, VersionPreference.storedPins(mapOf(
            key to current, VersionPreference.legacyKey("gba", old) to old
        ))[key])
        assertTrue(VersionPreference.storedPins(mapOf("gba|another game|" to current)).isEmpty())
    }

    @Test fun `a fixed source filename remains fixed when another source lists it decoded`() {
        val usa = VersionPicker.Candidate("Game (USA).gba", "Game (USA).gba")
        val japan = VersionPicker.Candidate("Game (Japan).gba", "Game (Japan).gba")
        assertEquals(japan, VersionPreference.pick(listOf(usa, japan), VersionPreferences.defaultFor(setOf("EN"), null, null),
            "Game%20%28Japan%29.gba"))
        assertFalse(VersionPreference.matchesPin("Game (Disc 2).cue", "Game%20%28Disc%201%29.cue"))
    }

    @Test fun `global preference and console overrides survive the existing backup format`() {
        val preference = VersionPreference(languages = listOf("NL", "EN"), regions = listOf("Europe", "USA"),
            preferLatestRevision = false, sizeTieBreak = SizeTieBreak.SMALLER)
        val overrides = mapOf("psx" to ConsoleOverride(languages = listOf("JA")), "gba" to ConsoleOverride(regions = emptyList()))
        val values = mapOf(VersionPreferences.PINNED_KEY to VersionPreferences.toJson(preference),
            VersionPreferences.OVERRIDES_KEY to VersionPreferences.overridesToJson(overrides))
        val restored = values.mapValues { (key, value) ->
            BackupJson.decodeSetting(key, JsonParser.parseString(BackupJson.encodeSetting(value)!!.toString())) as String
        }
        assertEquals(preference, VersionPreferences.fromJson(restored[VersionPreferences.PINNED_KEY]))
        assertEquals(overrides, VersionPreferences.overridesFromJson(restored[VersionPreferences.OVERRIDES_KEY]))
        for (key in values.keys) {
            assertNull(BackupJson.decodeSetting(key, BackupJson.encodeSetting(true)))
            assertNull(BackupJson.decodeSetting(key, BackupJson.encodeSetting(42)))
        }
    }
}
