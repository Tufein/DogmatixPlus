package com.cortinadev.dogmatix.util

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V30HelpersTest {

    @Test fun `resume appends only when the server continues exactly at the partial file`() {
        assertEquals(ResumePlan.ContentRange(100, 999, 1000), ResumePlan.parseContentRange("bytes 100-999/1000"))
        assertEquals(ResumePlan.ContentRange(5, 9, null), ResumePlan.parseContentRange("bytes 5-9/*"))
        assertNull(ResumePlan.parseContentRange("bytes */1000"))
        assertEquals(ResumePlan.Action.APPEND, ResumePlan.decide(100, 206, "bytes 100-999/1000"))
        assertEquals(ResumePlan.Action.RESTART, ResumePlan.decide(100, 200, null))               // server ignored the range
        assertEquals(ResumePlan.Action.RESTART, ResumePlan.decide(100, 206, "bytes 0-999/1000")) // wrong start
        assertEquals(ResumePlan.Action.RESTART, ResumePlan.decide(100, 206, "bytes 100-999/1000", expectedTotal = 2000)) // another file
        assertEquals(ResumePlan.Action.RESTART, ResumePlan.decide(0, 206, "bytes 0-9/10"))
        assertEquals("\"abc\"", ResumePlan.validator("\"abc\"", "Mon"))
        assertEquals("Mon, 01 Jan 2024", ResumePlan.validator("W/\"weak\"", "Mon, 01 Jan 2024"))
        assertNull(ResumePlan.validator(null, " "))
    }

    @Test fun `per-server limit lets downloads of other servers go first`() = runBlocking {
        val queue = DownloadQueue(slots = 3, perHost = 1)
        val order = mutableListOf<String>()
        queue.acquire("a1", "a.example")
        val a2 = async { queue.acquire("a2", "a.example"); order += "a2" }
        val b1 = async { queue.acquire("b1", "b.example"); order += "b1" }
        repeat(5) { yield() }
        assertEquals(listOf("b1"), order)               // a2 waits for a.example although it queued first
        assertEquals(listOf("a2"), queue.waiting.value)
        queue.release("a.example")
        a2.await(); b1.await()
        assertEquals(listOf("b1", "a2"), order)
        queue.setPerHost(0)
        val c = async { queue.acquire("a3", "a.example"); order += "a3" }
        c.await()
        assertEquals("a3", order.last())
    }

    @Test fun `retroachievements consoles and hashing rules`() {
        assertEquals(5, RetroAchievements.consoleFor("nintendo_gameboy_advance")?.id)
        assertEquals(6, RetroAchievements.consoleFor("gameboy_color")?.id)
        assertEquals(4, RetroAchievements.consoleFor("nintendo_gameboy")?.id)
        assertEquals(3, RetroAchievements.consoleFor("super_nintendo_entertainment_system")?.id)
        assertEquals(7, RetroAchievements.consoleFor("nintendo_entertainment_system")?.id)
        assertNull(RetroAchievements.consoleFor("sony_playstation"))

        val body = ByteArray(32768) { (it % 251).toByte() }
        val ines = byteArrayOf(0x4E, 0x45, 0x53, 0x1A) + ByteArray(12) + body
        assertEquals(RetroAchievements.md5(body), RetroAchievements.hash(ines, RetroAchievements.Rule.NES))
        val snesHeadered = ByteArray(512) + ByteArray(8192 * 4) { 7 }
        assertEquals(RetroAchievements.md5(ByteArray(8192 * 4) { 7 }), RetroAchievements.hash(snesHeadered, RetroAchievements.Rule.SNES))
        assertEquals(RetroAchievements.md5(body), RetroAchievements.hash(body, RetroAchievements.Rule.PLAIN))

        // N64: .v64 (byte-swapped) and .n64 (little-endian) hash like the .z64 they come from.
        val z64 = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40, 1, 2, 3, 4)
        val v64 = byteArrayOf(0x37, 0x80.toByte(), 0x40, 0x12, 2, 1, 4, 3)
        val n64 = byteArrayOf(0x40, 0x12, 0x37, 0x80.toByte(), 4, 3, 2, 1)
        val want = RetroAchievements.md5(z64)
        assertEquals(want, RetroAchievements.hash(z64, RetroAchievements.Rule.N64))
        assertEquals(want, RetroAchievements.hash(v64, RetroAchievements.Rule.N64))
        assertEquals(want, RetroAchievements.hash(n64, RetroAchievements.Rule.N64))

        val list = RetroAchievements.parseGameList("""[
            {"Title":"Pokemon Emerald Version","ID":515,"NumAchievements":76,"Hashes":["605B89B67018ABCEA91E693A4DD25BE3"]},
            {"Title":"No cheevos","ID":2,"NumAchievements":0,"Hashes":["aa"]}]""")
        assertEquals(1, list.size)
        assertEquals(setOf("605b89b67018abcea91e693a4dd25be3"), list[0].hashes)
        assertTrue(RetroAchievements.gameListUrl(5, "me", "k y").contains("i=5&h=1&f=1"))
    }

    @Test fun `profiles hide consoles and tags and guard the switch with a pin`() {
        val kid = Profile("k", "Kid", setOf("sony_playstation"), setOf("Adult", " Hack "))
        val back = Profiles.fromJson(Profiles.toJson(listOf(kid)))
        assertEquals(listOf(kid.copy(hiddenTags = setOf("Adult", " Hack "))), back)
        val r = Profiles.restrictionsOf(back, "k")
        assertTrue(r.active)
        assertEquals(setOf("Adult", "Hack"), r.hiddenTags)
        assertFalse(r.allows("sony_playstation", emptyList()))
        assertFalse(r.allows("gba", listOf("hack")))
        assertTrue(r.allows("gba", listOf("Europe")))
        assertFalse(Profiles.restrictionsOf(back, "").active)
        assertEquals(setOf("Adult", "Beta"), Profiles.parseTags("Adult, ;Beta ,"))
        val h = Profiles.pinHash("1234")
        assertTrue(Profiles.pinMatches("1234", h))
        assertFalse(Profiles.pinMatches("4321", h))
        assertTrue(Profiles.pinMatches("anything", ""))
    }

    @Test fun `es-de artwork paths and gamelist entries`() {
        assertEquals("downloaded_media/gba/covers/Game (Europe).png", EsdeArtwork.coverPath("gba", "Game (Europe).zip", "PNG"))
        assertEquals("webp", EsdeArtwork.imageExtension("https://x/y/cover.webp?size=2"))
        assertEquals("jpg", EsdeArtwork.imageExtension("https://x/y/cover"))
        assertEquals("20041121T000000", EsdeArtwork.esdeDate("2004-11-21"))
        assertNull(EsdeArtwork.esdeDate("soon"))
        val fresh = EsdeArtwork.gamelistWithGame(null, "Tom & Jerry.zip", "Tom & Jerry", "A <cat>.", "1993-05", "Hudson", "Action")!!
        assertTrue(fresh.contains("<path>./Tom &amp; Jerry.zip</path>"))
        assertTrue(fresh.contains("<desc>A &lt;cat&gt;.</desc>"))
        assertTrue(fresh.contains("<releasedate>19930501T000000</releasedate>"))
        assertNull(EsdeArtwork.gamelistWithGame(fresh, "Tom & Jerry.zip", "x", "y"))   // ES-DE's own entry wins
        val added = EsdeArtwork.gamelistWithGame(fresh, "Other.zip", "Other", "")!!
        assertEquals(2, Regex("<game>").findAll(added).count())
    }

    @Test fun `es-de play counts are read and ranked`() {
        val xml = """<gameList>
            <game><path>./a.zip</path><name>Alpha</name><playcount>3</playcount><lastplayed>20261001T120000</lastplayed></game>
            <game><path>./b.zip</path><name>Beta &amp; Co</name><playcount>9</playcount><lastplayed>20260901T120000</lastplayed></game>
            <game><path>./c.zip</path><name>Never</name></game></gameList>"""
        val plays = EsdePlayStats.parse("gba", xml)
        assertEquals(2, plays.size)
        assertEquals("Beta & Co", EsdePlayStats.top(plays).first().name)
        assertEquals("Alpha", EsdePlayStats.recent(plays).first().name)
        assertEquals(1790856000000L, EsdePlayStats.parseDate("20261001T120000"))
    }

    @Test fun `bulk plan reports the shortfall and the split per console`() {
        fun c(id: Long, console: String, size: Long) = BulkCandidate(id, console, "g$id", "g$id.zip", size, emptyList(), owned = false, downloading = false)
        val plan = BulkPlanner.plan(listOf(c(1, "gba", 600), c(2, "snes", 300), c(3, "gba", 200)), false, emptyList(), emptySet(), freeBytes = 1000)
        assertFalse(plan.fits)
        assertEquals(1100L - (1000 - 20), plan.shortBytes)
        assertEquals(listOf(Triple("gba", 2, 800L), Triple("snes", 1, 300L)), plan.perConsole)
        assertEquals(0L, BulkPlanner.plan(listOf(c(1, "gba", 10)), false, emptyList(), emptySet(), freeBytes = null).shortBytes)
    }

    @Test fun `wishlist auto download needs every word of the wish`() {
        assertTrue(GameTitleCleaner.containsAllWords("GBA1 Game 00321", "GBA1 Game 00321 (Europe) (En,Fr,De) (Rev 1).zip"))
        assertFalse(GameTitleCleaner.containsAllWords("GBA1 Game 00321", "GBA1 Game 03215 (World) (En,Fr,De) (Rev 2).zip"))
        assertTrue(GameTitleCleaner.containsAllWords("zelda minish cap", "Legend of Zelda, The - The Minish Cap (Europe).zip"))
        assertFalse(GameTitleCleaner.containsAllWords("", "anything.zip"))
    }

    @Test fun `libretro thumbnails names, systems and symlink stubs`() {
        assertEquals("Nintendo_-_Game_Boy_Advance", LibretroThumbnails.systemFor("nintendo_gameboy_advance")?.repo)
        assertEquals("Nintendo_-_Game_Boy", LibretroThumbnails.systemFor("gameboy")?.repo)
        assertEquals("Sony_-_PlayStation", LibretroThumbnails.systemFor("sony_playstation")?.repo)
        assertEquals("Sony_-_PlayStation_2", LibretroThumbnails.systemFor("playstation_2")?.repo)
        assertEquals(listOf("Final Fantasy VII (USA) (Disc 1)", "Final Fantasy VII (USA)"), LibretroThumbnails.candidates("Final Fantasy VII (USA) (Disc 1).chd"))
        assertEquals(listOf("Game (USA) (Rev 1) (En,Fr)", "Game (USA)"), LibretroThumbnails.candidates("Game (USA) (Rev 1) (En,Fr).zip"))
        assertEquals("Tom _ Jerry_ The Movie (USA)", LibretroThumbnails.thumbnailName("Tom & Jerry: The Movie (USA)"))
        val sys = LibretroThumbnails.systemFor("gba")!!
        assertEquals("https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy_Advance/master/Named_Boxarts/Advance%20Wars%20%28USA%29.png",
            LibretroThumbnails.boxartUrl(sys, "Advance Wars (USA)"))
        assertEquals("Final Fantasy VII (USA)", LibretroThumbnails.symlinkTarget("Final Fantasy VII (USA).png".toByteArray()))
        assertNull(LibretroThumbnails.symlinkTarget(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertNull(LibretroThumbnails.symlinkTarget("<html>not found</html>".toByteArray()))
    }
}
