package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetLayoutTest {
    @Test fun `a short widget shows no titles`() {
        assertEquals(0, WidgetLayout.titleLines(heightDp = 110, available = 3))
    }

    @Test fun `a taller widget shows more titles up to the maximum`() {
        assertEquals(1, WidgetLayout.titleLines(heightDp = 125, available = 3))
        assertEquals(2, WidgetLayout.titleLines(heightDp = 140, available = 3))
        assertEquals(3, WidgetLayout.titleLines(heightDp = 160, available = 3))
        assertEquals(3, WidgetLayout.titleLines(heightDp = 400, available = 10))
    }

    @Test fun `never more lines than there are titles`() {
        assertEquals(1, WidgetLayout.titleLines(heightDp = 400, available = 1))
        assertEquals(0, WidgetLayout.titleLines(heightDp = 400, available = 0))
        assertEquals(0, WidgetLayout.titleLines(heightDp = 400, available = -2))
    }

    @Test fun `an unknown height gets the default two lines`() {
        assertEquals(2, WidgetLayout.titleLines(heightDp = 0, available = 3))
        assertEquals(1, WidgetLayout.titleLines(heightDp = -1, available = 1))
    }

    @Test fun `the accent stays as it is on a dark panel`() {
        assertEquals(0xFFFF7F00.toInt(), WidgetLayout.accentTextOn(0xFFFF7F00.toInt(), darkPanel = true))
    }

    @Test fun `the accent is darkened on a light panel`() {
        val darker = WidgetLayout.accentTextOn(0xFFFF8000.toInt(), darkPanel = false, darken = 0.5f)
        assertEquals(0xFF, darker ushr 24 and 0xFF)
        assertEquals(127, darker ushr 16 and 0xFF)
        assertEquals(64, darker ushr 8 and 0xFF)
        assertEquals(0, darker and 0xFF)
    }
}
