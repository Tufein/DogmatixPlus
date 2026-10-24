package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScreenScraperQuotaTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(h: Int, day: Int = 10) = ZonedDateTime.of(2026, 3, day, h, 0, 0, 0, paris).toInstant().toEpochMilli()
    private fun user(used: Int?, max: Int? = 20000) = ScreenScraper.User("sam", 2, used, max, level = "1", requestsKoToday = 3, maxRequestsKoPerDay = 2000)

    @Test fun `no numbers is unknown`() {
        assertEquals(ScreenScraperQuota.State.Unknown, ScreenScraperQuota.state(null, 0, 0, at(12)))
    }

    @Test fun `numbers of today are shown with the thread limit`() {
        val s = ScreenScraperQuota.state(user(120), at(9), 0, at(12)) as ScreenScraperQuota.State.Open
        assertEquals(120, s.used)
        assertEquals(19880, s.remaining)
        assertEquals(2, s.threads)
        assertEquals(3, s.koUsed)
    }

    @Test fun `numbers of yesterday are not shown`() {
        assertEquals(ScreenScraperQuota.State.Unknown, ScreenScraperQuota.state(user(19000), at(22, 9), 0, at(8)))
    }

    @Test fun `a used up quota resumes at the next French midnight`() {
        val s = ScreenScraperQuota.state(user(20000), at(9), 0, at(12)) as ScreenScraperQuota.State.Exhausted
        assertEquals(at(0, 11), s.resumeAt)
    }

    @Test fun `a pause counts as exhausted until it ends`() {
        val pause = at(0, 11)
        assertTrue(ScreenScraperQuota.state(null, 0, pause, at(23)) is ScreenScraperQuota.State.Exhausted)
        assertEquals(ScreenScraperQuota.State.Unknown, ScreenScraperQuota.state(null, 0, pause, pause))
    }

    @Test fun `missing limit leaves remaining unknown`() {
        val s = ScreenScraperQuota.state(user(10, null), at(9), 0, at(12)) as ScreenScraperQuota.State.Open
        assertEquals(null, s.remaining)
    }

    @Test fun `pace slows down when the quota is nearly gone`() {
        assertEquals(ScreenScraperQuota.BASE_GAP_MS, ScreenScraperQuota.gapMs(null))
        assertEquals(ScreenScraperQuota.BASE_GAP_MS, ScreenScraperQuota.gapMs(101))
        assertEquals(ScreenScraperQuota.LOW_QUOTA_GAP_MS, ScreenScraperQuota.gapMs(100))
        assertEquals(ScreenScraperQuota.LOW_QUOTA_GAP_MS, ScreenScraperQuota.gapMs(0))
    }

    @Test fun `wait keeps request starts a gap apart`() {
        assertEquals(0L, ScreenScraperQuota.waitMs(1_000, 0, 250))
        assertEquals(150L, ScreenScraperQuota.waitMs(1_100, 1_000, 250))
        assertEquals(0L, ScreenScraperQuota.waitMs(1_300, 1_000, 250))
        // A clock that went backwards never waits longer than one gap.
        assertEquals(250L, ScreenScraperQuota.waitMs(500, 1_000, 250))
    }
}
