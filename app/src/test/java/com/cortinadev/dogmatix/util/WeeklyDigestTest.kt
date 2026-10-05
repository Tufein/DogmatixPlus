package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyDigestTest {

    private val day = 24L * 3_600_000
    private val now = 1_800_000_000_000L

    private fun file(console: String, name: String) = DigestFile(console, name)
    private fun build(
        files: List<DigestFile> = emptyList(),
        owned: Set<String> = setOf("snes", "gba"),
        wishes: List<DigestWish> = emptyList(),
        goals: List<DigestGoal> = emptyList(),
        downloads: List<DownloadLogEntry> = emptyList()
    ) = WeeklyDigest.build(files, owned, wishes, goals, downloads, now)

    // ---- schedule ------------------------------------------------------------------------------

    @Test fun `due the first time and after five days`() {
        assertTrue(WeeklyDigest.isDue(now, 0))
        assertTrue(WeeklyDigest.isDue(now, now - 7 * day))
        assertTrue(WeeklyDigest.isDue(now, now - 5 * day))
        assertFalse(WeeklyDigest.isDue(now, now - 4 * day))
        assertFalse(WeeklyDigest.isDue(now, now - 1000))
    }

    @Test fun `a clock set back does not block the digest for ever`() {
        assertTrue(WeeklyDigest.isDue(now, now + 30 * day))
    }

    @Test fun `the week starts seven days ago`() {
        assertEquals(now - 7 * day, WeeklyDigest.since(now))
    }

    // ---- consoles you have games for -------------------------------------------------------------

    @Test fun `a console counts when a folder of its name holds games`() {
        val keys = setOf("snes|super mario world (usa).sfc", "snes|super mario world (usa)", "gba|metroid fusion.gba")
        assertEquals(setOf("snes", "gba"), WeeklyDigest.consolesWithGames(listOf("snes", "gba", "n64"), keys))
    }

    @Test fun `a custom folder and a fresh download count too`() {
        val keys = setOf("custom:n64|mario kart 64.z64", "console:psx|crash bandicoot.chd")
        assertEquals(setOf("n64", "psx"), WeeklyDigest.consolesWithGames(listOf("n64", "psx", "snes"), keys))
    }

    @Test fun `loose files in the download root count for no console`() {
        assertTrue(WeeklyDigest.consolesWithGames(listOf("snes"), setOf("|tetris.gb")).isEmpty())
    }

    @Test fun `an empty index gives no consoles`() {
        assertTrue(WeeklyDigest.consolesWithGames(listOf("snes"), emptySet()).isEmpty())
    }

    // ---- new games -------------------------------------------------------------------------------

    @Test fun `only consoles you have games for count and versions count once`() {
        val s = build(
            files = listOf(
                file("snes", "Chrono Trigger (USA).zip"), file("snes", "Chrono Trigger (Europe).zip"), file("snes", "EarthBound (USA).zip"),
                file("gba", "Metroid Fusion (USA).zip"), file("n64", "Mario Kart 64 (USA).zip")
            )
        )
        assertEquals(4 - 1, s.newGames)
        assertEquals(listOf(DigestConsole("snes", 2), DigestConsole("gba", 1)), s.topConsoles)
    }

    @Test fun `the top consoles are the busiest three`() {
        val s = build(
            owned = setOf("a", "b", "c", "d"),
            files = listOf(
                file("a", "One (USA).zip"), file("b", "Two (USA).zip"), file("b", "Three (USA).zip"),
                file("c", "Four (USA).zip"), file("c", "Five (USA).zip"), file("c", "Six (USA).zip"), file("d", "Seven (USA).zip")
            )
        )
        assertEquals(listOf("c", "b", "a"), s.topConsoles.map { it.consoleId })
        assertEquals(7, s.newGames)
    }

    @Test fun `no files and nothing else is no news`() {
        val s = build()
        assertEquals(0, s.newGames)
        assertFalse(s.hasNews)
    }

    // ---- wishlist --------------------------------------------------------------------------------

    @Test fun `a wish matches a new file with all its words`() {
        val s = build(
            files = listOf(file("snes", "Chrono Trigger (USA).zip"), file("snes", "Secret of Mana (Europe).zip")),
            wishes = listOf(DigestWish("Chrono Trigger", null), DigestWish("Secret of Evermore", null), DigestWish("Chrono Cross", "psx"))
        )
        assertEquals(listOf("Chrono Trigger"), s.wishlistHits)
    }

    @Test fun `a wish for one console needs a file of that console`() {
        val files = listOf(file("snes", "Chrono Trigger (USA).zip"))
        assertTrue(WeeklyDigest.wishlistHits(listOf(DigestWish("Chrono Trigger", "gba")), files).isEmpty())
        assertEquals(listOf("Chrono Trigger"), WeeklyDigest.wishlistHits(listOf(DigestWish("Chrono Trigger", "snes")), files))
    }

    @Test fun `a wish is matched in consoles you have no games for`() {
        val s = build(owned = setOf("snes"), files = listOf(file("saturn", "Panzer Dragoon Saga (USA).zip")), wishes = listOf(DigestWish("Panzer Dragoon Saga", null)))
        assertEquals(0, s.newGames)
        assertEquals(listOf("Panzer Dragoon Saga"), s.wishlistHits)
        assertTrue(s.hasNews)
    }

    @Test fun `the same wish twice is listed once`() {
        val hits = WeeklyDigest.wishlistHits(
            listOf(DigestWish("Chrono Trigger", "snes"), DigestWish("chrono trigger ", null)),
            listOf(file("snes", "Chrono Trigger (USA).zip"))
        )
        assertEquals(1, hits.size)
    }

    @Test fun `blank wishes never match`() {
        assertTrue(WeeklyDigest.wishlistHits(listOf(DigestWish("  ", null)), listOf(file("snes", "Anything.zip"))).isEmpty())
    }

    // ---- goals -----------------------------------------------------------------------------------

    @Test fun `the goals nearest to done come first and complete ones are only counted`() {
        val s = build(
            files = listOf(file("snes", "Chrono Trigger (USA).zip")),
            goals = listOf(
                DigestGoal("gba", "Game Boy Advance", 40, 100), DigestGoal("snes", "Super Nintendo", 85, 100),
                DigestGoal("nes", "NES", 100, 100), DigestGoal("n64", "Nintendo 64", 10, 50), DigestGoal("gb", "Game Boy", 60, 100),
                DigestGoal("sms", "Master System", 0, 0)
            )
        )
        assertEquals(listOf("snes", "gb", "gba"), s.goals.map { it.consoleId })
        assertEquals(1, s.completedGoals)
        assertEquals(85, s.goals.first().percent)
    }

    @Test fun `goal progress alone is not news`() {
        val s = build(goals = listOf(DigestGoal("snes", "Super Nintendo", 85, 100)))
        assertFalse(s.hasNews)
        assertEquals(1, s.goals.size)
    }

    // ---- downloads -------------------------------------------------------------------------------

    @Test fun `downloads of the last week are counted with their size`() {
        val s = build(
            downloads = listOf(
                DownloadLogEntry(now - 8 * day, "snes", 500), DownloadLogEntry(now - 6 * day, "snes", 1_000),
                DownloadLogEntry(now - 1000, "gba", 2_000), DownloadLogEntry(now + day, "gba", 9_000)
            )
        )
        assertEquals(2, s.downloads)
        assertEquals(3_000L, s.downloadedBytes)
        assertTrue(s.hasNews)
    }

    @Test fun `a quiet week with only downloads still has news`() {
        assertTrue(build(downloads = listOf(DownloadLogEntry(now - day, "snes", 1))).hasNews)
    }
}
