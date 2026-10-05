package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthRollupTest {
    private fun res(c: HealthCheck, s: HealthStatus) = HealthResult(c, s, HealthDetail.COVERS_OK)

    @Test fun `counts fine checks and ignores those not set up`() {
        val s = HealthRollup.summarize(
            listOf(
                res(HealthCheck.SOURCES, HealthStatus.OK),
                res(HealthCheck.STORAGE, HealthStatus.HINT),
                res(HealthCheck.BIOS, HealthStatus.LOOK),
                res(HealthCheck.COVERS, HealthStatus.NOT_SET_UP)
            ), expected = 4
        )
        assertEquals(2, s.fine)
        assertEquals(3, s.total)
        assertEquals(1, s.notSetUp)
        assertEquals(HealthStatus.LOOK, s.worst)
        assertTrue(s.finished)
    }

    @Test fun `a problem beats a look`() {
        val s = HealthRollup.summarize(listOf(res(HealthCheck.BIOS, HealthStatus.LOOK), res(HealthCheck.ROMM, HealthStatus.PROBLEM)))
        assertEquals(HealthStatus.PROBLEM, s.worst)
        assertEquals(1, s.problems)
        assertFalse(s.finished)
    }

    @Test fun `nothing set up gives an empty ring`() {
        val s = HealthRollup.summarize(listOf(res(HealthCheck.ROMM, HealthStatus.NOT_SET_UP)))
        assertEquals(0, s.total)
        assertEquals(0f, s.fraction, 0f)
        assertEquals(HealthStatus.OK, s.worst)
    }

    @Test fun `focus starts on the first problem then the first look`() {
        val m = mapOf(
            HealthCheck.SOURCES to res(HealthCheck.SOURCES, HealthStatus.OK),
            HealthCheck.BIOS to res(HealthCheck.BIOS, HealthStatus.LOOK),
            HealthCheck.ROMM to res(HealthCheck.ROMM, HealthStatus.PROBLEM),
            HealthCheck.WEBDAV to res(HealthCheck.WEBDAV, HealthStatus.PROBLEM)
        )
        assertEquals(HealthCheck.ROMM, HealthRollup.firstFocus(m))
        assertEquals(HealthCheck.BIOS, HealthRollup.firstFocus(m - HealthCheck.ROMM - HealthCheck.WEBDAV))
        assertEquals(HealthCheck.SOURCES, HealthRollup.firstFocus(mapOf(HealthCheck.SOURCES to res(HealthCheck.SOURCES, HealthStatus.OK))))
        assertNull(HealthRollup.firstFocus(emptyMap()))
    }

    @Test fun `report groups rows and redacts addresses`() {
        val text = HealthReport.text(
            "Dogmatix+ health", "7 of 9 fine", "Version 6.0.0",
            listOf(
                HealthReport.Row("Library", "Sources", "OK", "3 sources"),
                HealthReport.Row("Library", "Covers", "Look at this", "4 without a cover"),
                HealthReport.Row("Cloud", "RomM", "Problem", "No answer from https://romm.example.com/api?token=abc12345")
            )
        )
        assertTrue(text.startsWith("Dogmatix+ health\n7 of 9 fine"))
        assertTrue(text.contains("\nLIBRARY\n[OK] Sources: 3 sources\n[Look at this] Covers"))
        assertTrue(text.contains("\nCLOUD\n[Problem] RomM"))
        assertFalse(text.contains("romm.example.com"))
        assertFalse(text.contains("abc12345"))
    }
}
