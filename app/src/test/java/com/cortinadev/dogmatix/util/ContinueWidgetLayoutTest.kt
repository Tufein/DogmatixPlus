package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWidgetLayoutTest {
    @Test
    fun `rows follow the height`() {
        assertEquals(1, ContinueWidgetLayout.rows(110, 4))
        assertEquals(3, ContinueWidgetLayout.rows(180, 4))
        assertEquals(4, ContinueWidgetLayout.rows(400, 12))
    }

    @Test
    fun `rows never exceed the games`() {
        assertEquals(2, ContinueWidgetLayout.rows(400, 2))
        assertEquals(0, ContinueWidgetLayout.rows(400, 0))
    }

    @Test
    fun `unknown height gets three rows`() {
        assertEquals(3, ContinueWidgetLayout.rows(0, 4))
        assertEquals(1, ContinueWidgetLayout.rows(0, 1))
    }

    @Test
    fun `subtitle joins time and device`() {
        assertEquals("2 hr. ago · Thor", ContinueWidgetLayout.subtitle("2 hr. ago", "Thor"))
        assertEquals("2 hr. ago", ContinueWidgetLayout.subtitle("2 hr. ago", null))
        assertEquals("Thor", ContinueWidgetLayout.subtitle(null, "Thor"))
        assertEquals("", ContinueWidgetLayout.subtitle(" ", ""))
    }

    @Test
    fun `thumb size is clamped`() {
        assertEquals(68, ContinueWidgetLayout.thumbPx(1f))
        assertEquals(102, ContinueWidgetLayout.thumbPx(3f))
        assertEquals(136, ContinueWidgetLayout.thumbPx(6f))
    }
}
