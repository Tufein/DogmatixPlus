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

    @Test fun `invalid and capped ranges never authorize appending`() {
        assertNull(ResumePlan.parseContentRange("bytes 100-999/999"))
        assertNull(ResumePlan.parseContentRange("bytes 0-0/0"))
        assertNull(ResumePlan.parseContentRange("bytes 0-9223372036854775807/*"))
        assertNull(ResumePlan.parseContentRange("bytes 0-9/99999999999999999999999"))
        assertEquals(ResumePlan.Action.RESTART, ResumePlan.decide(100, 206, "bytes 100-199/1000"))
        assertEquals(ResumePlan.Action.RESTART, ResumePlan.decide(100, 206, "bytes 100-199/*"))
        assertEquals(ResumePlan.Action.APPEND, ResumePlan.decide(100, 206, "bytes 100-999/*", 1000))
    }

    @Test fun `transfer lengths preserve whole file and body sizes for resume and restart`() {
        assertEquals(ResumePlan.Transfer(100, 1000, 900), ResumePlan.transfer(100, 206, "bytes 100-999/1000", 900))
        assertEquals(ResumePlan.Transfer(100, 1000, 900), ResumePlan.transfer(100, 206, "bytes 100-999/1000", -1))
        assertEquals(ResumePlan.Transfer(0, 1000, 1000), ResumePlan.transfer(100, 200, null, 1000))
        assertEquals(ResumePlan.Transfer(0, -1, null), ResumePlan.transfer(0, 200, null, -1, 1000))
        assertEquals(ResumePlan.Transfer(0, -1, null), ResumePlan.transfer(0, 200, null, -1))
        assertEquals(ResumePlan.Transfer(0, 0, 0), ResumePlan.transfer(0, 200, null, 0))
        assertNull(ResumePlan.transfer(0, 200, null, 0, 1000))
        assertNull(ResumePlan.transfer(100, 206, "bytes 100-999/1000", 800))
        assertNull(ResumePlan.transfer(0, 206, "bytes 100-999/1000", 900))
        assertNull(ResumePlan.transfer(100, 206, "bytes 200-999/1000", 800))
    }
}
