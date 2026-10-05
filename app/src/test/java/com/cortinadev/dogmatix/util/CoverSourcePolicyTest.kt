package com.cortinadev.dogmatix.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverSourcePolicyTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun `a found cover stays fresh for a month`() {
        assertTrue(CoverSourcePolicy.isFresh("https://x/a.png", 0, 29 * day))
        assertFalse(CoverSourcePolicy.isFresh("https://x/a.png", 0, 31 * day))
    }

    @Test fun `a miss is retried after three days`() {
        assertTrue(CoverSourcePolicy.isFresh("", 0, 2 * day))
        assertFalse(CoverSourcePolicy.isFresh("", 0, 4 * day))
    }

    @Test fun `a time in the future counts as stale`() {
        assertFalse(CoverSourcePolicy.isFresh("https://x/a.png", 10 * day, 0))
    }
}
