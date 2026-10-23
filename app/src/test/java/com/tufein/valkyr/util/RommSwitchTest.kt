package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RommSwitchTest {
    @Test fun onKeepsTheSavedValue() {
        assertEquals("https://romm.local", RommSwitch.effective(true, "https://romm.local"))
    }

    @Test fun offHidesTheValueWithoutLosingIt() {
        assertEquals("", RommSwitch.effective(false, "https://romm.local"))
        assertEquals("", RommSwitch.effective(false, ""))
    }

    @Test fun offStillListsWhatIsOnTheDevice() {
        assertTrue(RommSwitch.listRow(enabled = false, fromRomm = true, onDevice = true))
        assertTrue(RommSwitch.listRow(enabled = false, fromRomm = false, onDevice = false))
        assertFalse(RommSwitch.listRow(enabled = false, fromRomm = true, onDevice = false))
    }

    @Test fun onListsEverything() {
        assertTrue(RommSwitch.listRow(enabled = true, fromRomm = true, onDevice = false))
    }

    @Test fun theSettingIsInTheBackupSchema() {
        assertEquals("b", SettingSchema.expectedType("romm_enabled"))
    }
}
