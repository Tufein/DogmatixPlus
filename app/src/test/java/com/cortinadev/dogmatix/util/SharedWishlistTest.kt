package com.cortinadev.dogmatix.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SharedWishlistTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun wish(title: String, console: String? = "gba", at: Long = 100L, by: String = "Anna", doneBy: String = "", doneAt: Long = 0L) =
        SharedWish(title, console, at, by, doneBy, doneAt)

    private fun map(vararg w: SharedWish) = w.associateBy { it.key }
    private fun base(vararg w: SharedWish) = w.associate { it.key to it.toSync() }

    // ---- JSON ----------------------------------------------------------------------------------

    @Test fun `the file round-trips with authors, found notes and removals`() {
        val list = SharedWishlist(
            wishes = map(wish("Mother 3", "gba", 5, "Anna", "Ben", 9), wish("Chrono Trigger", null, 7, "Ben")),
            removed = mapOf("snes|zelda" to 42L), updatedAt = 99, updatedBy = "Ben", rev = "r1"
        )
        val back = (SharedWishlistJson.read(SharedWishlistJson.write(list)) as SharedWishlistJson.Parsed.Ok).list
        assertEquals(list, back)
    }

    @Test fun `a newer or foreign file is reported, malformed rows are skipped`() {
        assertEquals(SharedWishlistJson.Parsed.Newer(9), SharedWishlistJson.read("""{"format":"dogmatix-shared-wishlist","version":9}"""))
        assertEquals(SharedWishlistJson.Parsed.Invalid, SharedWishlistJson.read("<html>"))
        assertEquals(SharedWishlistJson.Parsed.Invalid, SharedWishlistJson.read("""{"format":"dogmatix-sync","version":1}"""))
        val text = """{"format":"dogmatix-shared-wishlist","version":1,"wishes":[{"title":"x"},{"title":"Mother 3","addedAt":3},5],"removed":[{"key":"nobar"},{"key":"a|b","at":2}]}"""
        val list = (SharedWishlistJson.read(text) as SharedWishlistJson.Parsed.Ok).list
        assertEquals(1, list.wishes.size)
        assertEquals(mapOf("a|b" to 2L), list.removed)
    }

    @Test fun `the people notes round-trip`() {
        val meta = SharedMeta(mapOf("gba|mother3" to "Anna"), mapOf("gba|mother3" to "Ben"))
        assertEquals(meta, SharedWishlistJson.readMeta(SharedWishlistJson.writeMeta(meta)))
        assertEquals(SharedMeta.EMPTY, SharedWishlistJson.readMeta("garbage"))
    }

    // ---- Merge ---------------------------------------------------------------------------------

    @Test fun `without a base the lists are joined and the authors kept`() {
        val mine = map(wish("Mother 3", by = "Ben"))
        val remote = SharedWishlist(map(wish("Mother 3", by = "Anna"), wish("Earthbound", "snes", by = "Anna")))
        val r = SharedWishlistMerge.merge(null, mine, remote, now)
        assertEquals(2, r.merged.size)
        assertEquals("Anna", r.merged[wish("Mother 3").key]!!.addedBy)       // the file's author stands
        assertEquals(setOf(wish("Earthbound", "snes").key), r.toLocalAdded.keys)
        assertTrue(r.toLocalRemoved.isEmpty())
        assertFalse(r.remoteChanged)
    }

    @Test fun `a wish added here is sent and a removal by somebody else comes here`() {
        val a = wish("Mother 3")
        val b = wish("Earthbound", "snes")
        val c = wish("Mario Kart", "gba", 50L, "Ben")
        // Base had a and b; this device added c; the file lost b (Anna removed it).
        val r = SharedWishlistMerge.merge(base(a, b), map(a, b, c), SharedWishlist(map(a)), now)
        assertEquals(setOf(a.key, c.key), r.merged.keys)
        assertEquals(setOf(b.key), r.toLocalRemoved)
        assertTrue(r.remoteChanged)
        assertTrue(r.removals >= 1)
    }

    @Test fun `a removal here leaves a tombstone and the wish does not come back`() {
        val a = wish("Mother 3")
        val b = wish("Earthbound", "snes")
        val r = SharedWishlistMerge.merge(base(a, b), map(a), SharedWishlist(map(a, b)), now)
        assertEquals(setOf(a.key), r.merged.keys)
        assertEquals(mapOf(b.key to now), r.tombstones)
        assertTrue(r.remoteChanged)
        assertEquals(setOf(b.key), r.removedFromRemote)
    }

    @Test fun `a tombstone drops the wish on a device that never synced, unless it was added again later`() {
        val old = wish("Earthbound", "snes", at = now - 5_000)
        val tomb = SharedWishlist(emptyMap(), mapOf(old.key to now - 1_000))
        val r = SharedWishlistMerge.merge(null, map(old), tomb, now)
        assertTrue(r.merged.isEmpty())
        assertEquals(setOf(old.key), r.toLocalRemoved)
        // Added again after the removal: the newer wish wins and the tombstone goes.
        val again = old.copy(addedAt = now - 100)
        val r2 = SharedWishlistMerge.merge(null, map(again), tomb, now)
        assertEquals(setOf(again.key), r2.merged.keys)
        assertTrue(r2.tombstones.isEmpty())
        assertTrue(r2.remoteChanged)
    }

    @Test fun `old tombstones are dropped`() {
        val tomb = SharedWishlist(emptyMap(), mapOf("snes|x" to now - 100 * day, "snes|y" to now - day))
        val r = SharedWishlistMerge.merge(null, emptyMap(), tomb, now)
        assertEquals(mapOf("snes|y" to now - day), r.tombstones)
        assertTrue(r.remoteChanged)
    }

    @Test fun `the first person to find a game is noted and kept`() {
        val a = wish("Mother 3")
        val mine = map(a.copy(addedBy = "Ben", doneBy = "Ben", doneAt = 300))
        val remote = SharedWishlist(map(a.copy(doneBy = "Anna", doneAt = 200)))
        val r = SharedWishlistMerge.merge(base(a), mine, remote, now)
        assertEquals("Anna", r.merged[a.key]!!.doneBy)
        assertEquals(200L, r.merged[a.key]!!.doneAt)
        // Found here only: the note goes to the file.
        val r2 = SharedWishlistMerge.merge(base(a), map(a.copy(doneBy = "Ben", doneAt = 300)), SharedWishlist(map(a)), now)
        assertEquals("Ben", r2.merged[a.key]!!.doneBy)
        assertTrue(r2.remoteChanged)
        assertEquals(mapOf(a.key to "Ben"), SharedWishlistMerge.metaOf(r2.merged, "Anna").doneBy)
    }

    @Test fun `the people notes list only the others as authors`() {
        val merged = map(wish("A game", by = "Anna"), wish("B game", "snes", by = "ben"))
        val meta = SharedWishlistMerge.metaOf(merged, "Ben")
        assertEquals(mapOf(wish("A game").key to "Anna"), meta.authors)
    }

    @Test fun `many removals at once are held back`() {
        val many = (1..30).map { wish("Quest " + ('a' + it / 5) + ('a' + it % 5) + "xx", "snes") }.toTypedArray()
        val r = SharedWishlistMerge.merge(base(*many), emptyMap(), SharedWishlist(map(*many)), now)
        assertTrue(SharedWishlistMerge.tooManyRemovals(r, base(*many)))
        assertFalse(SharedWishlistMerge.tooManyRemovals(r, null))
    }

    // ---- Engine --------------------------------------------------------------------------------

    private val server = "https://nas.local/dav/"
    private val root = "https://nas.local/dav/Dogmatix/"
    private val fileUrl = "https://nas.local/dav/Dogmatix/shared/family/wishlist.json"
    private var clock = now

    private class FakeWishes(var wishes: Map<String, SharedWish> = emptyMap()) : SharedWishlistEngine.Local {
        var base: DeviceSyncJson.Base? = null
        var meta: Map<String, SharedWish> = emptyMap()
        override suspend fun snapshot() = wishes
        override suspend fun apply(added: Collection<SharedWish>, removed: Set<String>, merged: Map<String, SharedWish>) {
            wishes = (wishes - removed) + added.associateBy { it.key }
            meta = merged
        }
        override suspend fun readBase() = base
        override suspend fun writeBase(base: DeviceSyncJson.Base) { this.base = base }
    }

    private fun engine(store: DavStore, local: SharedWishlistEngine.Local, list: String = "Family") =
        SharedWishlistEngine(store, server, root, list, local, clock = { clock++ })

    private fun remote(store: FakeDavStore) = (SharedWishlistJson.read(store.text(fileUrl)!!) as SharedWishlistJson.Parsed.Ok).list

    @Test fun `the list name makes the folder, ignoring case and slashes`() {
        assertEquals("family", SharedWishlistEngine.folderName("  Family "))
        assertEquals("casa-garcia", SharedWishlistEngine.folderName("Casa/García".replace("García", "garcia")))
        assertNull(SharedWishlistEngine.folderName("   "))
        assertNull(SharedWishlistEngine.folderName(".."))
        assertEquals("a b", SharedWishlistEngine.folderName("A   B"))
        assertTrue(SharedWishlistEngine.folderName("x".repeat(200))!!.length <= SharedWishlistEngine.MAX_LIST_NAME)
    }

    @Test fun `two people share one list through the server`() = runBlocking {
        val store = FakeDavStore("/dav", "/dav/Dogmatix")
        val anna = FakeWishes(map(wish("Mother 3", by = "Anna")))
        val ben = FakeWishes(map(wish("Earthbound", "snes", by = "Ben")))
        engine(store, anna).sync("Anna")
        assertTrue("/dav/Dogmatix/shared/family" in store.collections)
        assertEquals(1, remote(store).wishes.size)

        val outcome = engine(store, ben).sync("Ben") as SharedWishlistEngine.Outcome.Synced
        assertEquals(1, outcome.added)
        assertTrue(outcome.sent)
        assertEquals(2, ben.wishes.size)
        assertEquals("Anna", ben.meta[wish("Mother 3").key]!!.addedBy)

        // Anna gets Ben's wish, with his name.
        engine(store, anna).sync("Anna")
        assertEquals(2, anna.wishes.size)
        assertEquals("Ben", anna.meta[wish("Earthbound", "snes").key]!!.addedBy)
        assertEquals(setOf("Anna", "Ben"), remote(store).wishes.values.map { it.addedBy }.toSet())
    }

    @Test fun `a removal and a found note travel between people`() = runBlocking {
        val store = FakeDavStore("/dav", "/dav/Dogmatix")
        val m3 = wish("Mother 3", by = "Anna")
        val eb = wish("Earthbound", "snes", by = "Anna")
        val anna = FakeWishes(map(m3, eb))
        val ben = FakeWishes()
        engine(store, anna).sync("Anna")
        engine(store, ben).sync("Ben")
        // Ben finds Mother 3 and Anna removes Earthbound.
        ben.wishes = ben.wishes + (m3.key to ben.wishes.getValue(m3.key).copy(doneBy = "Ben", doneAt = 777))
        anna.wishes = anna.wishes - eb.key
        engine(store, ben).sync("Ben")
        engine(store, anna).sync("Anna")
        engine(store, ben).sync("Ben")
        assertEquals("Ben", remote(store).wishes[m3.key]!!.doneBy)
        assertEquals(setOf(m3.key), remote(store).wishes.keys)
        assertEquals(setOf(m3.key), ben.wishes.keys)
        assertEquals("Ben", anna.meta[m3.key]!!.doneBy)
        assertEquals(setOf(eb.key), remote(store).removed.keys)
    }

    @Test fun `two people writing at once never lose a wish, even without ETags`() = runBlocking {
        val store = FakeDavStore("/dav", "/dav/Dogmatix").apply { hideEtags = true; ignoreConditions = true }
        val anna = FakeWishes(map(wish("Mother 3", by = "Anna")))
        engine(store, anna).sync("Anna")
        anna.wishes = anna.wishes + map(wish("Earthbound", "snes", by = "Anna"))
        val bens = SharedWishlistJson.write(SharedWishlist(map(wish("Mother 3", by = "Anna"), wish("Pokemon Gold", "gbc", by = "Ben")), emptyMap(), 1, "Ben"))
        store.afterGet = { store.write(fileUrl, bens) }
        engine(store, anna).sync("Anna")
        assertEquals(3, remote(store).wishes.size)
        assertEquals(3, anna.wishes.size)
    }

    @Test fun `a damaged or newer file is left alone`() = runBlocking {
        val store = FakeDavStore("/dav", "/dav/Dogmatix/shared/family")
        val local = FakeWishes(map(wish("Mother 3")))
        store.write(fileUrl, """{"format":"dogmatix-shared-wishlist","version":5}""")
        try { engine(store, local).sync("Anna"); fail("expected NewerFileException") } catch (e: SharedWishlistEngine.NewerFileException) { assertEquals(5, e.version) }
        store.write(fileUrl, "oops")
        try { engine(store, local).sync("Anna"); fail("expected UnreadableFileException") } catch (_: SharedWishlistEngine.UnreadableFileException) { }
        assertFalse(store.requests.any { it.startsWith("PUT") })
        assertNull(local.base)
    }

    @Test fun `an emptied list is held back in the background but a manual sync applies it`() = runBlocking {
        val store = FakeDavStore("/dav", "/dav/Dogmatix")
        val many = (1..30).map { wish("Quest " + ('a' + it / 5) + ('a' + it % 5) + "xx", "snes") }
        val anna = FakeWishes(map(*many.toTypedArray()))
        engine(store, anna).sync("Anna")
        anna.wishes = emptyMap()
        assertEquals(SharedWishlistEngine.Outcome.HeldBack(30), engine(store, anna).sync("Anna"))
        assertEquals(30, remote(store).wishes.size)
        engine(store, anna).sync("Anna", allowMassRemoval = true)
        assertTrue(remote(store).wishes.isEmpty())
    }

    @Test fun `a server that went back in time removes nothing`() = runBlocking {
        val store = FakeDavStore("/dav", "/dav/Dogmatix")
        val anna = FakeWishes(map(wish("Mother 3"), wish("Earthbound", "snes")))
        engine(store, anna).sync("Anna")
        store.write(fileUrl, SharedWishlistJson.write(SharedWishlist(map(wish("Mother 3")), emptyMap(), updatedAt = 1_000L)))
        val outcome = engine(store, anna).sync("Anna") as SharedWishlistEngine.Outcome.Synced
        assertTrue(outcome.rolledBack)
        assertEquals(2, anna.wishes.size)
        assertEquals(2, remote(store).wishes.size)
    }

    @Test fun `another list name is another file`() = runBlocking {
        val store = FakeDavStore("/dav", "/dav/Dogmatix")
        val anna = FakeWishes(map(wish("Mother 3")))
        engine(store, anna, "Family").sync("Anna")
        val ben = FakeWishes()
        engine(store, ben, "Friends").sync("Ben")
        assertTrue(ben.wishes.isEmpty())
        assertEquals(setOf("/dav/Dogmatix/shared/family/wishlist.json", "/dav/Dogmatix/shared/friends/wishlist.json"), store.files.keys)
        assertEquals(1, (SharedWishlistJson.read(store.text(fileUrl)!!) as SharedWishlistJson.Parsed.Ok).list.wishes.size)
    }
}
