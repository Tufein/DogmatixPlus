package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumePlanTest {
    @Test fun `resume appends only when the server continues exactly at the partial file`() {
        assertEquals(ResumePlan.ContentRange(100, 999, 1000), ResumePlan.parseContentRange("bytes 100-999/1000"))
        assertEquals(ResumePlan.ContentRange(5, 9, null), ResumePlan.parseContentRange("bytes 5-9/*"))
        assertNull(ResumePlan.parseContentRange("bytes */1000"))
        assertEquals(ResumePlan.Action.APPEND, ResumePlan.decide(100, 206, "bytes 100-999/1000"))
        assertEquals(ResumePlan.Action.RESTART, ResumePlan.decide(100, 200, null))               // server ignored the range
        assertEquals(ResumePlan.Action.RESTART, ResumePlan.decide(100, 206, "bytes 0-999/1000")) // wrong start
        assertEquals(ResumePlan.Action.RESTART, ResumePlan.decide(100, 206, "bytes 100-999/1000", expectedTotal = 2000)) // another file
        assertEquals(ResumePlan.Action.RESTART, ResumePlan.decide(0, 206, "bytes 0-9/10"))
        assertEquals("\"abc\"", ResumePlan.validator("\"abc\"", "Mon"))
        assertEquals("Mon, 01 Jan 2024", ResumePlan.validator("W/\"weak\"", "Mon, 01 Jan 2024"))
        assertNull(ResumePlan.validator(null, " "))
    }
}
