package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BandwidthAndScheduleTest {
    @Test fun `token bucket lets one second through and then makes callers wait`() {
        var now = 0L
        val bucket = TokenBucket { now }
        bucket.bytesPerSecond = 1000
        assertEquals(0, bucket.take(1000))
        assertEquals(500, bucket.take(500))      // overdrawn by 500 bytes at 1000 B/s
        now += 1500
        assertEquals(0, bucket.take(900))
    }

    @Test fun `no limit means no waiting`() {
        val bucket = TokenBucket { 0L }
        assertEquals(0, bucket.take(10_000_000))
    }

    @Test fun `speed limit is in KB per second and can be lifted at night`() {
        assertEquals(500L * 1024, SpeedLimit.effectiveBytesPerSecond(500f, false, 12 * 60, 23 * 60, 7 * 60))
        assertEquals(0L, SpeedLimit.effectiveBytesPerSecond(Float.POSITIVE_INFINITY, false, 0, 0, 0))
        assertEquals(0L, SpeedLimit.effectiveBytesPerSecond(500f, true, 2 * 60, 23 * 60, 7 * 60))
        assertEquals(500L * 1024, SpeedLimit.effectiveBytesPerSecond(500f, true, 12 * 60, 23 * 60, 7 * 60))
    }

    @Test fun `background scan is due after its interval, inside the night window when asked`() {
        val h = 3_600_000L
        assertTrue(AutoScanPolicy.isDue(now = 30 * h, last = 6 * h, hours = 24, nightOnly = false, minuteOfDay = 600, nightStart = 1380, nightEnd = 420))
        assertFalse(AutoScanPolicy.isDue(now = 20 * h, last = 6 * h, hours = 24, nightOnly = false, minuteOfDay = 600, nightStart = 1380, nightEnd = 420))
        // An hour of slack keeps a daily scan from sliding later every day.
        assertTrue(AutoScanPolicy.isDue(now = 29 * h + 1, last = 6 * h, hours = 24, nightOnly = false, minuteOfDay = 600, nightStart = 1380, nightEnd = 420))
        assertFalse(AutoScanPolicy.isDue(now = 40 * h, last = 6 * h, hours = 24, nightOnly = true, minuteOfDay = 600, nightStart = 1380, nightEnd = 420))
        assertTrue(AutoScanPolicy.isDue(now = 40 * h, last = 6 * h, hours = 24, nightOnly = true, minuteOfDay = 120, nightStart = 1380, nightEnd = 420))
        assertTrue(AutoScanPolicy.isDue(now = 40 * h, last = 0, hours = 24, nightOnly = false, minuteOfDay = 0, nightStart = 0, nightEnd = 0))
    }

    @Test fun `new games are what a rescan found in the last two weeks`() {
        val now = 100L * 24 * 3_600_000
        assertTrue(NewGames.isNew(now - 3_600_000, now))
        assertFalse(NewGames.isNew(0, now))
        assertFalse(NewGames.isNew(now - 15L * 24 * 3_600_000, now))
    }
}
