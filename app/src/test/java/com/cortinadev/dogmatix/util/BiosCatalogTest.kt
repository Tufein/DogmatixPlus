package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiosCatalogTest {
    @Test fun `bios systems match console ids and report missing or other versions`() {
        val names = BiosCatalog.systemsFor(listOf("sony_playstation", "nintendo_gameboy_advance", "sega_dreamcast")).map { it.name }
        assertEquals(listOf("PlayStation", "Dreamcast", "Game Boy Advance"), names)
        assertFalse(BiosCatalog.systemsFor(listOf("nintendo_gameboy_advance")).any { it.name.startsWith("Game Boy /") })
        assertTrue(BiosCatalog.systemsFor(listOf("sony_playstation_2")).any { it.name == "PlayStation 2" })

        val ps1 = BiosCatalog.systems.first { it.name == "PlayStation" }
        val result = BiosCatalog.check(ps1, mapOf("scph5501.bin" to "SCPH5501.BIN", "scph5502.bin" to "scph5502.bin")) {
            if (it == "scph5501.bin") "490f666e1afb15b7362b406ed1cea246" else "deadbeef"
        }
        assertTrue(result.ready)
        assertFalse(result.allGood)
        assertEquals(BiosCatalog.State.OK, result.files[1].state)
        assertEquals(BiosCatalog.State.OTHER_VERSION, result.files[2].state)
        assertEquals(BiosCatalog.State.MISSING, result.files[0].state)

        val dc = BiosCatalog.systems.first { it.name == "Dreamcast" }
        assertFalse(BiosCatalog.check(dc, mapOf("dc/dc_flash.bin" to "dc_flash.bin")) { null }.ready)
        val ps2 = BiosCatalog.systems.first { it.name == "PlayStation 2" }
        assertTrue(BiosCatalog.check(ps2, mapOf("scph-70012.bin" to "SCPH-70012.bin")) { null }.ready)
        // A 512 KB PS1 BIOS in the same folder is not a PS2 BIOS.
        val sizes = mapOf("scph5501.bin" to 524_288L, "scph-70012.bin" to 4_194_304L)
        assertFalse(BiosCatalog.check(ps2, mapOf("scph5501.bin" to "SCPH5501.BIN"), { sizes[it] }) { null }.ready)
        assertTrue(BiosCatalog.check(ps2, mapOf("scph5501.bin" to "SCPH5501.BIN", "scph-70012.bin" to "SCPH-70012.bin"), { sizes[it] }) { null }.ready)
    }
}
