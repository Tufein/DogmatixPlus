package com.cortinadev.dogmatix.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSyncEngineTest {

    private val roms = listOf(
        SaveSyncPlanner.RomCandidate(1, "Pokemon Emerald (USA).gba", "gba", "gba"),
        SaveSyncPlanner.RomCandidate(2, "Super Mario World (USA).sfc", "snes", "snes"),
        SaveSyncPlanner.RomCandidate(3, "Dr. Mario (World).gb", "gb", "gb"),
        SaveSyncPlanner.RomCandidate(4, "Dr. Mario (World).nes", "nes", "nes")
    )
    private val clock = TestClock()
    private val server = FakeSaveServer(clock, roms)

    /** One handheld: its folders, its engine and its records. */
    private inner class Device {
        val store = FakeSaveStore(clock)
        val engine = SaveSyncEngine(server, store, clock = { clock.now })
        val records = mutableMapOf<String, SaveSyncRecord>()
        fun sync() = runBlocking { engine.sync(records) }
    }

    @Test
    fun `progress travels between two devices`() {
        val a = Device()
        val b = Device()
        a.store.put(SaveKind.SAVE, "mGBA/Pokemon Emerald (USA).srm", "badge 1")
        a.store.put(SaveKind.STATE, "Super Mario World (USA).state.auto", "world 2")

        val (first, _) = a.sync()
        assertEquals(2, first.uploaded)
        assertEquals("badge 1", server.text(SaveKind.SAVE, "Pokemon Emerald (USA).srm"))
        assertEquals("mGBA", server.stored.first { it.kind == SaveKind.SAVE }.emulator)

        // B has an mGBA folder too, so the save lands where its emulator looks for it.
        b.store.folders[SaveKind.SAVE] = mutableSetOf("mGBA")
        val (onB, _) = b.sync()
        assertEquals(2, onB.downloaded)
        assertEquals("badge 1", b.store.text(SaveKind.SAVE, "mGBA/Pokemon Emerald (USA).srm"))
        assertEquals("world 2", b.store.text(SaveKind.STATE, "Super Mario World (USA).state.auto"))

        // Nothing changed: nothing moves (the upload answer's precise time is not a change).
        assertEquals(2, a.sync().first.unchanged)
        assertEquals(2, b.sync().first.unchanged)

        // B plays on; A picks it up.
        b.store.put(SaveKind.SAVE, "mGBA/Pokemon Emerald (USA).srm", "badge 2")
        assertEquals(1, b.sync().first.uploaded)
        val (back, _) = a.sync()
        assertEquals(1, back.downloaded)
        assertEquals("badge 2", a.store.text(SaveKind.SAVE, "mGBA/Pokemon Emerald (USA).srm"))
        assertEquals(listOf("mGBA/Pokemon Emerald (USA).srm"), a.store.backups)
    }

    @Test
    fun `both sides changed - nothing is overwritten until the user picks`() {
        val a = Device()
        a.store.put(SaveKind.SAVE, "Pokemon Emerald (USA).srm", "v1")
        a.sync()
        a.store.put(SaveKind.SAVE, "Pokemon Emerald (USA).srm", "device v2")
        server.put(SaveKind.SAVE, 1, "Pokemon Emerald (USA).srm", "web player v2")

        val (result, conflicts) = a.sync()
        assertEquals(1, result.conflicts)
        assertEquals("device v2", a.store.text(SaveKind.SAVE, "Pokemon Emerald (USA).srm"))
        assertEquals("web player v2", server.text(SaveKind.SAVE, "Pokemon Emerald (USA).srm"))

        runBlocking { a.engine.resolve(conflicts.single(), keepDevice = false, records = a.records) }
        assertEquals("web player v2", a.store.text(SaveKind.SAVE, "Pokemon Emerald (USA).srm"))
        assertEquals(1, a.sync().first.unchanged)
    }

    @Test
    fun `keeping the device copy sends it up`() {
        val a = Device()
        a.store.put(SaveKind.SAVE, "Pokemon Emerald (USA).srm", "v1")
        a.sync()
        a.store.put(SaveKind.SAVE, "Pokemon Emerald (USA).srm", "device v2")
        server.put(SaveKind.SAVE, 1, "Pokemon Emerald (USA).srm", "other v2")
        val conflict = a.sync().second.single()
        runBlocking { a.engine.resolve(conflict, keepDevice = true, records = a.records) }
        assertEquals("device v2", server.text(SaveKind.SAVE, "Pokemon Emerald (USA).srm"))
        assertEquals(1, a.sync().first.unchanged)
    }

    @Test
    fun `first sync of a device that already has the same save just records it`() {
        server.put(SaveKind.SAVE, 1, "Pokemon Emerald (USA).srm", "same")
        val a = Device()
        a.store.put(SaveKind.SAVE, "Pokemon Emerald (USA).srm", "same")
        val (result, conflicts) = a.sync()
        assertEquals(1, result.unchanged)
        assertTrue(conflicts.isEmpty())
        // Different bytes on first meeting: the user decides.
        val b = Device()
        b.store.put(SaveKind.SAVE, "Pokemon Emerald (USA).srm", "different")
        assertEquals(1, b.sync().first.conflicts)
    }

    @Test
    fun `saves without a game in RomM are left alone and not searched every sync`() {
        val a = Device()
        a.store.put(SaveKind.SAVE, "Homebrew Thing.srm", "x")
        a.store.put(SaveKind.SAVE, "Dr. Mario (World).srm", "ambiguous: gb or nes")
        a.store.put(SaveKind.SAVE, "gb/Dr. Mario (World).srm", "the gb one")
        val (result, _) = a.sync()
        assertEquals(2, result.notMatched)
        assertEquals(1, result.uploaded)
        assertEquals(3, server.stored.single().romId)
        val searches = server.searches
        a.sync()
        assertEquals(searches, server.searches)
        assertNull(server.text(SaveKind.SAVE, "Homebrew Thing.srm"))
    }

    @Test
    fun `a failing file does not stop the others`() {
        val a = Device()
        a.store.put(SaveKind.SAVE, "Pokemon Emerald (USA).srm", "ok")
        server.put(SaveKind.SAVE, 2, "Super Mario World (USA).srm", "server")
        val broken = object : SaveStore by a.store {
            override suspend fun write(kind: SaveKind, path: String, bytes: ByteArray): LocalSaveFile = throw java.io.IOException("disk full")
        }
        val (result, _) = runBlocking { SaveSyncEngine(server, broken, clock = { clock.now }).sync(a.records) }
        assertEquals(1, result.uploaded)
        assertEquals(1, result.failed)
        assertTrue(result.errors.single().contains("disk full"))
    }
}
