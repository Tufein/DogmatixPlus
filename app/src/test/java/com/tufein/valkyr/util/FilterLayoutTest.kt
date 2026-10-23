package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterLayoutTest {
    @Test fun chipsKeepTheTouchTargetAndGap() {
        assertEquals(48, FilterLayout.CHIP_HEIGHT)
        assertEquals(8, FilterLayout.GAP)
    }

    @Test fun labelAndControlShareTheRow() {
        val (label, control) = FilterLayout.weights()
        assertEquals(1f, label + control, 0.0001f)
        assertTrue(label < control)
    }

    @Test fun viewSwitchStacksOnlyAtBigText() {
        assertFalse(FilterLayout.stackViewSwitch(1.0f))
        assertFalse(FilterLayout.stackViewSwitch(1.3f))
        assertTrue(FilterLayout.stackViewSwitch(1.5f))
        assertTrue(FilterLayout.stackViewSwitch(2.0f))
    }

    @Test fun labelStartMatchesTheResultCount() {
        assertEquals(24, FilterLayout.labelStart())
    }
}
