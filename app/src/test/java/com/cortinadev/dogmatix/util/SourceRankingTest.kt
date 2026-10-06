package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRankingTest {
    private val now = 1_000_000_000L
    private val mb = 1024L * 1024

    private fun rec(ok: Int, failed: Int, speedMb: Double = 0.0, streak: Int = 0, failedAt: Long = 0L) =
        SourceRecord(bytesPerSec = speedMb * mb, successes = ok, failures = failed, failureStreak = streak, lastFailureAt = failedAt)

    private fun rank(sources: List<String>, records: Map<String, SourceRecord>, order: List<String> = sources) =
        SourceRanking.rank(sources, { it }, records, order, now)

    @Test fun `reliability comes before speed`() {
        val records = mapOf("a" to rec(5, 5, speedMb = 50.0), "b" to rec(10, 0, speedMb = 2.0))
        assertEquals(listOf("b", "a"), rank(listOf("a", "b"), records))
    }

    @Test fun `speed decides between equally reliable sources`() {
        val records = mapOf("a" to rec(20, 0, speedMb = 3.0), "b" to rec(19, 0, speedMb = 12.0))
        assertEquals(listOf("b", "a"), rank(listOf("a", "b"), records))
    }

    @Test fun `the order of Sources breaks ties`() {
        assertEquals(listOf("b", "a"), rank(listOf("a", "b"), emptyMap(), order = listOf("b", "a")))
        // Unknown to Sources goes after the listed ones.
        assertEquals(listOf("a", "x"), rank(listOf("x", "a"), emptyMap(), order = listOf("a")))
    }

    @Test fun `a source failing again and again is set aside for a while`() {
        val failing = rec(30, 2, speedMb = 40.0, streak = 2, failedAt = now - 60_000)
        val records = mapOf("a" to failing, "b" to rec(0, 0))
        assertEquals(listOf("b", "a"), rank(listOf("a", "b"), records))
        assertTrue(SourceRanking.isDemoted(failing, now))
        // Long enough after the last failure it counts normally again.
        val later = now + SourceRanking.DEMOTE_MS
        assertFalse(SourceRanking.isDemoted(failing, later))
        assertEquals(listOf("a", "b"), SourceRanking.rank(listOf("a", "b"), { it }, records, listOf("a", "b"), later))
        // One failure is not enough.
        assertFalse(SourceRanking.isDemoted(failing.copy(failureStreak = 1), now))
    }

    @Test fun `an unknown source beats a bad one and loses to a good one`() {
        val records = mapOf("bad" to rec(1, 4), "good" to rec(5, 0))
        assertEquals(listOf("good", "new", "bad"), rank(listOf("bad", "new", "good"), records))
    }

    @Test fun `success updates the weighted speed and clears the streak`() {
        var r = SourceRanking.withFailure(SourceRecord(), FailureClass.SERVER, now)
        assertEquals(1, r.failureStreak)
        assertEquals(FailureClass.SERVER, r.lastFailure)
        r = SourceRanking.withSuccess(r, 100 * mb, 10_000, now) // 10 MB/s
        assertEquals(10.0 * mb, r.bytesPerSec, 1.0)
        assertEquals(0, r.failureStreak)
        r = SourceRanking.withSuccess(r, 200 * mb, 10_000, now) // 20 MB/s
        assertEquals((10.0 + 0.3 * 10.0) * mb, r.bytesPerSec, 1.0)
        // A tiny file says nothing about the speed but still counts as a success.
        val tiny = SourceRanking.withSuccess(r, 1000, 10, now)
        assertEquals(r.bytesPerSec, tiny.bytesPerSec, 0.0)
        assertEquals(3, tiny.successes)
        assertEquals(75, tiny.okPercent)
    }

    @Test fun `old counts fade`() {
        val r = SourceRanking.withSuccess(SourceRecord(successes = 60, failures = 40), 0, 0, now)
        assertTrue(r.total <= SourceRanking.MAX_COUNT)
        assertEquals(31, r.successes)
        assertEquals(20, r.failures)
    }

    @Test fun `failure classes`() {
        assertEquals(FailureClass.NOT_FOUND, FailureClass.of(404, false))
        assertEquals(FailureClass.FORBIDDEN, FailureClass.of(403, false))
        assertEquals(FailureClass.SERVER, FailureClass.of(503, false))
        assertEquals(FailureClass.SERVER, FailureClass.of(429, false))
        assertEquals(FailureClass.NETWORK, FailureClass.of(null, true))
        assertEquals(FailureClass.OTHER, FailureClass.of(null, false))
    }

    private fun copy(name: String, title: String = "Game", tags: List<String> = listOf("USA"), size: Long = 100 * mb, console: String = "snes") =
        SourceRanking.Copy(console, name, title, tags, size)

    @Test fun `same game means same file or same title tags and type, never another version`() {
        val a = copy("Game (USA).zip")
        assertTrue(SourceRanking.sameGame(a, copy("game (usa).zip")))
        assertTrue(SourceRanking.sameGame(a, copy("Game  (USA) .zip", title = "game")))
        // Rounded sizes in a listing still match.
        assertTrue(SourceRanking.sameGame(a, copy("Game (USA).zip", size = 102 * mb)))
        assertTrue(SourceRanking.sameGame(a, copy("Game (USA).zip", size = 0)))
        assertFalse(SourceRanking.sameGame(a, copy("Game (USA).zip", size = 150 * mb)))
        assertFalse(SourceRanking.sameGame(a, copy("Game (Europe).zip", tags = listOf("Europe"))))
        assertFalse(SourceRanking.sameGame(a, copy("Game (USA) (Rev 1).zip", tags = listOf("USA", "Rev 1"))))
        assertFalse(SourceRanking.sameGame(a, copy("Game (USA).7z")))
        assertFalse(SourceRanking.sameGame(a, copy("Game (USA).zip", console = "nes")))
    }

    @Test fun `labels for the status line`() {
        assertEquals("myrient.erista.me", SourceRanking.label("https://myrient.erista.me/files/No-Intro/"))
        assertEquals("example.org", SourceRanking.label("http://www.example.org/roms"))
        assertEquals("RomM", SourceRanking.label("romm://snes"))
        assertEquals("My Set", SourceRanking.label("magnet:?xt=urn:btih:abc&dn=My%20Set"))
        assertEquals("set.torrent", SourceRanking.label("https://example.org/set.torrent"))
    }

    @Test fun `speed text`() {
        assertNull(SourceRanking.speedText(0.0))
        assertEquals("12 MB/s", SourceRanking.speedText(12.0 * mb))
        assertEquals("2.5 MB/s", SourceRanking.speedText(2.5 * mb))
        assertEquals("300 KB/s", SourceRanking.speedText(300.0 * 1024))
    }

    @Test fun `json keeps every field`() {
        val records = mapOf(
            "https://a.org/x" to SourceRecord(1234.5, 3, 1, 10L, 20L, 1, FailureClass.NOT_FOUND),
            "romm://snes" to SourceRecord()
        )
        assertEquals(records, SourceRanking.fromJson(SourceRanking.toJson(records)))
        assertEquals(emptyMap<String, SourceRecord>(), SourceRanking.fromJson("not json"))
    }
}
