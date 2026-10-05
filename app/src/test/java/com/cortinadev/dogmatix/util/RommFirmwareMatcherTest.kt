package com.cortinadev.dogmatix.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommFirmwareMatcherTest {

    private fun json(s: String) = JsonParser.parseString(s)

    private val scph5501 = "490f666e1afb15b7362b406ed1cea246"
    private val dcBoot = "e10c53c2f8b90bab96ead2d368858623"

    /** `GET /api/firmware?platform_id=19` of a RomM 4.x server (fields trimmed). */
    private val psxListing = """
        [{"id": 3, "file_name": "scph5501.bin", "file_name_no_tags": "scph5501", "file_extension": "bin",
          "file_path": "psx/bios", "file_size_bytes": 524288, "full_path": "psx/bios/scph5501.bin", "is_verified": true,
          "crc_hash": "8D8CB7E4", "md5_hash": "490F666E1AFB15B7362B406ED1CEA246", "sha1_hash": "0555c6fae8906f3f09baf5988f00e55f88e9f30b",
          "missing_from_fs": false},
         {"id": 4, "file_name": "scph1001.bin", "file_size_bytes": "524288", "md5_hash": null, "missing_from_fs": false},
         {"id": 5, "file_name": "gone.bin", "md5_hash": "00000000000000000000000000000000", "missing_from_fs": true},
         {"file_name": "no-id.bin"}]
    """

    private fun fw(id: Int, name: String, md5: String? = null, platform: Int? = 1, size: Long = 0) =
        RommFirmware(id, name, size, md5, null, null, platform)

    private val ps1 = RommFirmwareMatcher.Wanted("PlayStation", "scph5501.bin", listOf(scph5501))
    private val dc = RommFirmwareMatcher.Wanted("Dreamcast", "dc/dc_boot.bin", listOf(dcBoot), required = true)
    private val ps2 = RommFirmwareMatcher.Wanted("PlayStation 2", "scph*.bin", emptyList(), minSize = 4L * 1024 * 1024, required = true)

    @Test fun `firmware listings are read, hashes lower-cased, lost files skipped`() {
        val list = RommFirmwareMatcher.parseList(json(psxListing), platformId = 19)
        assertEquals(listOf(3, 4), list.map { it.id })
        assertEquals(scph5501, list[0].md5)
        assertEquals("8d8cb7e4", list[0].crc)
        assertEquals(524288L, list[1].sizeBytes)
        assertNull(list[1].md5)
        assertEquals(19, list[0].platformId)
        assertEquals(2, RommFirmwareMatcher.parseList(json("""{"items": $psxListing}""")).size)
        assertTrue(RommFirmwareMatcher.parseList(json("""{"detail": "Forbidden"}""")).isEmpty())
        assertTrue(RommFirmwareMatcher.parseList(null).isEmpty())
    }

    @Test fun `a known dump is matched by MD5 on any platform`() {
        val result = RommFirmwareMatcher.match(listOf(ps1), listOf(fw(1, "SCPH-5501 (USA).BIN", scph5501, platform = 77)), emptyMap())
        val m = result.matches.single()
        assertTrue(m.byHash)
        assertEquals("scph5501.bin", m.targetPath)
        assertTrue(result.notOnServer.isEmpty())
    }

    @Test fun `a hash match on the system's own platform wins`() {
        val firmware = listOf(fw(1, "x.bin", scph5501, platform = 77), fw(2, "scph5501.bin", scph5501, platform = 19))
        val m = RommFirmwareMatcher.match(listOf(ps1), firmware, mapOf("PlayStation" to setOf(19))).matches.single()
        assertEquals(2, m.firmware.id)
    }

    @Test fun `the same name with another hash is another dump and is not taken`() {
        val firmware = listOf(fw(1, "dc_boot.bin", "ffffffffffffffffffffffffffffffff", platform = 5))
        val result = RommFirmwareMatcher.match(listOf(dc), firmware, mapOf("Dreamcast" to setOf(5)))
        assertTrue(result.matches.isEmpty())
        assertEquals(listOf(dc), result.notOnServer)
    }

    @Test fun `the same name without a server hash is taken on the system's platform and kept in its folder`() {
        val firmware = listOf(fw(1, "DC_BOOT.BIN", null, platform = 5), fw(2, "dc_boot.bin", null, platform = 6))
        val m = RommFirmwareMatcher.match(listOf(dc), firmware, mapOf("Dreamcast" to setOf(5))).matches.single()
        assertEquals(1, m.firmware.id)
        assertFalse(m.byHash)
        assertEquals("dc/dc_boot.bin", m.targetPath)
        assertEquals("dc", m.targetFolder)
        assertEquals("dc_boot.bin", m.targetName)
    }

    @Test fun `a name match never crosses to another system's platform`() {
        val firmware = listOf(fw(1, "dc_boot.bin", null, platform = 6))
        assertTrue(RommFirmwareMatcher.match(listOf(dc), firmware, mapOf("Dreamcast" to setOf(5))).matches.isEmpty())
    }

    @Test fun `a pattern takes a big enough file of the system and keeps its name`() {
        val firmware = listOf(
            fw(1, "scph5501.bin", scph5501, platform = 8, size = 524_288),
            fw(2, "SCPH-70012.bin", null, platform = 8, size = 4_194_304),
            fw(3, "scph39001.bin", null, platform = 9, size = 4_194_304)
        )
        val m = RommFirmwareMatcher.match(listOf(ps2), firmware, mapOf("PlayStation 2" to setOf(8))).matches.single()
        assertEquals(2, m.firmware.id)
        assertEquals("SCPH-70012.bin", m.targetPath)
    }

    @Test fun `only files with a known dump are matched from the listing of every platform`() {
        val all = listOf(fw(1, "whatever.bin", dcBoot, platform = null), fw(2, "scph70012.bin", null, platform = null, size = 4_194_304))
        val result = RommFirmwareMatcher.matchByHashOnly(listOf(dc, ps2), all)
        assertEquals(listOf(dc), result.matches.map { it.wanted })
        assertEquals(listOf(ps2), result.notOnServer)
    }

    @Test fun `downloads are verified against the good dumps, the server hash or the size`() {
        val byHash = RommFirmwareMatcher.Match(ps1, fw(1, "scph5501.bin", scph5501), "scph5501.bin", true)
        assertEquals(RommFirmwareMatcher.Verdict.OK, RommFirmwareMatcher.verify(byHash, scph5501.uppercase(), 524_288))
        assertEquals(RommFirmwareMatcher.Verdict.WRONG_DUMP, RommFirmwareMatcher.verify(byHash, "0".repeat(32), 524_288))
        assertEquals(RommFirmwareMatcher.Verdict.EMPTY, RommFirmwareMatcher.verify(byHash, scph5501, 0))

        val pattern = RommFirmwareMatcher.Match(ps2, fw(2, "scph70012.bin", "a".repeat(32), size = 4_194_304), "scph70012.bin", false)
        assertEquals(RommFirmwareMatcher.Verdict.OK, RommFirmwareMatcher.verify(pattern, "a".repeat(32), 4_194_304))
        assertEquals(RommFirmwareMatcher.Verdict.CORRUPT, RommFirmwareMatcher.verify(pattern, "b".repeat(32), 4_194_304))
        assertEquals(RommFirmwareMatcher.Verdict.TOO_SMALL, RommFirmwareMatcher.verify(pattern, "a".repeat(32), 1024))

        val sizeOnly = RommFirmwareMatcher.Match(ps2, fw(3, "scph1.bin", null, size = 4_194_304), "scph1.bin", false)
        assertEquals(RommFirmwareMatcher.Verdict.OK, RommFirmwareMatcher.verify(sizeOnly, "c".repeat(32), 4_194_304))
        assertEquals(RommFirmwareMatcher.Verdict.CORRUPT, RommFirmwareMatcher.verify(sizeOnly, "c".repeat(32), 4_194_305))
    }

    @Test fun `what the BIOS check misses becomes the wanted list`() {
        val ps = BiosCatalog.systems.first { it.name == "PlayStation" }
        val result = BiosCatalog.check(ps, mapOf("scph5502.bin" to "scph5502.bin")) { "32736f17079d0b2b7024407c39bd3050" }
        val wanted = RommFirmwareMatcher.missing(listOf(result))
        assertEquals(listOf("scph5500.bin", "scph5501.bin", "scph1001.bin"), wanted.map { it.path })
        assertEquals(listOf(scph5501), wanted.first { it.path == "scph5501.bin" }.md5)
    }

    @Test fun `platforms belong to a system through the console map or their own slug`() {
        val systems = BiosCatalog.systems.filter { it.name in setOf("PlayStation", "PlayStation 2", "Dreamcast") }
        val map = RommFirmwareMatcher.platformsBySystem(
            systems,
            consoleMap = mapOf("sony_playstation" to 19, "sony_playstation_2" to 20, "nintendo_gba" to 3),
            platforms = listOf(
                RommFirmwareMatcher.PlatformRef(21, "dc", "dc", "Dreamcast"),
                RommFirmwareMatcher.PlatformRef(22, "psx", "ps1", "PlayStation"),
                RommFirmwareMatcher.PlatformRef(23, "snes", "snes", "Super Nintendo")
            )
        )
        assertEquals(setOf(19, 22), map["PlayStation"])
        assertEquals(setOf(20), map["PlayStation 2"])
        assertEquals(setOf(21), map["Dreamcast"])
        assertEquals(3, map.size)
    }

    @Test fun `the content route encodes the file name`() {
        assertEquals("/api/firmware/7/content/7800%20BIOS%20%28U%29.rom", RommFirmwareMatcher.contentPath(fw(7, "7800 BIOS (U).rom")))
    }
}
