package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.OfflineCollections.Fetched
import com.cortinadev.dogmatix.util.OfflineCollections.Game
import com.cortinadev.dogmatix.util.OfflineCollections.Kept
import com.cortinadev.dogmatix.util.OfflineCollections.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCollectionsTest {

    private val mb = 1024L * 1024
    private fun g(name: String, console: String = "snes") = Game(console, name)

    private fun plan(
        kept: List<Kept>,
        offers: Map<Game, Offer>,
        have: Set<Game> = emptySet(),
        queue: Set<Game> = emptySet(),
        free: Long? = null,
        reserve: Long = 0,
        cap: Int = 25
    ) = OfflineCollections.plan(kept, offers, { it in have }, { it in queue }, free, reserve, cap)

    private fun offers(vararg games: Game, size: Long = 10 * mb) = games.associateWith { Offer(size, false) }

    @Test fun `only games in the library and not on the device are picked`() {
        val a = g("A.sfc"); val b = g("B.sfc"); val c = g("C.sfc"); val d = g("D.sfc")
        val p = plan(listOf(Kept(1, "Co-op", listOf(a, b, c, d))), offers(a, b, d), have = setOf(a), queue = setOf(b))
        assertEquals(listOf(d), p.picks.map { it.game })
        val t = p.tallies.single()
        assertEquals(4, t.total)
        assertEquals(1, t.onDevice)
        assertEquals(1, t.queued)
        assertEquals(1, t.notInLibrary)
        assertEquals(1, t.fetch)
        assertEquals(2, t.missing)
    }

    @Test fun `a cap holds the rest back for the next run`() {
        val games = (1..10).map { g("G$it.sfc") }
        val p = plan(listOf(Kept(1, "Big", games)), offers(*games.toTypedArray()), cap = 4)
        assertEquals(games.take(4), p.picks.map { it.game })
        assertEquals(6, p.overCap)
        assertEquals(6, p.tallies.single().overCap)
        assertEquals(0, p.noSpace)
    }

    @Test fun `a game in two collections is fetched once and remembers both`() {
        val a = g("A.sfc"); val b = g("B.sfc")
        val p = plan(listOf(Kept(1, "One", listOf(a, b)), Kept(2, "Two", listOf(b))), offers(a, b))
        assertEquals(2, p.picks.size)
        assertEquals(listOf(1L, 2L), p.picks.first { it.game == b }.collectionIds)
        assertEquals(1, p.tallies[1].fetch)
    }

    @Test fun `space is checked with a margin, the reserve and room to unpack`() {
        val a = g("A.zip"); val b = g("B.zip"); val c = g("C.sfc")
        val offers = mapOf(a to Offer(300 * mb, true), b to Offer(300 * mb, true), c to Offer(50 * mb, false))
        // 1 GB free, 100 MB margin, 200 MB reserved: 724 MB. A needs 600, B would need 600 more, C 50 fits after A.
        val p = plan(listOf(Kept(1, "K", listOf(a, b, c))), offers, free = 1024 * mb, reserve = 200 * mb)
        assertEquals(listOf(a, c), p.picks.map { it.game })
        assertEquals(1, p.noSpace)
        assertEquals(600 * mb, p.missingBytes)
        assertEquals(1, p.tallies.single().noSpace)
    }

    @Test fun `unknown free space does not block and an unknown size costs nothing`() {
        val a = g("A.sfc")
        val p = plan(listOf(Kept(1, "K", listOf(a))), mapOf(a to Offer(0, false)), free = null)
        assertEquals(1, p.picks.size)
        assertEquals(0L, p.picks.single().need)
        assertEquals(0, p.noSpace)
    }

    @Test fun `a collection with nothing to do plans nothing`() {
        val a = g("A.sfc")
        val p = plan(listOf(Kept(1, "Done", listOf(a))), offers(a), have = setOf(a))
        assertTrue(p.picks.isEmpty())
        assertEquals(0, p.tallies.single().missing)
        assertTrue(plan(emptyList(), emptyMap()).tallies.isEmpty())
    }

    @Test fun `a zero cap picks nothing`() {
        val a = g("A.sfc")
        val p = plan(listOf(Kept(1, "K", listOf(a))), offers(a), cap = 0)
        assertTrue(p.picks.isEmpty())
        assertEquals(1, p.overCap)
    }

    // ---- review ----

    private fun review(
        fetched: List<Fetched>,
        contents: Map<Long, Set<Game>>,
        kept: Set<Long>,
        have: Set<Game> = emptySet(),
        queue: Set<Game> = emptySet()
    ) = OfflineCollections.review(fetched, contents, kept, { it in have }, { it in queue })

    @Test fun `a fetched game that left its collection and is on the device is listed`() {
        val a = g("A.sfc"); val b = g("B.sfc")
        val r = review(
            listOf(Fetched(1, a), Fetched(1, b)),
            contents = mapOf(1L to setOf(b)), kept = setOf(1L), have = setOf(a, b)
        )
        assertEquals(listOf(a), r.stale.map { it.game })
        assertEquals(setOf(1L), r.stale.single().collectionIds)
        assertTrue(r.forget.isEmpty())
    }

    @Test fun `a game another kept collection still holds is not stale`() {
        val a = g("A.sfc")
        val r = review(
            listOf(Fetched(1, a)),
            contents = mapOf(1L to emptySet(), 2L to setOf(a)), kept = setOf(1L, 2L), have = setOf(a)
        )
        assertTrue(r.stale.isEmpty())
        assertTrue(r.forget.isEmpty())
    }

    @Test fun `a game of a collection that is not switched on is not wanted`() {
        val a = g("A.sfc")
        val r = review(listOf(Fetched(1, a)), contents = mapOf(2L to setOf(a)), kept = setOf(1L), have = setOf(a))
        assertEquals(listOf(a), r.stale.map { it.game })
    }

    @Test fun `a deleted collection leaves its games for review`() {
        val a = g("A.sfc")
        val r = review(listOf(Fetched(7, a)), contents = emptyMap(), kept = emptySet(), have = setOf(a))
        assertEquals(listOf(a), r.stale.map { it.game })
    }

    @Test fun `a game that left and is gone is forgotten, one still downloading is left alone`() {
        val gone = g("Gone.sfc"); val coming = g("Coming.sfc")
        val r = review(
            listOf(Fetched(1, gone), Fetched(1, coming)),
            contents = mapOf(1L to emptySet()), kept = setOf(1L), queue = setOf(coming)
        )
        assertTrue(r.stale.isEmpty())
        assertEquals(listOf(Fetched(1, gone)), r.forget)
    }

    @Test fun `a game still in its collection is never touched, even when it is not on the device`() {
        val a = g("A.sfc")
        val r = review(listOf(Fetched(1, a)), contents = mapOf(1L to setOf(a)), kept = setOf(1L))
        assertTrue(r.stale.isEmpty())
        assertTrue(r.forget.isEmpty())
    }

    @Test fun `the review is sorted by console then name`() {
        val r = review(
            listOf(Fetched(1, g("b.gba", "gba")), Fetched(1, g("Z.sfc")), Fetched(1, g("a.sfc"))),
            contents = mapOf(1L to emptySet()), kept = setOf(1L), have = setOf(g("b.gba", "gba"), g("Z.sfc"), g("a.sfc"))
        )
        assertEquals(listOf("b.gba", "a.sfc", "Z.sfc"), r.stale.map { it.game.fileName })
    }

    // ---- files on disk ----

    private fun entry(base: String, vararg files: String, console: String? = "snes", scope: String = console ?: "") =
        GameEntry(scope, console, "/ROMs/$scope", base, files.map { diskFile(it, consoleId = console, scope = scope) })

    @Test fun `the files of a game are found by name or base name within its console`() {
        val own = entry("Chrono Trigger (USA)", "Chrono Trigger (USA).sfc")
        val disc = entry("Game", "Game.cue", "Game (Track 1).bin", "Game (Track 2).bin", console = "psx")
        val other = entry("Chrono Trigger (USA)", "Chrono Trigger (USA).sfc", console = "gba")
        val found = OfflineCollections.entriesOf(Game("snes", "Chrono%20Trigger%20(USA).zip"), listOf(own, disc, other))
        assertEquals(listOf(own), found)
        assertEquals(listOf(disc), OfflineCollections.entriesOf(Game("psx", "Game.cue"), listOf(own, disc, other)))
        assertTrue(OfflineCollections.entriesOf(Game("snes", "Nothing.sfc"), listOf(own)).isEmpty())
    }

    @Test fun `loose files in the download folder count for any console`() {
        val loose = entry("Mario", "Mario.sfc", console = null, scope = "")
        assertEquals(listOf(loose), OfflineCollections.entriesOf(Game("snes", "Mario.sfc"), listOf(loose)))
    }

    @Test fun `a loose file matches by its exact name only`() {
        val sibling = entry("Mario", "Mario.sfc.bak", "Mario.srm", console = null, scope = "")
        val folder = entry("Mario", "Mario/Disc.bin", console = null, scope = "")
        assertTrue(OfflineCollections.entriesOf(Game("snes", "Mario.sfc"), listOf(sibling, folder)).isEmpty())
    }

    // ---- records ----

    @Test fun `a run record survives a round trip`() {
        val run = OfflineCollections.RunInfo(1_700_000_000_000, 12, 3, 40, 5_000_000_000)
        assertEquals(run, OfflineCollections.RunInfo.decode(run.encode()))
        assertNull(OfflineCollections.RunInfo.decode("junk"))
        assertNull(OfflineCollections.RunInfo.decode(null))
    }

    @Test fun `a fetched entry survives a round trip, even with a bar in the file name`() {
        val f = Fetched(42, Game("snes", "Odd|Name (USA).sfc"))
        assertEquals(f, OfflineCollections.decodeFetched(OfflineCollections.encode(f)))
        assertNull(OfflineCollections.decodeFetched("x|snes|a.sfc"))
        assertNull(OfflineCollections.decodeFetched("1|snes"))
        assertNull(OfflineCollections.decodeFetched("1||a.sfc"))
    }

    @Test fun `the cap stays in range`() {
        assertEquals(OfflineCollections.MIN_CAP, OfflineCollections.clampCap(0))
        assertEquals(OfflineCollections.MAX_CAP, OfflineCollections.clampCap(10_000))
        assertEquals(30, OfflineCollections.clampCap(30))
    }
}
