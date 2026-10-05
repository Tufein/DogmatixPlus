package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSyncPolicyTest {
    @Test fun `intervals cycle and wrap`() {
        assertEquals(12, BackgroundSyncPolicy.cycle(6, 1))
        assertEquals(3, BackgroundSyncPolicy.cycle(6, -1))
        assertEquals(1, BackgroundSyncPolicy.cycle(24, 1))
        assertEquals(24, BackgroundSyncPolicy.cycle(1, -1))
        assertEquals(12, BackgroundSyncPolicy.cycle(7, 1))   // unknown counts as the default
    }

    @Test fun `period is never below what Android allows`() {
        assertEquals(3600L * 1000, BackgroundSyncPolicy.periodMillis(0))   // never less than an hour for whole-hour choices
        assertEquals(6L * 3600 * 1000, BackgroundSyncPolicy.periodMillis(6))
    }

    @Test fun `notifies only when there is something to do or something broke`() {
        assertFalse(BackgroundSyncPolicy.shouldNotify(0, 0))
        assertTrue(BackgroundSyncPolicy.shouldNotify(2, 0))
        assertTrue(BackgroundSyncPolicy.shouldNotify(0, 1))
        assertFalse(BackgroundSyncPolicy.mayRun(enabled = true, configured = false))
        assertTrue(BackgroundSyncPolicy.mayRun(enabled = true, configured = true))
    }
}

class DownloadPolicyTest {
    private val night = DownloadRules(nightOnly = true, nightStart = 23 * 60, nightEnd = 7 * 60)

    @Test fun `no conditions means start at once`() {
        assertTrue(DownloadPolicy.waitingFor(DownloadRules(), DeviceConditions(false, false, 600)).isEmpty())
    }

    @Test fun `waits for wifi and the charger`() {
        val c = DownloadRules(wifiOnly = true, chargingOnly = true)
        assertEquals(listOf(WaitReason.WIFI, WaitReason.CHARGER), DownloadPolicy.waitingFor(c, DeviceConditions(false, false, 0)))
        assertEquals(listOf(WaitReason.CHARGER), DownloadPolicy.waitingFor(c, DeviceConditions(true, false, 0)))
        assertTrue(DownloadPolicy.waitingFor(c, DeviceConditions(true, true, 0)).isEmpty())
    }

    @Test fun `a night window past midnight`() {
        assertTrue(DownloadPolicy.inWindow(23 * 60 + 30, 23 * 60, 7 * 60))
        assertTrue(DownloadPolicy.inWindow(3 * 60, 23 * 60, 7 * 60))
        assertFalse(DownloadPolicy.inWindow(12 * 60, 23 * 60, 7 * 60))
        assertFalse(DownloadPolicy.inWindow(7 * 60, 23 * 60, 7 * 60))   // the end is exclusive
        assertEquals(listOf(WaitReason.NIGHT), DownloadPolicy.waitingFor(night, DeviceConditions(true, true, 12 * 60)))
    }

    @Test fun `a window within one day and a whole-day window`() {
        assertTrue(DownloadPolicy.inWindow(10 * 60, 9 * 60, 17 * 60))
        assertFalse(DownloadPolicy.inWindow(17 * 60, 9 * 60, 17 * 60))
        assertTrue(DownloadPolicy.inWindow(5, 600, 600))
    }

    @Test fun `times are formatted and shifted in half hours`() {
        assertEquals("23:00", DownloadPolicy.formatMinutes(23 * 60))
        assertEquals("07:30", DownloadPolicy.formatMinutes(7 * 60 + 30))
        assertEquals(23 * 60 + 30, DownloadPolicy.shift(23 * 60, 1))
        assertEquals(0, DownloadPolicy.shift(23 * 60 + 30, 1))
        assertEquals(23 * 60 + 30, DownloadPolicy.shift(0, -1))
    }
}
