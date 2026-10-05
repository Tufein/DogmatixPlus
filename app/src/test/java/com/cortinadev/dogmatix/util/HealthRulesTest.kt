package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthRulesTest {
    private val now = 100L * HealthRules.DAY_MS
    private val gb = 1024L * 1024 * 1024

    @Test fun `no sources is not set up and offers the sources screen`() {
        val r = HealthRules.sources(listOf(emptyList(), emptyList()), 0, now)
        assertEquals(HealthStatus.NOT_SET_UP, r.status)
        assertEquals(HealthFix.OPEN_SOURCES, r.fix)
    }

    @Test fun `sources grade by how many scans failed`() {
        assertEquals(HealthStatus.LOOK, HealthRules.sources(listOf(listOf(null, null)), 0, now).status)
        assertEquals(HealthStatus.PROBLEM, HealthRules.sources(listOf(listOf(false), listOf(false, null)), now, now).status)
        val partial = HealthRules.sources(listOf(listOf(true, false)), now, now)
        assertEquals(HealthStatus.LOOK, partial.status)
        assertEquals(1L, partial.a)
        assertEquals(HealthStatus.OK, HealthRules.sources(listOf(listOf(true, null)), now - HealthRules.DAY_MS, now).status)
    }

    @Test fun `an old scan is worth a look`() {
        val r = HealthRules.sources(listOf(listOf(true)), now - 40 * HealthRules.DAY_MS, now)
        assertEquals(HealthStatus.LOOK, r.status)
        assertEquals(40L, r.b)
    }

    @Test fun `storage reads the free space and the queue`() {
        assertEquals(HealthStatus.NOT_SET_UP, HealthRules.storage(false, 50 * gb, 0, 0).status)
        assertEquals(HealthStatus.OK, HealthRules.storage(true, 50 * gb, 10 * gb, 0).status)
        assertEquals(HealthStatus.LOOK, HealthRules.storage(true, 2 * gb, 10 * gb, 0).status)
        assertEquals(HealthStatus.PROBLEM, HealthRules.storage(true, gb / 2, 10 * gb, 0).status)
        val short = HealthRules.storage(true, 50 * gb, 10 * gb, 3 * gb)
        assertEquals(HealthStatus.PROBLEM, short.status)
        assertEquals(HealthDetail.STORAGE_QUEUE_SHORT, short.detail)
        assertEquals(HealthStatus.OK, HealthRules.storage(true, null, 10 * gb, null).status)
    }

    @Test fun `bios counts incomplete systems`() {
        assertEquals(HealthStatus.NOT_SET_UP, HealthRules.bios(false, 3, 3).status)
        assertEquals(HealthDetail.BIOS_NONE_NEEDED, HealthRules.bios(true, 0, 0).detail)
        val r = HealthRules.bios(true, 5, 2)
        assertEquals(HealthStatus.LOOK, r.status)
        assertEquals(2L, r.a)
        assertEquals(5L, r.b)
        assertEquals(HealthStatus.OK, HealthRules.bios(true, 5, 0).status)
    }

    @Test fun `covers offer a retry`() {
        assertEquals(HealthFix.RETRY_COVERS, HealthRules.covers(4).fix)
        assertEquals(HealthStatus.OK, HealthRules.covers(0).status)
    }

    @Test fun `save sync follows the folders then the server then the last run`() {
        fun r(folders: Boolean = true, romm: Boolean = true, err: String? = null, conflicts: Int = 0, failed: Int = 0, last: Long = now) =
            HealthRules.saveSync(folders, romm, false, err, conflicts, failed, last, now)
        assertEquals(HealthStatus.NOT_SET_UP, r(folders = false).status)
        assertEquals(HealthDetail.SAVE_NO_ROMM, r(romm = false).detail)
        assertEquals(HealthStatus.PROBLEM, r(err = "boom").status)
        assertEquals(HealthStatus.LOOK, r(conflicts = 2).status)
        assertEquals(HealthStatus.LOOK, r(failed = 1).status)
        assertEquals(HealthDetail.SAVE_NEVER, r(last = 0).detail)
        assertEquals(HealthDetail.SAVE_STALE, r(last = now - 20 * HealthRules.DAY_MS).detail)
        assertEquals(HealthStatus.OK, r().status)
    }

    @Test fun `server text is redacted`() {
        val r = HealthRules.romm(true, false, "UNREACHABLE", null, "failed to reach https://user:pw@romm.example.com/api token=abc12345")
        assertFalse(r.text.contains("romm.example.com"))
        assertFalse(r.text.contains("abc12345"))
        assertFalse(HealthRules.saveSync(true, true, false, "bad https://x.example/y", 0, 0, 0, now).text.contains("x.example"))
    }

    @Test fun `romm maps error kinds to fixes`() {
        assertEquals(HealthStatus.NOT_SET_UP, HealthRules.romm(false, false, null, null, null).status)
        val ok = HealthRules.romm(true, true, null, "5.3.1", null)
        assertEquals(HealthStatus.OK, ok.status)
        assertEquals("5.3.1", ok.text)
        assertEquals(HealthFix.OPEN_ROMM, HealthRules.romm(true, true, "AUTH", "5.3.1", null).fix)
        assertEquals(HealthFix.OPEN_ROMM, HealthRules.romm(true, false, "TLS", null, null).fix)
        assertEquals(HealthFix.REFRESH_ROMM, HealthRules.romm(true, false, "UNREACHABLE", null, null).fix)
        assertEquals(HealthFix.REFRESH_ROMM, HealthRules.romm(true, false, "SERVER", null, "500").fix)
        assertEquals(HealthStatus.PROBLEM, HealthRules.romm(true, false, null, null, null).status)
    }

    @Test fun `webdav checks connection, errors and backup age`() {
        fun r(configured: Boolean = true, connected: Boolean? = true, auto: Boolean = true, last: Long = now - HealthRules.DAY_MS, stale: Boolean = false, attention: Int = 0) =
            HealthRules.webdav(configured, connected, auto, last, stale, attention, now)
        assertEquals(HealthStatus.NOT_SET_UP, r(configured = false).status)
        assertEquals(HealthStatus.PROBLEM, r(connected = false).status)
        assertEquals(HealthStatus.LOOK, r(attention = 1).status)
        assertEquals(HealthDetail.DAV_BACKUP_OVERDUE, r(stale = true, last = now - 9 * HealthRules.DAY_MS).detail)
        assertEquals(HealthDetail.DAV_NO_BACKUP, r(last = 0).detail)
        assertEquals(HealthDetail.DAV_OK_NO_BACKUP, r(auto = false, last = 0).detail)
        assertEquals(1L, r().a)
        assertEquals(HealthStatus.OK, r(connected = null).status)
    }

    @Test fun `retro achievements needs both name and key`() {
        assertEquals(HealthStatus.NOT_SET_UP, HealthRules.retroAchievements("", "").status)
        assertEquals(HealthStatus.LOOK, HealthRules.retroAchievements("me", "").status)
        assertEquals(HealthStatus.OK, HealthRules.retroAchievements("me", "key").status)
    }

    @Test fun `frontends are only a look when es-de is half done`() {
        val none = FrontendCheck.evaluate(FrontendCheck.State(false, false, false, false, false, false))
        assertEquals(HealthStatus.NOT_SET_UP, HealthRules.frontends(none).status)
        val half = FrontendCheck.evaluate(FrontendCheck.State(true, true, false, false, false, false))
        assertEquals(HealthStatus.LOOK, HealthRules.frontends(half).status)
        val iisu = FrontendCheck.evaluate(FrontendCheck.State(true, false, false, true, false, false))
        val r = HealthRules.frontends(iisu)
        assertEquals(HealthStatus.OK, r.status)
        assertEquals(1L, r.a)
    }

    @Test fun `battery is only ever a hint and notifications a look`() {
        assertEquals(HealthStatus.HINT, HealthRules.battery(false).status)
        assertEquals(HealthStatus.OK, HealthRules.battery(true).status)
        assertEquals(HealthFix.OPEN_APP_NOTIFICATION_SETTINGS, HealthRules.notifications(false).fix)
        assertNull(HealthRules.notifications(true).fix)
    }

    @Test fun `update states`() {
        assertEquals(HealthStatus.HINT, HealthRules.update(null, null).status)
        assertEquals(HealthStatus.LOOK, HealthRules.update(true, "v6.0.0").status)
        assertEquals(HealthStatus.OK, HealthRules.update(false, "v5.0.0").status)
    }

    @Test fun `days since never goes negative`() {
        assertEquals(0, HealthRules.daysSince(now + 5, now))
        assertEquals(0, HealthRules.daysSince(0, now))
        assertEquals(3, HealthRules.daysSince(now - 3 * HealthRules.DAY_MS - 5, now))
    }

    @Test fun `inline fixes are the four the screen runs itself`() {
        assertEquals(4, HealthFix.entries.count { it.inline })
        assertTrue(HealthFix.RETRY_COVERS.inline)
        assertFalse(HealthFix.OPEN_SOURCES.inline)
    }
}
