package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerRulesTest {
    private val both = PowerSettings(lowBatteryOn = true, batteryPercent = 20, heatOn = true)
    private fun reading(level: Int? = 80, charging: Boolean = false, temp: Int? = 300, severe: Boolean = false) =
        PowerReading(level, charging, temp, severe)

    @Test fun `off by default holds nothing`() {
        val off = PowerSettings()
        assertFalse(off.any)
        assertEquals(PowerHold(), PowerRules.next(PowerHold(), off, reading(level = 1, temp = 600, severe = true)))
    }

    @Test fun `low battery pauses below the limit and only goes on above limit plus five`() {
        var hold = PowerRules.next(PowerHold(), both, reading(level = 20))
        assertFalse(hold.lowBattery)
        hold = PowerRules.next(hold, both, reading(level = 19))
        assertTrue(hold.lowBattery)
        assertEquals(listOf(WaitReason.LOW_BATTERY), hold.reasons)
        hold = PowerRules.next(hold, both, reading(level = 22))
        assertTrue(hold.lowBattery)
        hold = PowerRules.next(hold, both, reading(level = 25))
        assertTrue(hold.lowBattery)
        hold = PowerRules.next(hold, both, reading(level = 26))
        assertFalse(hold.lowBattery)
        // Back in the band without having dropped below the limit: still fine.
        hold = PowerRules.next(hold, both, reading(level = 23))
        assertFalse(hold.lowBattery)
    }

    @Test fun `charging lifts the battery hold at once`() {
        val low = PowerHold(lowBattery = true)
        assertFalse(PowerRules.next(low, both, reading(level = 3, charging = true)).lowBattery)
        assertTrue(PowerRules.next(PowerHold(), both, reading(level = 3)).lowBattery)
    }

    @Test fun `unknown level never holds`() {
        assertFalse(PowerRules.next(PowerHold(lowBattery = true), both, reading(level = null)).lowBattery)
    }

    @Test fun `heat pauses at 45 and goes on below 40`() {
        var hold = PowerRules.next(PowerHold(), both, reading(temp = 449))
        assertFalse(hold.hot)
        hold = PowerRules.next(hold, both, reading(temp = 450))
        assertTrue(hold.hot)
        assertEquals(listOf(WaitReason.HOT), hold.reasons)
        hold = PowerRules.next(hold, both, reading(temp = 400))
        assertTrue(hold.hot)
        hold = PowerRules.next(hold, both, reading(temp = 399))
        assertFalse(hold.hot)
        hold = PowerRules.next(hold, both, reading(temp = 420))
        assertFalse(hold.hot)
    }

    @Test fun `severe thermal status counts as hot whatever the battery says`() {
        assertTrue(PowerRules.next(PowerHold(), both, reading(temp = null, severe = true)).hot)
        assertTrue(PowerRules.next(PowerHold(), both, reading(temp = 300, severe = true)).hot)
        assertFalse(PowerRules.next(PowerHold(hot = true), both, reading(temp = null)).hot)
        assertFalse(PowerRules.next(PowerHold(), both.copy(heatOn = false), reading(severe = true)).hot)
    }

    @Test fun `both reasons together`() {
        val hold = PowerRules.next(PowerHold(), both, reading(level = 10, temp = 470))
        assertEquals(listOf(WaitReason.LOW_BATTERY, WaitReason.HOT), hold.reasons)
        assertTrue(hold.any)
    }

    @Test fun `limit is clamped and stepped by five`() {
        assertEquals(25, PowerRules.shift(20, 1))
        assertEquals(50, PowerRules.shift(50, 1))
        assertEquals(5, PowerRules.shift(5, -1))
        assertEquals(5, PowerRules.shift(2, 0))
        assertEquals(50, PowerRules.clampPercent(90))
        // A stored value out of range acts as the nearest allowed one.
        assertTrue(PowerRules.next(PowerHold(), both.copy(batteryPercent = 90), reading(level = 49)).lowBattery)
    }

    @Test fun `level from level and scale`() {
        assertEquals(50, PowerRules.levelPercent(50, 100))
        assertEquals(100, PowerRules.levelPercent(255, 255))
        assertNull(PowerRules.levelPercent(-1, 100))
        assertNull(PowerRules.levelPercent(10, 0))
    }
}

class ThermalLatchTest {
    @Test fun `severe makes it hot and it stays hot while moderate`() {
        val latch = ThermalLatch(coolMs = 60_000)
        assertFalse(latch.update(ThermalLatch.MODERATE, 0))
        assertTrue(latch.update(ThermalLatch.SEVERE, 1_000))
        assertTrue(latch.update(ThermalLatch.MODERATE, 100_000))
        assertNull(latch.releaseAt())
    }

    @Test fun `it lets go only after a full minute below moderate`() {
        val latch = ThermalLatch(coolMs = 60_000)
        latch.update(ThermalLatch.SEVERE, 0)
        assertTrue(latch.update(1, 10_000))
        assertEquals(70_000L, latch.releaseAt())
        assertTrue(latch.update(1, 69_999))
        assertFalse(latch.update(1, 70_000))
        assertNull(latch.releaseAt())
        assertFalse(latch.update(ThermalLatch.MODERATE, 80_000))
    }

    @Test fun `a bounce back up restarts the minute`() {
        val latch = ThermalLatch(coolMs = 60_000)
        latch.update(ThermalLatch.SEVERE, 0)
        latch.update(0, 10_000)
        assertTrue(latch.update(ThermalLatch.MODERATE, 50_000))
        assertTrue(latch.update(0, 60_000))
        assertTrue(latch.update(0, 100_000))
        assertFalse(latch.update(0, 120_000))
    }

    @Test fun `reset forgets the heat`() {
        val latch = ThermalLatch()
        latch.update(ThermalLatch.SEVERE, 0)
        latch.reset()
        assertFalse(latch.update(0, 1))
    }
}
