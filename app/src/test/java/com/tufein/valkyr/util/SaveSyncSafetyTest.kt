package com.tufein.valkyr.util

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 5.5 save sync: a copy before every replacement, the three-way merge, the two options. */
class SaveSyncSafetyTest {
    private val name = "Pokemon Emerald (USA).srm"
    private val path = "mGBA/$name"
    private val roms = listOf(SaveSyncPlanner.RomCandidate(1, "Pokemon Emerald (USA).gba", "gba", "gba"))
    private val clock = TestClock()
    private val server = FakeSaveServer(clock, roms)

    private inner class Device(var options: SaveSyncOptions = SaveSyncOptions(), val deletions: Boolean = false) {
        val store = FakeSaveStore(clock).also { it.folders[SaveKind.SAVE] = mutableSetOf("mGBA") }
        val records = mutableMapOf<String, SaveSyncRecord>()
        val engine = SaveSyncEngine(server, store, clock = { clock.now }, syncDeletions = { deletions }, options = { options })
        fun sync() = runBlocking { engine.sync(records) }
        fun record() = records[SaveSyncPlanner.key(SaveKind.SAVE, path)]
        fun text() = store.text(SaveKind.SAVE, path)
    }

    private fun later(ms: Long = 60_000) { clock.now += ms }

    private fun synced(options: SaveSyncOptions = SaveSyncOptions(), deletions: Boolean = false): Device {
        val d = Device(options, deletions)
        d.store.put(SaveKind.SAVE, path, "v1")
        d.sync()
        assertEquals("v1", server.text(SaveKind.SAVE, name))
        return d
    }

    // ---- A copy before every replacement ---------------------------------------------------------

    @Test fun `an upload that replaces the server save keeps the server version on the device first`() {
        val d = synced()
        // Another device saved; this one syncs that down, then plays on.
        server.put(SaveKind.SAVE, 1, name, "other device")
        d.sync()
        d.store.put(SaveKind.SAVE, path, "v3 on this device")
        d.store.copies.clear()
        val (r, _) = d.sync()
        assertEquals(1, r.uploaded)
        assertEquals(listOf(path to "other device"), d.store.copies)
        assertEquals("v3 on this device", server.text(SaveKind.SAVE, name))
    }

    @Test fun `no upload happens when the copy of the server save cannot be kept`() {
        val d = synced()
        d.store.put(SaveKind.SAVE, path, "v2")
        d.store.copyFailure = IOException("disk full")
        val (r, _) = d.sync()
        assertEquals(1, r.failed)
        assertEquals(0, r.uploaded)
        assertEquals("v1", server.text(SaveKind.SAVE, name))
        assertTrue(r.errors.single().contains("disk full"))
    }

    @Test fun `a new save needs no copy`() {
        val d = Device()
        d.store.put(SaveKind.SAVE, path, "first")
        val (r, _) = d.sync()
        assertEquals(1, r.uploaded)
        assertTrue(d.store.copies.isEmpty())
    }

    @Test fun `the device save a download replaces is still backed up`() {
        val d = synced()
        server.put(SaveKind.SAVE, 1, name, "from the web player")
        val (r, _) = d.sync()
        assertEquals(1, r.downloaded)
        assertEquals(listOf(path), d.store.backups)
        assertEquals("from the web player", d.text())
    }

    @Test fun `keeping the device copy of a conflict also keeps the server version`() {
        val d = synced()
        d.store.put(SaveKind.SAVE, path, "device v2")
        server.put(SaveKind.SAVE, 1, name, "server v2")
        val (r, conflicts) = d.sync()
        assertEquals(1, r.conflicts)
        runBlocking { d.engine.resolve(conflicts.single(), keepDevice = true, records = d.records) }
        assertEquals("device v2", server.text(SaveKind.SAVE, name))
        assertEquals(listOf(path to "server v2"), d.store.copies)
    }

    // ---- The manifest ----------------------------------------------------------------------------

    @Test fun `every transfer records the hash both sides held`() {
        val d = synced()
        assertEquals(SaveSyncEngine.md5("v1".toByteArray()), d.record()?.baseHash)
        server.put(SaveKind.SAVE, 1, name, "v2")
        d.sync()
        assertEquals(SaveSyncEngine.md5("v2".toByteArray()), d.record()?.baseHash)
    }

    @Test fun `a file rewritten with the same bytes is not a change`() {
        val d = synced()
        d.store.touch(SaveKind.SAVE, path)
        val (r, conflicts) = d.sync()
        assertEquals(0, r.uploaded)
        assertEquals(1, r.unchanged)
        assertTrue(conflicts.isEmpty())
        // The snapshot caught up: the next sync does not need to read the file again.
        assertFalse(SaveSyncPlanner.localChanged(d.store.files.getValue(SaveKind.SAVE to path).let { LocalSaveFile(SaveKind.SAVE, path, it.bytes.size.toLong(), it.modified) }, d.record()!!))
    }

    @Test fun `a server that only touches its time is not a change`() {
        val d = synced()
        server.touch(SaveKind.SAVE, name)
        val (r, _) = d.sync()
        assertEquals(1, r.unchanged)
        assertEquals(0, r.downloaded)
        assertTrue(d.store.backups.isEmpty())
    }

    @Test fun `a real device change is uploaded although the server only touched its time`() {
        val d = synced()
        d.store.put(SaveKind.SAVE, path, "v2")
        server.touch(SaveKind.SAVE, name)
        val (r, conflicts) = d.sync()
        assertTrue(conflicts.isEmpty())
        assertEquals(1, r.uploaded)
        assertEquals("v2", server.text(SaveKind.SAVE, name))
    }

    @Test fun `a real server change is applied although the device only touched its file`() {
        val d = synced()
        d.store.touch(SaveKind.SAVE, path)
        server.put(SaveKind.SAVE, 1, name, "v2 from server")
        val (r, conflicts) = d.sync()
        assertTrue(conflicts.isEmpty())
        assertEquals(1, r.downloaded)
        assertEquals("v2 from server", d.text())
    }

    @Test fun `both sides changing to the same bytes is no conflict`() {
        val d = synced()
        d.store.put(SaveKind.SAVE, path, "same new save")
        server.put(SaveKind.SAVE, 1, name, "same new save")
        val (r, conflicts) = d.sync()
        assertTrue(conflicts.isEmpty())
        assertEquals(0, r.conflicts)
        assertEquals(1, r.unchanged)
        assertTrue(d.store.backups.isEmpty())
        assertEquals(SaveSyncEngine.md5("same new save".toByteArray()), d.record()?.baseHash)
    }

    @Test fun `changes on both sides stay a conflict and nothing moves`() {
        val d = synced()
        d.store.put(SaveKind.SAVE, path, "device v2")
        server.put(SaveKind.SAVE, 1, name, "server v2")
        val (r, conflicts) = d.sync()
        assertEquals(1, r.conflicts)
        assertEquals(1, conflicts.size)
        assertEquals("device v2", d.text())
        assertEquals("server v2", server.text(SaveKind.SAVE, name))
        assertEquals(0, r.uploaded + r.downloaded)
        // Still a conflict on the next sync: nothing decided anything.
        assertEquals(1, d.sync().first.conflicts)
    }

    // ---- Keep both -------------------------------------------------------------------------------

    @Test fun `keep both replaces nothing and keeps the server version as a copy`() {
        val d = synced()
        d.store.put(SaveKind.SAVE, path, "device v2")
        server.put(SaveKind.SAVE, 1, name, "server v2")
        val (_, conflicts) = d.sync()
        runBlocking { d.engine.resolveKeepBoth(conflicts.single(), d.records) }
        assertEquals("device v2", d.text())
        assertEquals("server v2", server.text(SaveKind.SAVE, name))
        assertEquals(listOf(path to "server v2"), d.store.copies)
        // Both are noted as seen: the next sync neither reports nor moves anything.
        val (again, left) = d.sync()
        assertTrue(left.isEmpty())
        assertEquals(1, again.unchanged)
        assertEquals("server v2", server.text(SaveKind.SAVE, name))
    }

    @Test fun `after keep both a new device save goes up and the server version stays in the copy`() {
        val d = synced()
        d.store.put(SaveKind.SAVE, path, "device v2")
        server.put(SaveKind.SAVE, 1, name, "server v2")
        runBlocking { d.engine.resolveKeepBoth(d.sync().second.single(), d.records) }
        d.store.put(SaveKind.SAVE, path, "device v3")
        val (r, _) = d.sync()
        assertEquals(1, r.uploaded)
        assertEquals("device v3", server.text(SaveKind.SAVE, name))
        assertTrue(d.store.copies.all { it.second == "server v2" })
    }

    @Test fun `keep both fails without touching anything when the copy cannot be kept`() {
        val d = synced()
        d.store.put(SaveKind.SAVE, path, "device v2")
        server.put(SaveKind.SAVE, 1, name, "server v2")
        val (_, conflicts) = d.sync()
        val before = d.records.toMap()
        d.store.copyFailure = IOException("no space")
        try { runBlocking { d.engine.resolveKeepBoth(conflicts.single(), d.records) }; throw AssertionError("should have failed") }
        catch (_: IOException) { }
        assertEquals(before, d.records)
        assertEquals(1, d.sync().first.conflicts)
    }

    // ---- Prefer the newer server save ------------------------------------------------------------

    @Test fun `prefer newer server takes a clearly newer server save and backs the device save up`() {
        val d = synced(SaveSyncOptions(preferNewerServer = true))
        d.store.put(SaveKind.SAVE, path, "device v2")
        later()
        server.put(SaveKind.SAVE, 1, name, "server v2")
        val (r, conflicts) = d.sync()
        assertTrue(conflicts.isEmpty())
        assertEquals(1, r.downloaded)
        assertEquals("server v2", d.text())
        assertEquals(listOf(path), d.store.backups)
    }

    @Test fun `prefer newer server asks when the device save is the newer one`() {
        val d = synced(SaveSyncOptions(preferNewerServer = true))
        server.put(SaveKind.SAVE, 1, name, "server v2")
        later()
        d.store.put(SaveKind.SAVE, path, "device v2")
        val (r, conflicts) = d.sync()
        assertEquals(1, conflicts.size)
        assertEquals(0, r.downloaded)
        assertEquals("device v2", d.text())
    }

    @Test fun `with the option off a newer server save is still a conflict`() {
        val d = synced()
        d.store.put(SaveKind.SAVE, path, "device v2")
        later()
        server.put(SaveKind.SAVE, 1, name, "server v2")
        assertEquals(1, d.sync().second.size)
    }

    @Test fun `prefer newer server also settles two never-synced files that differ`() {
        val d = Device(SaveSyncOptions(preferNewerServer = true))
        d.store.put(SaveKind.SAVE, path, "old device save")
        later()
        server.put(SaveKind.SAVE, 1, name, "newer server save", "mGBA")
        val (r, conflicts) = d.sync()
        assertTrue(conflicts.isEmpty())
        assertEquals(1, r.downloaded)
        assertEquals("newer server save", d.text())
        assertEquals(listOf(path), d.store.backups)
    }

    // ---- Never overwrite, only add ---------------------------------------------------------------

    @Test fun `never overwrite keeps a changed server save as a copy instead of replacing the device save`() {
        val d = synced(SaveSyncOptions(nonDestructive = true))
        server.put(SaveKind.SAVE, 1, name, "server v2")
        val (r, _) = d.sync()
        assertEquals(0, r.downloaded)
        assertEquals(1, r.serverCopies)
        assertEquals("v1", d.text())
        assertEquals(listOf(path to "server v2"), d.store.copies)
        assertTrue(d.store.backups.isEmpty())
        // Seen: the same server version is not copied again.
        val (again, _) = d.sync()
        assertEquals(0, again.serverCopies)
        assertEquals(1, d.store.copies.size)
    }

    @Test fun `never overwrite keeps the old server snapshot so the newer save is applied once replacing is allowed`() {
        val d = synced(SaveSyncOptions(nonDestructive = true))
        server.put(SaveKind.SAVE, 1, name, "server v2")
        d.sync()
        assertEquals(1, d.store.copies.size)
        // Seen again: no second identical copy, and the save is still pending.
        val (again, _) = d.sync()
        assertEquals(0, again.serverCopies)
        assertEquals(1, d.store.copies.size)
        assertEquals("v1", d.text())
        d.options = SaveSyncOptions()
        val (r, _) = d.sync()
        assertEquals(1, r.downloaded)
        assertEquals("server v2", d.text())
    }

    @Test fun `resolving to keep the device copy backs up the server save as it is now`() {
        val d = synced()
        d.store.put(SaveKind.SAVE, path, "device v2")
        server.put(SaveKind.SAVE, 1, name, "server v2")
        val (_, conflicts) = d.sync()
        later()
        server.put(SaveKind.SAVE, 1, name, "server v3")
        runBlocking { d.engine.resolve(conflicts.single(), keepDevice = true, records = d.records) }
        assertEquals("device v2", server.text(SaveKind.SAVE, name))
        assertEquals(listOf(path to "server v3"), d.store.copies)
    }

    @Test fun `never overwrite does not send a changed device save over the server's`() {
        val d = synced(SaveSyncOptions(nonDestructive = true))
        d.store.put(SaveKind.SAVE, path, "device v2")
        val (r, _) = d.sync()
        assertEquals(0, r.uploaded)
        assertEquals(1, r.keptAsIs)
        assertEquals("v1", server.text(SaveKind.SAVE, name))
    }

    @Test fun `never overwrite still adds new saves in both directions`() {
        val a = Device(SaveSyncOptions(nonDestructive = true))
        a.store.put(SaveKind.SAVE, path, "badge 1")
        assertEquals(1, a.sync().first.uploaded)
        val b = Device(SaveSyncOptions(nonDestructive = true))
        val (r, _) = b.sync()
        assertEquals(1, r.downloaded)
        assertEquals("badge 1", b.text())
    }

    @Test fun `never overwrite mirrors no deletion even when deletions are on`() {
        val d = synced(SaveSyncOptions(nonDestructive = true), deletions = true)
        d.store.remove(SaveKind.SAVE, path)
        val (r, _) = d.sync()
        assertEquals(0, r.deletedOnServer)
        assertEquals("v1", server.text(SaveKind.SAVE, name))
    }

    @Test fun `never overwrite with prefer newer server keeps both instead of taking the server`() {
        val d = synced(SaveSyncOptions(preferNewerServer = true, nonDestructive = true))
        d.store.put(SaveKind.SAVE, path, "device v2")
        later()
        server.put(SaveKind.SAVE, 1, name, "server v2")
        val (r, conflicts) = d.sync()
        assertTrue(conflicts.isEmpty())
        assertEquals(1, r.serverCopies)
        assertEquals("device v2", d.text())
        assertEquals("server v2", server.text(SaveKind.SAVE, name))
        assertEquals(listOf(path to "server v2"), d.store.copies)
    }

    @Test fun `never overwrite leaves the conflict list to the user and an explicit choice still works`() {
        val d = synced(SaveSyncOptions(nonDestructive = true))
        d.store.put(SaveKind.SAVE, path, "device v2")
        server.put(SaveKind.SAVE, 1, name, "server v2")
        val (_, conflicts) = d.sync()
        assertEquals(1, conflicts.size)
        runBlocking { d.engine.resolve(conflicts.single(), keepDevice = false, records = d.records) }
        assertEquals("server v2", d.text())
        assertEquals(listOf(path), d.store.backups)
    }

    @Test fun `default options keep the old behaviour`() {
        val d = synced()
        d.store.put(SaveKind.SAVE, path, "v2")
        assertEquals(1, d.sync().first.uploaded)
        server.put(SaveKind.SAVE, 1, name, "v3")
        assertEquals(1, d.sync().first.downloaded)
        assertNotNull(d.record())
        assertNull(d.sync().second.firstOrNull())
    }
}
