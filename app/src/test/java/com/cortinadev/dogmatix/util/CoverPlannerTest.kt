package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPlannerTest {
    @Test fun `the system is the console folder in the path`() {
        assertEquals("gba", CoverPlanner.systemOf(diskFile("a.gba", folder = "/storage/ROMs/gba/USA", consoleId = "gba")))
        assertNull(CoverPlanner.systemOf(diskFile("a.bin", consoleId = null)))
        assertNull(CoverPlanner.systemOf(diskFile("a.gba", folder = "/storage/Stuff", consoleId = "gba")))
    }

    @Test fun `only missing covers of games on the device are planned`() {
        val device = mapOf("gba" to listOf("Zelda (USA)", "Metroid (USA)", "Unknown Game"))
        val server = mapOf("gba" to listOf(
            CoverSource("zelda (usa)", "roms/1/1/cover/big.png"),
            CoverSource("Metroid (USA)", "roms/1/2/cover/big.jpg?ts=1"),
            CoverSource("Not On Device", "roms/1/3/cover/big.png")
        ))
        val have = mapOf("gba" to setOf("metroid (usa)"))
        val jobs = CoverPlanner.plan(device, server, have)
        assertEquals(listOf("Zelda (USA).png"), jobs.map { it.fileName })
        assertEquals("roms/1/1/cover/big.png", jobs.single().coverPath)
    }

    @Test fun `extension comes from the cover path`() {
        assertEquals("jpg", CoverJob("gba", "G", "roms/2/cover/small.jpg?ts=123").extension)
        assertEquals("png", CoverJob("gba", "G", "roms/2/cover/small").extension)
    }

    @Test fun `games without a cover on the server and other systems are skipped`() {
        val jobs = CoverPlanner.plan(mapOf("snes" to listOf("A"), "gba" to listOf("B")), mapOf("gba" to listOf(CoverSource("b", ""))), emptyMap())
        assertTrue(jobs.isEmpty())
    }

    @Test fun `builds the resource url`() {
        assertEquals("https://romm.lan/assets/romm/resources/roms/1/2/cover/big.png", CoverPlanner.coverUrl("https://romm.lan/", "/roms/1/2/cover/big.png"))
    }
}
