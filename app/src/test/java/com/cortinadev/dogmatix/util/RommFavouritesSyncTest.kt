package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommFavouritesSyncTest {

    @Test fun `the first sync only adds, on both sides`() {
        val plan = RommFavouritesSync.plan(local = setOf(1, 2), remote = setOf(2, 3), base = null, known = setOf(1, 2, 3))
        assertEquals(setOf(1, 2, 3), plan.remote)
        assertEquals(setOf(3), plan.addLocal)
        assertTrue(plan.removeLocal.isEmpty())
        assertTrue(plan.pushNeeded)
        assertTrue(plan.additive)
        assertEquals(setOf(1, 2, 3), plan.base)
    }

    @Test fun `a new star here becomes a heart`() {
        val plan = RommFavouritesSync.plan(local = setOf(1, 2), remote = setOf(1), base = setOf(1), known = setOf(1, 2))
        assertEquals(setOf(1, 2), plan.remote)
        assertTrue(plan.pushNeeded)
        assertTrue(plan.addLocal.isEmpty() && plan.removeLocal.isEmpty())
        assertFalse(plan.additive)
    }

    @Test fun `a new heart on RomM becomes a star`() {
        val plan = RommFavouritesSync.plan(local = setOf(1), remote = setOf(1, 5), base = setOf(1), known = setOf(1, 5))
        assertEquals(setOf(5), plan.addLocal)
        assertFalse(plan.pushNeeded)
    }

    @Test fun `a star taken away here takes the heart away`() {
        val plan = RommFavouritesSync.plan(local = setOf(1, 2), remote = setOf(1, 2, 3), base = setOf(1, 2, 3), known = setOf(1, 2, 3))
        assertEquals(setOf(1, 2), plan.remote)
        assertTrue(plan.pushNeeded)
        assertTrue(plan.removeLocal.isEmpty())
        assertEquals(setOf(1, 2), plan.base)
    }

    @Test fun `a heart taken away on RomM takes the star away`() {
        val plan = RommFavouritesSync.plan(local = setOf(1, 2, 3), remote = setOf(1, 2), base = setOf(1, 2, 3), known = setOf(1, 2, 3))
        assertEquals(setOf(3), plan.removeLocal)
        assertFalse(plan.pushNeeded)
        assertEquals(setOf(1, 2), plan.base)
    }

    @Test fun `hearts of games this device cannot place stay on RomM`() {
        // 9 is hearted on RomM but not in this library: never removed, never starred.
        val plan = RommFavouritesSync.plan(local = setOf(1), remote = setOf(1, 9), base = setOf(1, 9), known = setOf(1))
        assertEquals(setOf(1, 9), plan.remote)
        assertTrue(plan.addLocal.isEmpty())
        assertFalse(plan.pushNeeded)
        assertTrue("the agreement about 9 is kept for when the game shows up", 9 in plan.base)
    }

    @Test fun `an emptied collection does not wipe the stars`() {
        val plan = RommFavouritesSync.plan(local = setOf(1, 2, 3, 4), remote = emptySet(), base = setOf(1, 2, 3, 4), known = setOf(1, 2, 3, 4))
        assertTrue(plan.additive)
        assertTrue(plan.removeLocal.isEmpty())
        assertEquals(setOf(1, 2, 3, 4), plan.remote)
    }

    @Test fun `a device without stars does not wipe the hearts`() {
        val plan = RommFavouritesSync.plan(local = emptySet(), remote = setOf(1, 2, 3), base = setOf(1, 2, 3), known = setOf(1, 2, 3))
        assertTrue(plan.additive)
        assertEquals(setOf(1, 2, 3), plan.remote)
        assertEquals(setOf(1, 2, 3), plan.addLocal)
        assertFalse(plan.pushNeeded)
    }

    @Test fun `removing one or two everywhere is still a removal`() {
        val plan = RommFavouritesSync.plan(local = emptySet(), remote = setOf(1, 2), base = setOf(1, 2), known = setOf(1, 2))
        assertFalse(plan.additive)
        assertTrue(plan.remote.isEmpty())
        assertTrue(plan.pushNeeded)
    }

    @Test fun `nothing to do when both sides agree`() {
        val plan = RommFavouritesSync.plan(local = setOf(4, 5), remote = setOf(4, 5, 6), base = setOf(4, 5), known = setOf(4, 5))
        assertFalse(plan.pushNeeded)
        assertTrue(plan.addLocal.isEmpty() && plan.removeLocal.isEmpty())
    }

    @Test fun `the favourites collection is the flagged one of this account, else one named so`() {
        val mine = 1
        val cols = listOf(
            RommFavouritesSync.CollectionRef(10, "Favourites", userId = 2, isFavourite = true),
            RommFavouritesSync.CollectionRef(11, "RPGs", userId = 1, isFavourite = false),
            RommFavouritesSync.CollectionRef(12, "Favorites", userId = 1, isFavourite = false),
            RommFavouritesSync.CollectionRef(13, "Hearts", userId = 1, isFavourite = true)
        )
        assertEquals(13, RommFavouritesSync.pickCollection(cols, mine)?.id)
        assertEquals(12, RommFavouritesSync.pickCollection(cols.filter { it.id != 13 }, mine)?.id)
        assertNull("another user's collection is never taken", RommFavouritesSync.pickCollection(cols.filter { it.id == 10 || it.id == 11 }, mine))
        assertEquals(10, RommFavouritesSync.pickCollection(cols.filter { it.id == 10 }, null)?.id)
    }

    @Test fun `a collection that counts more games than it lists cannot be trusted`() {
        assertTrue(RommFavouritesSync.isReadable(listOf(1, 2), 2))
        assertTrue(RommFavouritesSync.isReadable(emptyList(), null))
        assertFalse(RommFavouritesSync.isReadable(emptyList(), 14))
    }
}
