package com.cortinadev.dogmatix.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceSyncEngineTest {

    private val server = "https://nas.local/dav/"
    private val root = "https://nas.local/dav/Dogmatix/"
    private val fileUrl = "https://nas.local/dav/Dogmatix/sync/library.json"
    private var now = 1_800_000_000_000L

    private val chrono = DeviceSyncMerge.itemKey("snes", "Chrono Trigger (USA).sfc")
    private val zelda = DeviceSyncMerge.itemKey("snes", "Zelda (USA).sfc")
    private val metroid = DeviceSyncMerge.itemKey("gba", "Metroid Fusion (USA).gba")

    private val thor = DeviceSyncEngine.Device("id-thor", "Thor")
    private val odin = DeviceSyncEngine.Device("id-odin", "Odin")

    private fun engine(store: DavStore, local: DeviceSyncEngine.Local) = DeviceSyncEngine(store, server, root, local) { now++ }

    private fun favs(vararg keys: String) = SyncLibrary(favourites = keys.associateWith { 100L })

    private fun remoteLibrary(store: FakeDavStore): SyncLibrary =
        (DeviceSyncJson.read(store.text(fileUrl)!!) as DeviceSyncJson.Parsed.Ok).document.library

    @Test fun `the first device starts the file, the second gets its favourites`() = runBlocking {
        val store = FakeDavStore("/dav")
        val a = FakeLocal(favs(chrono, zelda))
        val first = engine(store, a).sync(thor)
        assertEquals(DeviceSyncEngine.Outcome.Synced(0, 0, true), first)
        assertTrue(store.requests.any { it.startsWith("PUT $fileUrl") && it.endsWith("if-none-match") })
        assertEquals(setOf(chrono, zelda), remoteLibrary(store).favourites.keys)
        assertEquals(fileUrl, a.base?.remoteUrl)

        val b = FakeLocal(favs(metroid))
        val second = engine(store, b).sync(odin)
        assertEquals(DeviceSyncEngine.Outcome.Synced(2, 0, true), second)
        assertEquals(setOf(chrono, zelda, metroid), b.library.favourites.keys)
        val doc = (DeviceSyncJson.read(store.text(fileUrl)!!) as DeviceSyncJson.Parsed.Ok).document
        assertEquals(setOf("id-thor", "id-odin"), doc.devices.keys)
        assertEquals("Odin", doc.updatedBy)
    }

    @Test fun `a removal travels and nothing is written when nothing changed`() = runBlocking {
        val store = FakeDavStore("/dav")
        val a = FakeLocal(favs(chrono, zelda))
        val b = FakeLocal()
        engine(store, a).sync(thor)
        engine(store, b).sync(odin)
        a.library = favs(zelda)                                       // Thor un-stars Chrono
        assertEquals(DeviceSyncEngine.Outcome.Synced(0, 0, true), engine(store, a).sync(thor))
        assertEquals(DeviceSyncEngine.Outcome.Synced(0, 1, false), engine(store, b).sync(odin))
        assertEquals(setOf(zelda), b.library.favourites.keys)
        val writes = store.requests.count { it.startsWith("PUT") }
        assertEquals(DeviceSyncEngine.Outcome.Synced(0, 0, false), engine(store, b).sync(odin))
        assertEquals(writes, store.requests.count { it.startsWith("PUT") })
    }

    @Test fun `only local changes are sent when asked so`() = runBlocking {
        val store = FakeDavStore("/dav")
        val a = FakeLocal(favs(chrono))
        engine(store, a).sync(thor)
        val reads = store.requests.count { it.startsWith("GET") }
        assertEquals(DeviceSyncEngine.Outcome.NothingToSend, engine(store, a).sync(thor, onlyIfLocalChanges = true))
        assertEquals(reads, store.requests.count { it.startsWith("GET") })
        a.library = favs(chrono, zelda)
        assertEquals(DeviceSyncEngine.Outcome.Synced(0, 0, true), engine(store, a).sync(thor, onlyIfLocalChanges = true))
    }

    @Test fun `another device writing at the same moment is merged, not overwritten`() = runBlocking {
        val store = FakeDavStore("/dav")
        val a = FakeLocal(favs(chrono))
        val b = FakeLocal(favs(zelda))
        engine(store, a).sync(thor)
        engine(store, b).sync(odin)
        // Thor adds Metroid; while it writes, Odin's write of a new favourite lands first.
        a.library = favs(chrono, zelda, metroid)
        val odinsWrite = DeviceSyncJson.write(DeviceSyncJson.Document(favs(chrono, zelda, "gb|Tetris.gb"), now + 1_000, "Odin"))
        store.beforePut = { store.write(fileUrl, odinsWrite) }
        val outcome = engine(store, a).sync(thor)
        assertEquals(DeviceSyncEngine.Outcome.Synced(1, 0, true), outcome)
        assertEquals(setOf(chrono, zelda, metroid, "gb|Tetris.gb"), remoteLibrary(store).favourites.keys)
        assertEquals(remoteLibrary(store).favourites.keys, a.library.favourites.keys)
    }

    @Test fun `a server whose conditional writes never match still syncs`() = runBlocking {
        val store = FakeDavStore("/dav").apply { refuseConditionalWrites = true }
        val a = FakeLocal(favs(chrono))
        assertEquals(DeviceSyncEngine.Outcome.Synced(0, 0, true), engine(store, a).sync(thor))
        a.library = favs(chrono, zelda)
        assertEquals(DeviceSyncEngine.Outcome.Synced(0, 0, true), engine(store, a).sync(thor))
        assertEquals(setOf(chrono, zelda), remoteLibrary(store).favourites.keys)
    }

    @Test fun `an emptied device is held back until confirmed`() = runBlocking {
        val store = FakeDavStore("/dav")
        val many = SyncLibrary(favourites = (1..30).associate { DeviceSyncMerge.itemKey("snes", "Game $it.sfc") to 5L })
        val a = FakeLocal(many)
        engine(store, a).sync(thor)
        a.library = SyncLibrary.EMPTY
        val held = engine(store, a).sync(thor)
        assertEquals(DeviceSyncEngine.Outcome.HeldBack(30), held)
        assertEquals(30, remoteLibrary(store).favourites.size)
        val confirmed = engine(store, a).sync(thor, allowMassRemoval = true)
        assertEquals(DeviceSyncEngine.Outcome.Synced(0, 0, true), confirmed)
        assertTrue(remoteLibrary(store).favourites.isEmpty())
    }

    @Test fun `a deleted server file is a fresh start, never a reason to remove`() = runBlocking {
        val store = FakeDavStore("/dav")
        val a = FakeLocal(favs(chrono, zelda))
        engine(store, a).sync(thor)
        store.delete(fileUrl)
        assertEquals(DeviceSyncEngine.Outcome.Synced(0, 0, true), engine(store, a).sync(thor))
        assertEquals(setOf(chrono, zelda), a.library.favourites.keys)
        assertEquals(setOf(chrono, zelda), remoteLibrary(store).favourites.keys)
    }

    @Test fun `a newer or damaged server file is left alone and nothing changes here`() = runBlocking {
        val store = FakeDavStore("/dav", "/dav/Dogmatix/sync")
        val a = FakeLocal(favs(chrono))
        store.write(fileUrl, """{"format":"dogmatix-sync","version":9}""")
        try {
            engine(store, a).sync(thor)
            fail("expected NewerFileException")
        } catch (e: DeviceSyncEngine.NewerFileException) {
            assertEquals(9, e.version)
        }
        store.write(fileUrl, "<html>oops</html>")
        try {
            engine(store, a).sync(thor)
            fail("expected UnreadableFileException")
        } catch (_: DeviceSyncEngine.UnreadableFileException) {
        }
        assertFalse(store.requests.any { it.startsWith("PUT") })
        assertEquals(favs(chrono), a.library)
        assertNull(a.base)
    }

    @Test fun `a failed write changes nothing on the device`() = runBlocking {
        val store = FakeDavStore("/dav")
        val a = FakeLocal(favs(chrono))
        engine(store, a).sync(thor)
        val baseBefore = a.base
        store.write(fileUrl, DeviceSyncJson.write(DeviceSyncJson.Document(favs(chrono, zelda), 1, "Odin")))
        a.library = favs(chrono, metroid)
        store.failNextPut = DavProblem.NO_SPACE
        try {
            engine(store, a).sync(thor)
            fail("expected DavException")
        } catch (e: DavException) {
            assertEquals(DavProblem.NO_SPACE, e.problem)
        }
        assertEquals(favs(chrono, metroid), a.library)
        assertEquals(baseBefore, a.base)
    }

    @Test fun `a base from another server or folder is ignored`() = runBlocking {
        val store = FakeDavStore("/dav")
        store.ensureCollection("$root/sync/", server)
        store.write(fileUrl, DeviceSyncJson.write(DeviceSyncJson.Document(favs(zelda), 1, "Odin")))
        val a = FakeLocal(favs(chrono)).apply { base = DeviceSyncJson.Base("https://elsewhere/sync/library.json", favs(chrono, zelda)) }
        // With that base, Zelda would count as "removed here"; ignored, it is simply added.
        assertEquals(DeviceSyncEngine.Outcome.Synced(1, 0, true), engine(store, a).sync(thor))
        assertEquals(setOf(chrono, zelda), a.library.favourites.keys)
    }

    // ---- 6.0 hardening ---------------------------------------------------------------------

    @Test fun `a server without ETags never loses another device's change`() = runBlocking {
        val store = FakeDavStore("/dav").apply { hideEtags = true; ignoreConditions = true }
        val a = FakeLocal(favs(chrono))
        engine(store, a).sync(thor)
        // Odin writes right after Thor read the file: Thor's re-read before the write sees it.
        a.library = favs(chrono, zelda)
        val odins = DeviceSyncJson.write(DeviceSyncJson.Document(favs(chrono, "gb|Tetris.gb"), 1, "Odin"))
        store.afterGet = { store.write(fileUrl, odins) }
        engine(store, a).sync(thor)
        assertEquals(setOf(chrono, zelda, "gb|Tetris.gb"), remoteLibrary(store).favourites.keys)
        assertEquals(setOf(chrono, zelda, "gb|Tetris.gb"), a.library.favourites.keys)
    }

    @Test fun `a write another device overwrote right after it is merged and sent again`() = runBlocking {
        val store = FakeDavStore("/dav").apply { hideEtags = true; ignoreConditions = true }
        val a = FakeLocal(favs(chrono))
        engine(store, a).sync(thor)
        a.library = favs(chrono, zelda)
        // Odin's blind write lands right after Thor's: the read-back shows it is not Thor's file.
        val odins = DeviceSyncJson.write(DeviceSyncJson.Document(favs(chrono, "gb|Tetris.gb"), 1, "Odin"))
        store.afterPut = { store.write(fileUrl, odins) }
        engine(store, a).sync(thor)
        assertEquals(setOf(chrono, zelda, "gb|Tetris.gb"), remoteLibrary(store).favourites.keys)
    }

    @Test fun `a new file is read back when the server ignores If-None-Match`() = runBlocking {
        val store = FakeDavStore("/dav").apply { ignoreConditions = true }
        val a = FakeLocal(favs(chrono))
        val odins = DeviceSyncJson.write(DeviceSyncJson.Document(favs("gb|Tetris.gb"), 1, "Odin"))
        store.afterPut = { store.write(fileUrl, odins) }
        engine(store, a).sync(thor)
        assertEquals(setOf(chrono, "gb|Tetris.gb"), remoteLibrary(store).favourites.keys)
    }

    @Test fun `a server that keeps changing the file stops with an error, never an unchecked overwrite`() = runBlocking {
        val store = FakeDavStore("/dav").apply { hideEtags = true; ignoreConditions = true }
        val a = FakeLocal(favs(chrono))
        engine(store, a).sync(thor)
        a.library = favs(chrono, zelda)
        var n = 0
        // Every read is followed by another device's write.
        lateinit var hook: (String) -> Unit
        hook = { _ ->
            val doc = DeviceSyncJson.write(DeviceSyncJson.Document(favs(chrono, "gb|Game ${n++}.gb"), 1, "Odin"))
            store.write(fileUrl, doc)
            store.afterGet = hook
        }
        store.afterGet = hook
        try {
            engine(store, a).sync(thor)
            fail("expected DavException")
        } catch (e: DavException) {
            assertEquals(DavProblem.PRECONDITION, e.problem)
        }
        assertEquals(favs(chrono, zelda), a.library)
    }

    @Test fun `a server that went back in time removes nothing`() = runBlocking {
        val store = FakeDavStore("/dav")
        val a = FakeLocal(favs(chrono, zelda))
        val b = FakeLocal()
        engine(store, a).sync(thor)
        engine(store, b).sync(odin)
        // The server is restored to an old snapshot that holds only Chrono, dated long before Odin's last sync.
        store.write(fileUrl, DeviceSyncJson.write(DeviceSyncJson.Document(favs(chrono), updatedAt = 1_000L, updatedBy = "old")))
        val outcome = engine(store, b).sync(odin) as DeviceSyncEngine.Outcome.Synced
        assertTrue(outcome.rolledBack)
        assertEquals(0, outcome.removed)
        assertEquals(setOf(chrono, zelda), b.library.favourites.keys)
        // Zelda is sent up again.
        assertEquals(setOf(chrono, zelda), remoteLibrary(store).favourites.keys)
    }

    @Test fun `a normal sync is not taken for a rollback`() = runBlocking {
        val store = FakeDavStore("/dav")
        val a = FakeLocal(favs(chrono))
        engine(store, a).sync(thor)
        assertFalse((engine(store, a).sync(thor) as DeviceSyncEngine.Outcome.Synced).rolledBack)
        assertTrue(DeviceSyncEngine.isRollback(1_000, 1_000 + DeviceSyncEngine.ROLLBACK_SLACK_MS + 1))
        assertFalse(DeviceSyncEngine.isRollback(1_000, 1_000 + DeviceSyncEngine.ROLLBACK_SLACK_MS))
    }

    @Test fun `the base of another login on the same address is not trusted`() = runBlocking {
        val store = FakeDavStore("/dav")
        val a = FakeLocal(favs(chrono, zelda))
        val acct = DeviceSyncEngine.accountKey("john")
        DeviceSyncEngine(store, server, root, a, account = acct) { now++ }.sync(thor)
        assertEquals(acct, a.base?.account)
        // Another account's file holds only Chrono; with John's base, Zelda would count as removed there.
        val other = DeviceSyncEngine.accountKey("mary")
        store.write(fileUrl, DeviceSyncJson.write(DeviceSyncJson.Document(favs(chrono), updatedAt = now + 10, updatedBy = "Mary")))
        val outcome = DeviceSyncEngine(store, server, root, a, account = other) { now++ }.sync(thor)
        assertEquals(DeviceSyncEngine.Outcome.Synced(0, 0, true), outcome)
        assertEquals(setOf(chrono, zelda), a.library.favourites.keys)
        assertEquals(other, a.base?.account)
        // The same login keeps trusting its base.
        assertEquals(DeviceSyncEngine.accountKey("John "), DeviceSyncEngine.accountKey("john"))
    }

    @Test fun `a base from before 6_0 without a login is still trusted`() = runBlocking {
        val store = FakeDavStore("/dav")
        val a = FakeLocal(favs(chrono, zelda))
        engine(store, a).sync(thor)
        a.base = a.base!!.copy(account = "")
        a.library = favs(chrono)                                           // Zelda removed here
        engine(store, a).sync(thor)
        assertEquals(setOf(chrono), remoteLibrary(store).favourites.keys)
    }
}
