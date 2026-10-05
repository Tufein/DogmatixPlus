package com.cortinadev.dogmatix.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommServerParserTest {

    private fun json(s: String) = JsonParser.parseString(s)

    @Test fun `version is read from SYSTEM VERSION of RomM 3_5 and later`() {
        val heartbeat = json(
            """{"SYSTEM": {"VERSION": "5.3.1", "SHOW_SETUP_WIZARD": false},
                "WATCHER": {"ENABLED": true, "TITLE": "Rescan on filesystem change"},
                "METADATA_SOURCES": {"ANY_SOURCE_ENABLED": true, "IGDB_API_ENABLED": true},
                "FILESYSTEM": {"FS_PLATFORMS": ["gba", "psx"]}}"""
        )
        assertEquals("5.3.1", RommServerParser.version(heartbeat))
    }

    @Test fun `version is read from the top level of older servers`() {
        assertEquals("3.0.1", RommServerParser.version(json("""{"VERSION": "3.0.1", "NEW_VERSION": "", "ROMM_AUTH_ENABLED": true}""")))
        assertEquals("4.0.0", RommServerParser.version(json("""{"system": {"version": "v4.0.0"}}""")))
    }

    @Test fun `a heartbeat without a version or not an object gives null`() {
        assertNull(RommServerParser.version(json("""{"WATCHER": {}}""")))
        assertNull(RommServerParser.version(json("""["5.0.0"]""")))
        assertNull(RommServerParser.version(json("""{"SYSTEM": {"VERSION": null}}""")))
        assertNull(RommServerParser.version(null))
    }

    @Test fun `the account is read with its role and avatar`() {
        val user = RommServerParser.user(json(
            """{"id": 1, "username": "tufein", "email": "t@example.com", "enabled": true, "role": "admin",
                "avatar_path": "users/557365723a31/profile/avatar.png", "last_login": "2026-09-30T18:02:11"}"""
        ))!!
        assertEquals(1, user.id)
        assertEquals("tufein", user.username)
        assertEquals("admin", user.role)
        assertEquals(
            "https://romm.local/assets/romm/assets/users/557365723a31/profile/avatar.png",
            RommServerParser.avatarUrl("https://romm.local/", user.avatarPath)
        )
    }

    @Test fun `an enum style role and a missing avatar are tolerated`() {
        val user = RommServerParser.user(json("""{"id": "7", "username": "kid", "role": "Role.VIEWER", "avatar_path": ""}"""))!!
        assertEquals(7, user.id)
        assertEquals("viewer", user.role)
        assertNull(user.avatarPath)
        assertNull(RommServerParser.avatarUrl("https://romm.local", user.avatarPath))
    }

    @Test fun `no username means no account`() {
        assertNull(RommServerParser.user(json("""{"id": 3}""")))
        assertNull(RommServerParser.user(json("""{"detail": "Not authenticated"}""")))
    }

    @Test fun `stats are read whatever is missing`() {
        val full = RommServerParser.stats(json(
            """{"PLATFORMS": 12, "ROMS": 1834, "SAVES": 77, "STATES": 31, "SCREENSHOTS": 9, "TOTAL_FILESIZE_BYTES": 412316860416}"""
        ))
        assertEquals(12, full.platforms)
        assertEquals(1834, full.roms)
        assertEquals(77, full.saves)
        assertEquals(31, full.states)
        assertEquals(9, full.screenshots)
        assertEquals(412316860416L, full.totalBytes)

        val older = RommServerParser.stats(json("""{"PLATFORMS": "4", "ROMS": 90, "FILESIZE": 1024}"""))
        assertEquals(4, older.platforms)
        assertEquals(1024L, older.totalBytes)
        assertNull(older.saves)

        val garbage = RommServerParser.stats(json("""["nope"]"""))
        assertNull(garbage.platforms)
        assertNull(garbage.totalBytes)
    }

    @Test fun `versions compare by their numbers`() {
        assertEquals(true, RommServerParser.atLeast("5.3.1", 4))
        assertEquals(true, RommServerParser.atLeast("4.0.0", 4, 0, 0))
        assertEquals(false, RommServerParser.atLeast("3.10.3", 4))
        assertEquals(true, RommServerParser.atLeast("3.10.3", 3, 7))
        assertEquals(true, RommServerParser.atLeast("v4.1.0-beta.2", 4, 1))
        assertEquals(false, RommServerParser.atLeast("3.9", 3, 10))
        assertNull(RommServerParser.atLeast("development", 4))
        assertNull(RommServerParser.atLeast(null, 4))
    }

    @Test fun `short version labels`() {
        assertEquals("5.3.1", RommServerParser.shortVersion("5.3.1"))
        assertEquals("dev", RommServerParser.shortVersion("development"))
        assertNull(RommServerParser.shortVersion(null))
        assertTrue(RommServerParser.versionParts("4.0.0") == listOf(4, 0, 0))
        assertFalse(RommServerParser.versionParts("abc") != null)
    }
}
