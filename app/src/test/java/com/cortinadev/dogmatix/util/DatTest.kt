package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DatTest {
    private val xml = """
        <?xml version="1.0"?>
        <!DOCTYPE datafile PUBLIC "-//Logiqx//DTD ROM Management Datafile//EN" "http://www.logiqx.com/dtds/datafile.dtd">
        <datafile>
          <header><name>Nintendo - Game Boy Advance</name><version>20261001-000000</version></header>
          <game name="Advance Wars (USA)">
            <description>Advance Wars (USA)</description>
            <rom name="Advance Wars (USA).gba" size="8388608" crc="1a2b3c4d" md5="00112233445566778899aabbccddeeff" sha1="0123456789ABCDEF0123456789ABCDEF01234567"/>
          </game>
          <game name="Tom &amp; Jerry (Europe)">
            <rom name="Tom &amp; Jerry (Europe).gba" size="4194304" crc="DEADBEEF"/>
          </game>
          <game name="Disc Game (Europe)">
            <rom name="Disc Game (Europe) (Track 1).bin" size="1000" crc="00000001"/>
            <rom name="Disc Game (Europe) (Track 2).bin" size="2000" crc="00000002"/>
          </game>
        </datafile>
    """.trimIndent()

    private val cmp = """
        clrmamepro (
        	name "Sega - Mega Drive - Genesis"
        	version 20261001
        )

        game (
        	name "Sonic The Hedgehog (USA, Europe)"
        	description "Sonic The Hedgehog (USA, Europe)"
        	rom ( name "Sonic The Hedgehog (USA, Europe).md" size 524288 crc F9394E97 md5 1BC674BE034E43C96B86487AC69D9293 sha1 6DDB7DE1E17E7F6CDB88927BD906352030DAA194 )
        )
    """.trimIndent()

    @Test fun `logiqx xml is read with entities and hashes in lower case`() {
        val dat = DatParser.parse(xml)
        assertEquals("Nintendo - Game Boy Advance", dat.name)
        assertEquals("20261001-000000", dat.version)
        assertEquals(3, dat.games.size)
        assertEquals("Tom & Jerry (Europe)", dat.games[1].name)
        assertEquals("deadbeef", dat.games[1].roms.single().crc)
        assertEquals("0123456789abcdef0123456789abcdef01234567", dat.games[0].roms.single().sha1)
        assertEquals(4, dat.romCount)
    }

    @Test fun `clrmamepro text is read`() {
        val dat = DatParser.parse(cmp)
        assertEquals("Sega - Mega Drive - Genesis", dat.name)
        val rom = dat.games.single().roms.single()
        assertEquals("Sonic The Hedgehog (USA, Europe).md", rom.name)
        assertEquals(524288L, rom.size)
        assertEquals("f9394e97", rom.crc)
        assertEquals("6ddb7de1e17e7f6cdb88927bd906352030daa194", rom.sha1)
    }

    private fun matcher() = DatMatcher(DatParser.parse(xml).games.flatMap { g -> g.roms.map { DatEntry(g.name, it.name, it.size, it.crc, it.sha1) } })

    @Test fun `loose files are verified, misnamed or unknown`() {
        val m = matcher()
        assertEquals(DatStatus.VERIFIED, m.check(ScannedFile("Advance Wars (USA).gba", 8388608, "1a2b3c4d", "0123456789abcdef0123456789abcdef01234567")).status)
        val renamed = m.check(ScannedFile("aw.gba", 8388608, "1a2b3c4d", "0123456789abcdef0123456789abcdef01234567"))
        assertEquals(DatStatus.MISNAMED, renamed.status)
        assertEquals("Advance Wars (USA).gba", renamed.canonicalName)
        // The DAT has no SHA-1 for this file, so the CRC (with the size) decides.
        assertEquals(DatStatus.MISNAMED, m.check(ScannedFile("tj.gba", 4194304, "deadbeef", null)).status)
        assertEquals(DatStatus.UNKNOWN, m.check(ScannedFile("hack.gba", 8388608, "99999999", "abcd")).status)
        assertEquals(DatStatus.SKIPPED, m.check(ScannedFile("game.chd", 1, null, null)).status)
    }

    @Test fun `zips are matched by the files inside and get the game's name`() {
        val m = matcher()
        val disc = listOf(ZipEntryInfo("t1.bin", 1000, "00000001"), ZipEntryInfo("t2.bin", 2000, "00000002"))
        val check = m.check(ScannedFile("disc.zip", 0, zipEntries = disc))
        assertEquals(DatStatus.MISNAMED, check.status)
        assertEquals("Disc Game (Europe).zip", check.canonicalName)
        assertEquals(DatStatus.VERIFIED, m.check(ScannedFile("Disc Game (Europe).zip", 0, zipEntries = disc)).status)
        assertEquals(DatStatus.UNKNOWN, m.check(ScannedFile("x.zip", 0, zipEntries = disc + ZipEntryInfo("extra.txt", 5, "12345678"))).status)
        assertEquals(listOf("Advance Wars (USA)", "Tom & Jerry (Europe)"), m.missing(listOf(check)))
    }

    @Test fun `zip central directory gives names, sizes and crcs`() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                listOf("a.gba" to "hello", "dir/b.bin" to "world!!").forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray()
        val entries = ZipDirectory.list(bytes.size.toLong()) { pos, len -> bytes.copyOfRange(pos.toInt(), pos.toInt() + len) }!!
        assertEquals(listOf("a.gba", "b.bin"), entries.map { it.name })
        assertEquals(listOf(5L, 7L), entries.map { it.size })
        assertEquals("%08x".format(CRC32().apply { update("hello".toByteArray()) }.value), entries[0].crc)
        assertNull(ZipDirectory.list(10) { _, len -> ByteArray(len) })
    }

    @Test fun `dat names are made safe for storage`() {
        assertEquals("A_B (USA).zip", DatMatcher.safeFileName("A/B (USA).zip"))
        assertEquals("Q_ A_B", DatMatcher.safeFileName("Q? A:B"))
    }
}
