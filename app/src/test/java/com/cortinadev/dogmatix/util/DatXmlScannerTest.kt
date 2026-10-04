package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DatXmlScannerTest {
    @Test fun `xml dat scanner handles entities, quotes, comments and empty games`() {
        val dat = DatParser.parse("""<?xml version="1.0"?>
            <!DOCTYPE datafile PUBLIC "-//Logiqx//DTD ROM Management Datafile//EN" "http://www.logiqx.com/Dats/datafile.dtd">
            <datafile><header><name>Sony - PlayStation &amp; Co</name><version>2026-10-01</version></header>
            <!-- <game name="Commented"><rom name="x.bin" size="1" crc="00000000"/></game> -->
            <game name="Tom &amp; Jerry (Europe)"><description>d</description>
              <rom name='Tom &amp; Jerry (Europe).cue' size='100' crc='ABCDEF01' sha1="0123456789ABCDEF0123456789ABCDEF01234567"/>
              <rom name="Tom &#38; Jerry (Europe).bin" size="200" crc="12345678" /></game>
            <game name="Empty"/>
            <machine name="mame &#x26; co"><rom name="a.rom" size="3" crc="deadbeef"/></machine>
            </datafile>""")
        assertEquals("Sony - PlayStation & Co", dat.name)
        assertEquals("2026-10-01", dat.version)
        assertEquals(listOf("Tom & Jerry (Europe)", "mame & co"), dat.games.map { it.name })
        assertEquals(listOf("Tom & Jerry (Europe).cue", "Tom & Jerry (Europe).bin"), dat.games[0].roms.map { it.name })
        assertEquals("abcdef01", dat.games[0].roms[0].crc)
        assertEquals(200L, dat.games[0].roms[1].size)
    }
}
