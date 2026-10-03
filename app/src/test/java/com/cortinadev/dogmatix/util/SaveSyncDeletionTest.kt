package com.cortinadev.dogmatix.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSyncDeletionTest {

    private val roms = listOf(
        SaveSyncPlanner.RomCandidate(1, "Pokemon Emerald (USA).gba", "gba", "gba"),
        SaveSyncPlanner.RomCandidate(2, "Super Mario World (USA).sfc", "snes", "snes"),
        SaveSyncPlanner.RomCandidate(5, "Kirby (USA).gba", "gba", "gba"),
        SaveSyncPlanner.RomCandidate(6, "Metroid (USA).gba", "gba", "gba"),
        SaveSyncPlanner.RomCandidate(7, "Zelda (USA).gba", "gba", "gba")
    )
    private val clock = TestClock()
    private val server = FakeSaveServer(clock, roms)

    private inner class Device(deletions: Boolean) {
        val store = FakeSaveStore(clock)
        val engine = SaveSyncEngine(server, store, clock = { clock.now }, syncDeletions = { deletions })
        val records = mutableMapOf<String, SaveSyncRecord>()
        fun sync(confirm: Boolean = false) = runBlocking { engine.sync(records, confirm) }
    }

    private val emerald = "Pokemon Emerald (USA).srm"

    @Test fun `without the setting a deleted save is simply copied back`() {
        val a = Device(deletions = false)
        a.store.put(SaveKind.SAVE, emerald, "v1")
        a.sync()
        a.store.remove(SaveKind.SAVE, emerald)
        val result = a.sync().first
        assertEquals(1, result.downloaded)
        assertEquals("v1", a.store.text(SaveKind.SAVE, emerald))
    }

    @Test fun `a save deleted on the device is deleted on the server`() {
        val a = Device(deletions = true)
        a.store.put(SaveKind.SAVE, emerald, "v1")
        a.sync()
        a.store.remove(SaveKind.SAVE, emerald)
        val result = a.sync().first
        assertEquals(1, result.deletedOnServer)
        assertEquals(0, result.downloaded)
        assertNull(server.text(SaveKind.SAVE, emerald))
        // And it stays gone.
        assertEquals(0, a.sync().first.downloaded + a.sync().first.uploaded)
    }

    @Test fun `a save deleted on the server is deleted here, with a backup`() {
        val a = Device(deletions = true)
        a.store.put(SaveKind.SAVE, emerald, "v1")
        a.sync()
        server.remove(SaveKind.SAVE, emerald)
        val result = a.sync().first
        assertEquals(1, result.deletedOnDevice)
        assertEquals(0, result.uploaded)
        assertNull(a.store.text(SaveKind.SAVE, emerald))
        assertEquals(listOf(emerald), a.store.backups)
    }

    @Test fun `a change on the other side beats a deletion`() {
        val a = Device(deletions = true)
        a.store.put(SaveKind.SAVE, emerald, "v1")
        a.sync()
        // Deleted here, but changed on the server meanwhile: the server version comes back.
        a.store.remove(SaveKind.SAVE, emerald)
        server.put(SaveKind.SAVE, 1, emerald, "played on another device")
        val result = a.sync().first
        assertEquals(0, result.deletedOnServer)
        assertEquals(1, result.downloaded)
        assertEquals("played on another device", a.store.text(SaveKind.SAVE, emerald))

        // Deleted on the server, but changed here meanwhile: the device version goes back up.
        server.remove(SaveKind.SAVE, emerald)
        a.store.put(SaveKind.SAVE, emerald, "kept playing")
        val back = a.sync().first
        assertEquals(0, back.deletedOnDevice)
        assertEquals(1, back.uploaded)
        assertEquals("kept playing", server.text(SaveKind.SAVE, emerald))
    }

    @Test fun `a kind without a picked folder is never treated as deleted`() {
        val both = Device(deletions = true)
        both.store.put(SaveKind.STATE, "Super Mario World (USA).state", "s1")
        both.store.put(SaveKind.SAVE, emerald, "v1")
        both.sync()
        // The same records, but now only the saves folder is picked: the states are not "gone".
        val savesOnly = SaveSyncEngine(server, FakeSaveStore(clock, kinds = setOf(SaveKind.SAVE)).also { it.put(SaveKind.SAVE, emerald, "v1") }, clock = { clock.now }, syncDeletions = { true })
        val result = runBlocking { savesOnly.sync(both.records) }.first
        assertEquals(0, result.deletedOnServer + result.deletedOnDevice)
        assertEquals("s1", server.text(SaveKind.STATE, "Super Mario World (USA).state"))
    }

    @Test fun `a mass deletion is held back until confirmed`() {
        val a = Device(deletions = true)
        val names = listOf("Pokemon Emerald (USA)", "Kirby (USA)", "Metroid (USA)", "Zelda (USA)")
        names.forEach { a.store.put(SaveKind.SAVE, "$it.srm", "x") }
        a.store.put(SaveKind.STATE, "Super Mario World (USA).state", "y")
        a.sync()
        // The user (or a glitch) emptied the saves folder: 4 of 5 files.
        names.forEach { a.store.remove(SaveKind.SAVE, "$it.srm") }
        val held = a.sync().first
        assertEquals(4, held.deletionsHeld)
        assertEquals(0, held.deletedOnServer)
        assertEquals(4, server.stored.count { it.kind == SaveKind.SAVE })
        assertEquals(0, held.downloaded)

        val confirmed = a.sync(confirm = true).first
        assertEquals(4, confirmed.deletedOnServer)
        assertEquals(0, confirmed.deletionsHeld)
        assertTrue(server.stored.none { it.kind == SaveKind.SAVE })
    }

    @Test fun `a few deletions go through without asking`() {
        val a = Device(deletions = true)
        listOf("Pokemon Emerald (USA)", "Kirby (USA)", "Metroid (USA)").forEach { a.store.put(SaveKind.SAVE, "$it.srm", "x") }
        a.store.put(SaveKind.STATE, "Super Mario World (USA).state", "y")
        a.sync()
        a.store.remove(SaveKind.SAVE, "Kirby (USA).srm")
        a.store.remove(SaveKind.SAVE, "Metroid (USA).srm")
        val result = a.sync().first
        assertEquals(2, result.deletedOnServer)
        assertEquals(0, result.deletionsHeld)
    }
}
