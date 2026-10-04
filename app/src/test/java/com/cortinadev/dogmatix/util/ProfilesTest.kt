package com.cortinadev.dogmatix.util

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {
    @Test fun `profiles hide consoles and tags and guard the switch with a pin`() {
        val kid = Profile("k", "Kid", setOf("sony_playstation"), setOf("Adult", " Hack "))
        val back = Profiles.fromJson(Profiles.toJson(listOf(kid)))
        assertEquals(listOf(kid.copy(hiddenTags = setOf("Adult", " Hack "))), back)
        val r = Profiles.restrictionsOf(back, "k")
        assertTrue(r.active)
        assertEquals(setOf("Adult", "Hack"), r.hiddenTags)
        assertFalse(r.allows("sony_playstation", emptyList()))
        assertFalse(r.allows("gba", listOf("hack")))
        assertTrue(r.allows("gba", listOf("Europe")))
        assertFalse(Profiles.restrictionsOf(back, "").active)
        assertEquals(setOf("Adult", "Beta"), Profiles.parseTags("Adult, ;Beta ,"))
        val h = Profiles.pinHash("1234")
        assertTrue(Profiles.pinMatches("1234", h))
        assertFalse(Profiles.pinMatches("4321", h))
        assertTrue(Profiles.pinMatches("anything", ""))
    }

    @Test fun pinsAreSaltedAndOldHashesStillWork() {
        val a = Profiles.pinHash("1234")
        val b = Profiles.pinHash("1234")
        assertTrue(a.startsWith("pbkdf2$"))
        assertFalse("each PIN gets its own salt", a == b)
        assertTrue(Profiles.pinMatches(" 1234 ", a))
        assertFalse(Profiles.pinMatches("12345", a))
        assertFalse(Profiles.pinMatches("1234", a.substringBeforeLast('$') + "$00"))
        // The 3.0.0 format: SHA-256 of "dogmatix-profile:" + PIN.
        val legacy = MessageDigest.getInstance("SHA-256").digest("dogmatix-profile:1234".toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(Profiles.pinMatches("1234", legacy))
        assertFalse(Profiles.pinMatches("0000", legacy))
        assertFalse(Profiles.pinMatches("1234", "pbkdf2\$broken"))
    }
}
