package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.SmartStorage.Console
import com.cortinadev.dogmatix.util.SmartStorage.Place
import com.cortinadev.dogmatix.util.SmartStorage.Reason
import com.cortinadev.dogmatix.util.SmartStorage.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartStorageTest {
    private val day = SmartStorage.DAY_MS
    private val gb = 1024L * 1024 * 1024
    private val now = 1_000L * day

    private fun console(id: String, place: Place = Place.INTERNAL, bytes: Long = gb, playedDaysAgo: Int? = null, favourite: Boolean = false, busy: Boolean = false, movedDaysAgo: Int? = null) =
        Console(id, id, place, bytes, 10, playedDaysAgo?.let { now - it * day }, favourite, busy, movedDaysAgo?.let { now - it * day })

    @Test fun `a cold console goes to the SD card and a played one stays`() {
        val plan = SmartStorage.plan(listOf(console("gba", playedDaysAgo = 200), console("snes", playedDaysAgo = 3), console("nes")), now, 30, 50 * gb, 50 * gb)
        assertEquals(listOf("nes", "gba"), plan.moves.map { it.console.id })
        assertTrue(plan.moves.all { it.to == Place.SD && it.reason == Reason.COLD })
        assertEquals(2 * gb, plan.freesInternal)
    }

    @Test fun `favourites never leave internal storage`() {
        val plan = SmartStorage.plan(listOf(console("gba", favourite = true)), now, 30, 50 * gb, 50 * gb)
        assertTrue(plan.isEmpty)
    }

    @Test fun `between hot and cold nothing moves, so consoles do not bounce`() {
        // Played 35 days ago with recent = 30: not hot any more, not cold yet (gap of 14 days).
        val inside = SmartStorage.plan(listOf(console("gba", playedDaysAgo = 35)), now, 30, 50 * gb, 50 * gb)
        assertTrue(inside.isEmpty)
        val onSd = SmartStorage.plan(listOf(console("gba", Place.SD, playedDaysAgo = 35)), now, 30, 50 * gb, 50 * gb)
        assertTrue(onSd.isEmpty)
        val cold = SmartStorage.plan(listOf(console("gba", playedDaysAgo = 45)), now, 30, 50 * gb, 50 * gb)
        assertEquals(1, cold.moves.size)
    }

    @Test fun `a played console on the SD card comes home, a favourite too`() {
        val plan = SmartStorage.plan(listOf(console("gba", Place.SD, playedDaysAgo = 2), console("psx", Place.SD, favourite = true)), now, 30, 50 * gb, 50 * gb)
        assertEquals(listOf("gba" to Reason.PLAYED, "psx" to Reason.FAVOURITE), plan.moves.map { it.console.id to it.reason })
        assertTrue(plan.moves.all { it.to == Place.INTERNAL })
        assertEquals(-2 * gb, plan.freesInternal)
    }

    @Test fun `a recently moved console rests`() {
        val plan = SmartStorage.plan(listOf(console("gba", Place.SD, playedDaysAgo = 1, movedDaysAgo = 3)), now, 30, 50 * gb, 50 * gb)
        assertTrue(plan.moves.isEmpty())
        assertEquals(Wait.RESTING, plan.waiting.single().wait)
        val later = SmartStorage.plan(listOf(console("gba", Place.SD, playedDaysAgo = 1, movedDaysAgo = 20)), now, 30, 50 * gb, 50 * gb)
        assertEquals(1, later.moves.size)
    }

    @Test fun `a console with a download or a frontend run waits`() {
        val plan = SmartStorage.plan(listOf(console("gba", busy = true)), now, 30, 50 * gb, 50 * gb)
        assertEquals(Wait.BUSY, plan.waiting.single().wait)
    }

    @Test fun `space is checked on both sides as the plan goes on`() {
        // SD card: room for one console of 3 GB with the reserves, not for two.
        val sdFree = 4 * gb + LibraryMove.RESERVE_BYTES
        val plan = SmartStorage.plan(listOf(console("a", bytes = 3 * gb), console("b", bytes = 3 * gb)), now, 30, gb, sdFree)
        assertEquals(listOf("a"), plan.moves.map { it.console.id })
        assertEquals(Wait.NO_ROOM, plan.waiting.single().wait)
        // Coming home needs room inside; moving a cold console out first makes that room.
        val swap = SmartStorage.plan(
            listOf(console("cold", bytes = 5 * gb, playedDaysAgo = 300), console("hot", Place.SD, bytes = 5 * gb, playedDaysAgo = 1)),
            now, 30, internalFree = 2 * gb, sdFree = 20 * gb
        )
        assertEquals(listOf("cold", "hot"), swap.moves.map { it.console.id })
        val noRoom = SmartStorage.plan(listOf(console("hot", Place.SD, bytes = 5 * gb, playedDaysAgo = 1)), now, 30, internalFree = 2 * gb, sdFree = 20 * gb)
        assertEquals(Wait.NO_ROOM, noRoom.waiting.single().wait)
    }

    @Test fun `unknown free space refuses nothing`() {
        assertTrue(SmartStorage.fits(100 * gb, null))
        assertFalse(SmartStorage.fits(100 * gb, 100 * gb))
    }

    @Test fun `an automatic run keeps to its budget but always moves one`() {
        val big = SmartStorage.plan(listOf(console("a", bytes = 20 * gb)), now, 30, gb, 100 * gb, budgetBytes = 8 * gb)
        assertEquals(1, big.moves.size)
        val two = SmartStorage.plan(listOf(console("a", bytes = 5 * gb), console("b", bytes = 5 * gb)), now, 30, gb, 100 * gb, budgetBytes = 8 * gb)
        assertEquals(1, two.moves.size)
        assertEquals(Wait.LATER, two.waiting.single().wait)
    }

    @Test fun `empty folders are left alone`() {
        val plan = SmartStorage.plan(listOf(console("a").copy(files = 0)), now, 30, gb, 100 * gb)
        assertTrue(plan.isEmpty)
    }

    @Test fun `the recent days stay in range`() {
        assertEquals(7, SmartStorage.clampDays(1))
        assertEquals(180, SmartStorage.clampDays(999))
        assertEquals(30, SmartStorage.clampDays(30))
    }

    @Test fun `a copy is verified only with every file at its size`() {
        val expected = mapOf("a.gba" to 10L, "hacks/b.gba" to 20L)
        assertTrue(SmartStorage.verified(expected, expected + ("extra.txt" to 1L)))
        assertFalse(SmartStorage.verified(expected, mapOf("a.gba" to 10L)))
        assertFalse(SmartStorage.verified(expected, mapOf("a.gba" to 10L, "hacks/b.gba" to 19L)))
        assertFalse(SmartStorage.verified(emptyMap(), emptyMap()))
    }

    @Test fun `favourites count only when the game is on the device`() {
        assertTrue(SmartStorage.hasFavouriteOnDisk(listOf("Metroid Fusion (USA).zip"), listOf("metroid fusion (usa).gba")))
        assertFalse(SmartStorage.hasFavouriteOnDisk(listOf("Metroid Fusion (USA).zip"), listOf("Zelda.gba")))
        assertFalse(SmartStorage.hasFavouriteOnDisk(emptyList(), listOf("Zelda.gba")))
    }

    @Test fun `the last play of a console`() {
        val plays = listOf("gba" to 5L, "gba" to 9L, "snes" to 20L, "gba" to null)
        assertEquals(9L, SmartStorage.lastPlayed(plays) { it == "gba" })
        assertNull(SmartStorage.lastPlayed(plays) { it == "nes" })
    }

    @Test fun `records survive a round trip and junk is dropped`() {
        val r = SmartStorage.Record("gba", Place.SD, 123L, "gba", "content://x/tree/1234%3AGames/document/1234%3AGames%2Fgba")
        assertEquals(r, SmartStorage.Record.decode(r.encode()))
        assertNull(SmartStorage.Record.decode("gba|SD"))
        val run = SmartStorage.RunInfo(5L, 2, 1, 99L)
        assertEquals(run, SmartStorage.RunInfo.decode(run.encode()))
        assertNull(SmartStorage.RunInfo.decode("x"))
    }

    @Test fun `volumes of document ids`() {
        assertEquals("primary", SmartStorage.volumeOf("primary:ROMs"))
        assertEquals("1234-abcd", SmartStorage.volumeOf("1234-ABCD:Games"))
    }

    private val bundled = """
        <systemList>
            <system>
                <name>gba</name>
                <path>%ROMPATH%/gba</path>
                <extension>.gba .zip</extension>
            </system>
        </systemList>
    """.trimIndent()

    @Test fun `ES-DE gets the new path of a system, copied from its own definitions`() {
        val out = SmartStorage.esdeSystemsWithPath(null, bundled, "gba", "/storage/1234-ABCD/Games/gba")
        assertNotNull(out)
        assertTrue(out!!.contains("<path>/storage/1234-ABCD/Games/gba</path>"))
        assertTrue(out.contains(".gba .zip"))
        // Coming back: the custom block is set back to the ROM folder.
        val back = SmartStorage.esdeSystemsWithPath(out, null, "gba", SmartStorage.esdeRomPath("gba"))
        assertTrue(back!!.contains("<path>%ROMPATH%/gba</path>"))
        assertNull(SmartStorage.esdeSystemsWithPath(back, null, "gba", SmartStorage.esdeRomPath("gba")))
    }

    @Test fun `an unknown ES-DE system is left alone`() {
        assertNull(SmartStorage.esdeSystemsWithPath(null, bundled, "psx", "/x"))
        assertNull(SmartStorage.esdeSystemsWithPath(null, null, "gba", "/x"))
    }
}
