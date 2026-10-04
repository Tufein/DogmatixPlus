package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Test

class EsdePlayCountsTest {
    @Test fun `es-de play counts are read and ranked`() {
        val xml = """<gameList>
            <game><path>./a.zip</path><name>Alpha</name><playcount>3</playcount><lastplayed>20261001T120000</lastplayed></game>
            <game><path>./b.zip</path><name>Beta &amp; Co</name><playcount>9</playcount><lastplayed>20260901T120000</lastplayed></game>
            <game><path>./c.zip</path><name>Never</name></game></gameList>"""
        val plays = EsdePlayStats.parse("gba", xml)
        assertEquals(2, plays.size)
        assertEquals("Beta & Co", EsdePlayStats.top(plays).first().name)
        assertEquals("Alpha", EsdePlayStats.recent(plays).first().name)
        assertEquals(1790856000000L, EsdePlayStats.parseDate("20261001T120000"))
    }
}
