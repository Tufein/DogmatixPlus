package com.cortinadev.dogmatix.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    @Test fun `shown once after an update`() {
        assertTrue(WhatsNew.shouldShow(lastSeen = 23, current = 24, onboarded = true))
        assertFalse(WhatsNew.shouldShow(lastSeen = 24, current = 24, onboarded = true))
    }

    @Test fun `an update from before the notes were stored counts`() {
        assertTrue(WhatsNew.shouldShow(lastSeen = 0, current = 24, onboarded = true))
    }

    @Test fun `not on a fresh install`() {
        assertFalse(WhatsNew.shouldShow(lastSeen = 0, current = 24, onboarded = false))
    }

    @Test fun `not after a downgrade`() {
        assertFalse(WhatsNew.shouldShow(lastSeen = 25, current = 24, onboarded = true))
    }
}
