package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTextTest {
    @Test fun `done over total becomes a fraction`() {
        assertEquals(0.25f, ProgressText.fraction("3 / 12")!!, 0.0001f)
    }

    @Test fun `spacing and surrounding words do not matter`() {
        assertEquals(0.5f, ProgressText.fraction("Syncing… 5/10")!!, 0.0001f)
    }

    @Test fun `nothing usable gives null`() {
        assertNull(ProgressText.fraction(null))
        assertNull(ProgressText.fraction(""))
        assertNull(ProgressText.fraction("preparing"))
        assertNull(ProgressText.fraction("4 / 0"))
    }

    @Test fun `more done than total is capped at one`() {
        assertEquals(1f, ProgressText.fraction("13 / 12")!!, 0.0001f)
    }
}
