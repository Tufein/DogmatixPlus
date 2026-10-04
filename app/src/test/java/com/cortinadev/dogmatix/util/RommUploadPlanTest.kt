package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.RommUploadPlan.Candidate
import org.junit.Assert.assertEquals
import org.junit.Test

class RommUploadPlanTest {
    private val server = RommMarks.keys("gba", listOf("Advance Wars (USA).zip"))
    private val base = "https://romm.example"

    private fun plan(vararg c: Candidate, mapped: Set<String> = setOf("gba", "snes"), onDevice: (Candidate) -> Boolean = { true }) =
        RommUploadPlan.missing(c.toList(), server, mapped, base, onDevice)

    @Test fun `only what the server lacks is sent`() {
        val have = Candidate("Advance Wars (USA).gba", "gba", "https://a/x")
        val lack = Candidate("Metroid Fusion (USA).zip", "gba", "https://a/y")
        assertEquals(listOf("Metroid Fusion (USA).zip"), plan(have, lack))
    }

    @Test fun `unmapped consoles and games that came from the server are left alone`() {
        val unmapped = Candidate("Chrono Trigger.sfc", "ps2", "https://a/z")
        val fromServer = Candidate("Zelda.zip", "gba", "$base/api/roms/1/content/Zelda.zip")
        assertEquals(emptyList<String>(), plan(unmapped, fromServer))
    }

    @Test fun `a game that is no longer on the device is not sent`() {
        val gone = Candidate("Kirby.zip", "gba", "https://a/k")
        assertEquals(emptyList<String>(), plan(gone, onDevice = { false }))
    }

    @Test fun `the same file name is listed once`() {
        val a = Candidate("Kirby.zip", "gba", "https://a/k")
        assertEquals(listOf("Kirby.zip"), plan(a, a))
    }
}
