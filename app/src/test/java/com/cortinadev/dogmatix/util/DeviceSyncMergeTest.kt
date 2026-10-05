package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSyncMergeTest {

    private val chrono = DeviceSyncMerge.itemKey("snes", "Chrono Trigger (USA).sfc")
    private val zelda = DeviceSyncMerge.itemKey("snes", "Zelda - A Link to the Past (USA).sfc")
    private val metroid = DeviceSyncMerge.itemKey("gba", "Metroid Fusion (USA).gba")
    private val tetris = DeviceSyncMerge.itemKey("gb", "Tetris (World).gb")

    private fun favs(vararg pairs: Pair<String, Long>) = SyncLibrary(favourites = mapOf(*pairs))

    // ---- Favourites ----------------------------------------------------------------------------

    @Test fun `without a base the result is the union and nothing is removed`() {
        val r = DeviceSyncMerge.merge(null, favs(chrono to 10, zelda to 20), favs(zelda to 25, metroid to 30))
        assertEquals(setOf(chrono, zelda, metroid), r.merged.favourites.keys)
        assertEquals(setOf(metroid), r.toLocal.favouritesAdded.keys)
        assertEquals(setOf(chrono), r.toRemote.favouritesAdded.keys)
        assertEquals(0, r.removals)
        // Both had Zelda: the newer time is kept.
        assertEquals(25L, r.merged.favourites[zelda])
    }

    @Test fun `an addition on either side since the base survives`() {
        val base = favs(chrono to 10)
        val r = DeviceSyncMerge.merge(base, favs(chrono to 10, zelda to 50), favs(chrono to 10, metroid to 60))
        assertEquals(setOf(chrono, zelda, metroid), r.merged.favourites.keys)
        assertEquals(mapOf(metroid to 60L), r.toLocal.favouritesAdded)
        assertEquals(mapOf(zelda to 50L), r.toRemote.favouritesAdded)
    }

    @Test fun `a removal on one side removes it on both`() {
        val base = favs(chrono to 10, zelda to 20)
        val localRemoved = DeviceSyncMerge.merge(base, favs(zelda to 20), favs(chrono to 10, zelda to 20))
        assertEquals(setOf(zelda), localRemoved.merged.favourites.keys)
        assertEquals(setOf(chrono), localRemoved.toRemote.favouritesRemoved)
        assertTrue(localRemoved.toLocal.isEmpty)

        val remoteRemoved = DeviceSyncMerge.merge(base, favs(chrono to 10, zelda to 20), favs(zelda to 20))
        assertEquals(setOf(zelda), remoteRemoved.merged.favourites.keys)
        assertEquals(setOf(chrono), remoteRemoved.toLocal.favouritesRemoved)
        assertFalse(remoteRemoved.remoteChanged)
    }

    @Test fun `removed on both sides stays removed`() {
        val r = DeviceSyncMerge.merge(favs(chrono to 10), favs(), favs())
        assertTrue(r.merged.favourites.isEmpty())
        assertEquals(0, r.removals)
    }

    @Test fun `starred again after the other side removed it wins`() {
        val base = favs(chrono to 10)
        // This device removed and starred it again (time 99); the other device removed it.
        val r = DeviceSyncMerge.merge(base, favs(chrono to 99), favs())
        assertEquals(mapOf(chrono to 99L), r.merged.favourites)
        assertEquals(setOf(chrono), r.toRemote.favouritesAdded.keys)
        // The other way round: removed here, starred again over there.
        val r2 = DeviceSyncMerge.merge(base, favs(), favs(chrono to 99))
        assertEquals(mapOf(chrono to 99L), r2.merged.favourites)
        assertEquals(setOf(chrono), r2.toLocal.favouritesAdded.keys)
    }

    @Test fun `after a sync both sides match the base`() {
        val first = DeviceSyncMerge.merge(null, favs(chrono to 10, zelda to 30), favs(zelda to 20))
        val base = first.merged
        // Nothing changed since: nothing to do.
        val local = favs(chrono to 10, zelda to 30)
        val second = DeviceSyncMerge.merge(base, local, base)
        assertTrue(second.toLocal.isEmpty)
        assertFalse(second.remoteChanged)
        assertFalse(DeviceSyncMerge.hasLocalChanges(base, local))
        // An older time on this side than in the base is not "starred again".
        assertFalse(DeviceSyncMerge.hasLocalChanges(favs(chrono to 10, zelda to 30), favs(chrono to 10, zelda to 20)))
    }

    @Test fun `three devices converge`() {
        // A and B synced once; then A removes Chrono, B adds Metroid, C (new) adds Tetris.
        val s1 = favs(chrono to 10, zelda to 20)
        val a = DeviceSyncMerge.merge(s1, favs(zelda to 20), s1)
        val s2 = a.merged
        val b = DeviceSyncMerge.merge(s1, favs(chrono to 10, zelda to 20, metroid to 40), s2)
        val s3 = b.merged
        assertEquals(setOf(zelda, metroid), s3.favourites.keys)
        assertEquals(setOf(chrono), b.toLocal.favouritesRemoved)
        val c = DeviceSyncMerge.merge(null, favs(tetris to 50), s3)
        assertEquals(setOf(zelda, metroid, tetris), c.merged.favourites.keys)
        // A syncs again and gets everything.
        val a2 = DeviceSyncMerge.merge(s2, favs(zelda to 20), c.merged)
        assertEquals(setOf(metroid, tetris), a2.toLocal.favouritesAdded.keys)
        assertTrue(a2.toLocal.favouritesRemoved.isEmpty())
    }

    // ---- Wishlist ------------------------------------------------------------------------------

    @Test fun `wishes merge by console and squashed title`() {
        val mother = SyncWish("Mother 3", "gba", 10)
        val key = DeviceSyncMerge.wishKey("Mother 3", "gba")
        assertEquals(key, DeviceSyncMerge.wishKey("  MOTHER-3 ", "gba"))
        assertEquals("*|mother3", DeviceSyncMerge.wishKey("Mother 3", null))
        val anyConsole = SyncWish("Mother 3", null, 15)
        val local = SyncLibrary(wishlist = mapOf(key to mother))
        val remote = SyncLibrary(wishlist = mapOf(key to mother.copy(title = "MOTHER 3", addedAt = 12), DeviceSyncMerge.wishKey("Mother 3", null) to anyConsole))
        val r = DeviceSyncMerge.merge(null, local, remote)
        assertEquals(2, r.merged.wishlist.size)
        assertEquals("Mother 3", r.merged.wishlist[key]?.title)   // this device's spelling
        assertEquals(12L, r.merged.wishlist[key]?.addedAt)
        assertEquals(1, r.toLocal.wishesAdded.size)
    }

    @Test fun `a wish removed on one side goes`() {
        val key = DeviceSyncMerge.wishKey("Mother 3", "gba")
        val lib = SyncLibrary(wishlist = mapOf(key to SyncWish("Mother 3", "gba", 10)))
        val r = DeviceSyncMerge.merge(lib, SyncLibrary.EMPTY, lib)
        assertTrue(r.merged.wishlist.isEmpty())
        assertEquals(setOf(key), r.toRemote.wishesRemoved)
    }

    // ---- Collections ---------------------------------------------------------------------------

    private fun coll(name: String, created: Long, vararg items: Pair<String, Long>) =
        DeviceSyncMerge.collectionKey(name) to SyncCollection(name, created, mapOf(*items))

    private fun colls(vararg c: Pair<String, SyncCollection>) = SyncLibrary(collections = mapOf(*c))

    @Test fun `games in a collection merge three-way`() {
        val base = colls(coll("Couch co-op", 1, chrono to 10, zelda to 10))
        val local = colls(coll("Couch co-op", 1, chrono to 10, zelda to 10, metroid to 30))   // added Metroid
        val remote = colls(coll("Couch co-op", 1, chrono to 10))                              // removed Zelda
        val r = DeviceSyncMerge.merge(base, local, remote)
        assertEquals(setOf(chrono, metroid), r.merged.collections["couch co-op"]?.items?.keys)
        assertEquals(mapOf("couch co-op" to setOf(zelda)), r.toLocal.itemsRemoved)
        assertEquals(mapOf("couch co-op" to mapOf(metroid to 30L)), r.toRemote.itemsAdded)
        assertEquals(1, r.toLocal.removals)
    }

    @Test fun `a new collection travels with its games`() {
        val r = DeviceSyncMerge.merge(colls(), colls(), colls(coll("RPG", 5, chrono to 6)))
        val added = r.toLocal.collectionsAdded["rpg"]
        assertEquals("RPG", added?.name)
        assertEquals(setOf(chrono), added?.items?.keys)
    }

    @Test fun `a collection deleted on one side goes when the other did not touch it`() {
        val base = colls(coll("RPG", 5, chrono to 6))
        val r = DeviceSyncMerge.merge(base, colls(), base)
        assertNull(r.merged.collections["rpg"])
        assertEquals(setOf("rpg"), r.toRemote.collectionsRemoved)
        // Counted as one removal, not one per game.
        assertEquals(1, r.removals)
    }

    @Test fun `a collection the other side added games to is kept`() {
        val base = colls(coll("RPG", 5, chrono to 6))
        val remote = colls(coll("RPG", 5, chrono to 6, zelda to 70))
        val r = DeviceSyncMerge.merge(base, colls(), remote)
        assertEquals(setOf(chrono, zelda), r.merged.collections["rpg"]?.items?.keys)
        assertEquals(setOf("rpg"), r.toLocal.collectionsAdded.keys)
    }

    @Test fun `a renamed collection replaces the old name`() {
        val base = colls(coll("RPG", 5, chrono to 6))
        val local = colls(coll("Role-playing", 5, chrono to 6))
        val r = DeviceSyncMerge.merge(base, local, base)
        assertEquals(setOf("role-playing"), r.merged.collections.keys)
        assertEquals(setOf("rpg"), r.toRemote.collectionsRemoved)
        assertEquals(setOf("role-playing"), r.toRemote.collectionsAdded.keys)
    }

    @Test fun `collections with the same name in another case are one`() {
        val r = DeviceSyncMerge.merge(null, colls(coll("rpg", 5, chrono to 6)), colls(coll("RPG", 3, zelda to 4)))
        assertEquals(1, r.merged.collections.size)
        assertEquals("rpg", r.merged.collections["rpg"]?.name)
        assertEquals(setOf(chrono, zelda), r.merged.collections["rpg"]?.items?.keys)
        assertEquals(5L, r.merged.collections["rpg"]?.createdAt)
    }

    // ---- Guard ---------------------------------------------------------------------------------

    @Test fun `an emptied side is held back`() {
        val many = SyncLibrary(favourites = (1..40).associate { DeviceSyncMerge.itemKey("snes", "Game $it.sfc") to it.toLong() })
        val wiped = DeviceSyncMerge.merge(many, SyncLibrary.EMPTY, many)
        assertEquals(40, wiped.removals)
        assertTrue(DeviceSyncMerge.tooManyRemovals(wiped, many))
        // A few removals are normal.
        val few = DeviceSyncMerge.merge(many, SyncLibrary(favourites = many.favourites.entries.drop(5).associate { it.key to it.value }), many)
        assertFalse(DeviceSyncMerge.tooManyRemovals(few, many))
        // Never without a base.
        assertFalse(DeviceSyncMerge.tooManyRemovals(DeviceSyncMerge.merge(null, SyncLibrary.EMPTY, many), null))
    }

    @Test fun `local changes are noticed, including starred again`() {
        val base = favs(chrono to 10)
        assertTrue(DeviceSyncMerge.hasLocalChanges(null, SyncLibrary.EMPTY))
        assertFalse(DeviceSyncMerge.hasLocalChanges(base, favs(chrono to 10)))
        assertTrue(DeviceSyncMerge.hasLocalChanges(base, favs(chrono to 11)))
        assertTrue(DeviceSyncMerge.hasLocalChanges(base, favs()))
        val withColl = colls(coll("RPG", 5, chrono to 6))
        assertTrue(DeviceSyncMerge.hasLocalChanges(withColl, colls(coll("RPG", 5, chrono to 9))))
        assertFalse(DeviceSyncMerge.hasLocalChanges(withColl, colls(coll("RPG", 5, chrono to 6))))
    }

    @Test fun `item keys split at the first bar`() {
        assertEquals("snes" to "a|b.sfc", DeviceSyncMerge.splitItemKey("snes|a|b.sfc"))
        assertNull(DeviceSyncMerge.splitItemKey("|x"))
        assertNull(DeviceSyncMerge.splitItemKey("snes|"))
        assertNull(DeviceSyncMerge.splitItemKey("nobar"))
    }
}
