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

    private fun console(
        id: String, place: Place = Place.INTERNAL, bytes: Long = gb, playedDaysAgo: Int? = null, favourite: Boolean = false,
        busy: Boolean = false, movedDaysAgo: Int? = null, changedDaysAgo: Int? = 400, largest: Long = bytes / 10
    ) = Console(id, id, place, bytes, 10, playedDaysAgo?.let { now - it * day }, favourite, busy, movedDaysAgo?.let { now - it * day }, changedDaysAgo?.let { now - it * day }, largest)

    @Test fun `a cold console goes to the SD card and a played one stays`() {
        val plan = SmartStorage.plan(listOf(console("gba", playedDaysAgo = 200), console("snes", playedDaysAgo = 3), console("nes", changedDaysAgo = 500)), now, 30, 50 * gb, 50 * gb)
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

    @Test fun `nothing known about a console means it is not cold`() {
        val plan = SmartStorage.plan(listOf(console("gba", changedDaysAgo = null)), now, 30, 50 * gb, 50 * gb)
        assertTrue(plan.isEmpty)
    }

    @Test fun `a recent download keeps a never played console inside`() {
        val plan = SmartStorage.plan(listOf(console("gba", changedDaysAgo = 3), console("nes", playedDaysAgo = 300, changedDaysAgo = 5)), now, 30, 50 * gb, 50 * gb)
        assertTrue(plan.isEmpty)
    }

    @Test fun `an automatic run leaves consoles with a big file to a run by hand`() {
        val c = console("psx", bytes = 6 * gb, largest = 3 * gb)
        val auto = SmartStorage.plan(listOf(c), now, 30, gb, 100 * gb, budgetBytes = 8 * gb, maxFileBytes = SmartStorage.AUTO_MAX_FILE_BYTES)
        assertEquals(Wait.BIG_FILE, auto.waiting.single().wait)
        assertEquals(1, SmartStorage.plan(listOf(c), now, 30, gb, 100 * gb).moves.size)
    }

    @Test fun `a confirmed plan is run as confirmed, never more`() {
        val fresh = SmartStorage.plan(listOf(console("a"), console("b"), console("c", Place.SD, playedDaysAgo = 1)), now, 30, 50 * gb, 50 * gb)
        assertEquals(3, fresh.moves.size)
        val run = SmartStorage.restrictTo(fresh, mapOf("a" to Place.SD, "c" to Place.SD, "gone" to Place.SD))
        assertEquals(listOf("a"), run.moves.map { it.console.id })
    }

    @Test fun `an original goes only when unchanged since the copy`() {
        val copied = SmartStorage.Stamp(100, 5000)
        assertTrue(SmartStorage.unchanged(copied, SmartStorage.Stamp(100, 5000)))
        assertFalse(SmartStorage.unchanged(copied, SmartStorage.Stamp(100, 6000)))
        assertFalse(SmartStorage.unchanged(copied, SmartStorage.Stamp(101, 5000)))
        assertFalse(SmartStorage.unchanged(copied, null))
    }

    @Test fun `only copies this feature wrote are trusted`() {
        val original = SmartStorage.Stamp(100, 5000)
        val text = SmartStorage.Manifest.encode("hacks/a b.gba", original) + "\n" + "junk\n" + SmartStorage.Manifest.encode("c.gba", SmartStorage.Stamp(7, 8)) + "\n"
        val m = SmartStorage.Manifest.decode(text)
        assertEquals(original, m["hacks/a b.gba"])
        assertEquals(2, m.size)
        assertTrue(SmartStorage.Manifest.trusted(m["hacks/a b.gba"], original, 100))
        // Same name and size but not written by us, or the original changed since: copy again.
        assertFalse(SmartStorage.Manifest.trusted(null, original, 100))
        assertFalse(SmartStorage.Manifest.trusted(m["hacks/a b.gba"], SmartStorage.Stamp(100, 9000), 100))
        assertFalse(SmartStorage.Manifest.trusted(m["hacks/a b.gba"], original, 99))
    }

    @Test fun `the tree a folder was granted through`() {
        val tree = "content://com.android.externalstorage.documents/tree/1234-ABCD%3AGames"
        assertEquals(tree, SmartStorage.treeOf("$tree/document/1234-ABCD%3AGames%2Fgba"))
        assertEquals(tree, SmartStorage.treeOf(tree))
        assertNull(SmartStorage.treeOf("content://x/document/abc"))
    }

    @Test fun `older records still read`() {
        val old = listOf("gba", "SD", "5", "gba", "content://u").joinToString("\u001F")
        assertEquals(SmartStorage.Record("gba", Place.SD, 5, "gba", "content://u"), SmartStorage.Record.decode(old))
        val r = SmartStorage.Record("gba", Place.SD, 5, "gba", "u", "/storage/X/gba", "%ROMPATH%/gba", true)
        assertEquals(r, SmartStorage.Record.decode(r.encode()))
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

    @Test fun `ES-DE ROM directory and paths`() {
        val xml = """<?xml version="1.0"?>
            <string name="MediaDirectory" value="" />
            <string name="ROMDirectory" value="/storage/emulated/0/ROMs" />"""
        assertEquals("/storage/emulated/0/ROMs", SmartStorage.esdeRomDirectory(xml))
        assertNull(SmartStorage.esdeRomDirectory("""<string name="ROMDirectory" value="" />"""))
        assertNull(SmartStorage.esdeRomDirectory("""<string name="ROMDirectory" value="%ESPATH%/ROMs" />"""))
        assertTrue(SmartStorage.samePath("/storage/emulated/0/ROMs/", "/storage/emulated/0/roms"))
        assertFalse(SmartStorage.samePath(null, "/x"))
    }

    @Test fun `ES-DE gets the new path of a system copied from its own definitions, and loses it again`() {
        val patch = SmartStorage.esdePatch(null, bundled, "gba", "/storage/1234-ABCD/Games & Co/gba")!!
        assertTrue(patch.inserted)
        assertEquals("%ROMPATH%/gba", patch.previous)
        assertTrue(patch.content.contains("<path>/storage/1234-ABCD/Games &amp; Co/gba</path>"))
        assertTrue(patch.content.contains(".gba .zip"))
        // Coming back: the block it added goes again.
        val back = SmartStorage.esdeRestore(patch.content, "gba", "/storage/1234-ABCD/Games & Co/gba", patch.previous, patch.inserted)!!
        assertNull(EsdeXml.systemBlock(back, "gba"))
        assertTrue(back.contains("<systemList>"))
    }

    @Test fun `a custom ES-DE block gets its old path back and the user's own path is never touched`() {
        val custom = "<systemList>\n    <system>\n        <name>gba</name>\n        <path>%ROMPATH%/gba</path>\n        <extension>.gba .dgmtx</extension>\n    </system>\n</systemList>\n"
        val patch = SmartStorage.esdePatch(custom, bundled, "gba", "/storage/X/gba")!!
        assertFalse(patch.inserted)
        assertTrue(patch.content.contains(".gba .dgmtx"))
        val back = SmartStorage.esdeRestore(patch.content, "gba", "/storage/X/gba", patch.previous, false)!!
        assertEquals(custom, back)
        // A path the user set: left alone, both ways.
        val users = custom.replace("%ROMPATH%/gba", "/storage/Y/mygba")
        assertNull(SmartStorage.esdePatch(users, bundled, "gba", "/storage/X/gba"))
        assertNull(SmartStorage.esdeRestore(users, "gba", "/storage/X/gba", "%ROMPATH%/gba", false))
        // What smart storage wrote itself may be changed again.
        assertNotNull(SmartStorage.esdePatch(patch.content, bundled, "gba", "/storage/Z/gba", ours = "/storage/X/gba"))
    }

    @Test fun `an unknown ES-DE system is left alone`() {
        assertNull(SmartStorage.esdePatch(null, bundled, "psx", "/x"))
        assertNull(SmartStorage.esdePatch(null, null, "gba", "/x"))
    }
}
