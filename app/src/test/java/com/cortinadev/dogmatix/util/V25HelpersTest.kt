package com.cortinadev.dogmatix.util

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class V25HelpersTest {
    @Test fun `bios systems match console ids and report missing or other versions`() {
        val names = BiosCatalog.systemsFor(listOf("sony_playstation", "nintendo_gameboy_advance", "sega_dreamcast")).map { it.name }
        assertEquals(listOf("PlayStation", "Dreamcast", "Game Boy Advance"), names)
        assertFalse(BiosCatalog.systemsFor(listOf("nintendo_gameboy_advance")).any { it.name.startsWith("Game Boy /") })
        assertTrue(BiosCatalog.systemsFor(listOf("sony_playstation_2")).any { it.name == "PlayStation 2" })

        val ps1 = BiosCatalog.systems.first { it.name == "PlayStation" }
        val result = BiosCatalog.check(ps1, mapOf("scph5501.bin" to "SCPH5501.BIN", "scph5502.bin" to "scph5502.bin")) {
            if (it == "scph5501.bin") "490f666e1afb15b7362b406ed1cea246" else "deadbeef"
        }
        assertTrue(result.ready)
        assertFalse(result.allGood)
        assertEquals(BiosCatalog.State.OK, result.files[1].state)
        assertEquals(BiosCatalog.State.OTHER_VERSION, result.files[2].state)
        assertEquals(BiosCatalog.State.MISSING, result.files[0].state)

        val dc = BiosCatalog.systems.first { it.name == "Dreamcast" }
        assertFalse(BiosCatalog.check(dc, mapOf("dc/dc_flash.bin" to "dc_flash.bin")) { null }.ready)
        val ps2 = BiosCatalog.systems.first { it.name == "PlayStation 2" }
        assertTrue(BiosCatalog.check(ps2, mapOf("scph-70012.bin" to "SCPH-70012.bin")) { null }.ready)
    }

    @Test fun `queue serves in its own order and can be reordered`() = runBlocking {
        val queue = DownloadQueue(1)
        val order = mutableListOf<String>()
        queue.acquire("a")   // runs
        val b = async { queue.acquire("b"); order += "b" }
        val c = async { queue.acquire("c"); order += "c" }
        val d = async { queue.acquire("d"); order += "d" }
        repeat(5) { yield() }
        assertEquals(listOf("b", "c", "d"), queue.waiting.value)
        queue.moveToFront("d")
        queue.moveDown("b")
        assertEquals(listOf("d", "c", "b"), queue.waiting.value)
        queue.release(); d.await(); queue.release(); c.await(); queue.release(); b.await()
        assertEquals(listOf("d", "c", "b"), order)
        delay(1)
    }

    @Test fun `saved views survive json and make a deep link`() {
        val view = LibraryView("v1", "PS1 Europe", "final", setOf("sony_playstation"), setOf("Europe", "Nl"), newOnly = true, collectionId = 3)
        assertEquals(listOf(view), LibraryViews.fromJson(LibraryViews.toJson(listOf(view))))
        val link = view.deepLink()
        assertTrue(link.startsWith("dogmatix://library?console=sony_playstation"))
        val request = DeepLinkParser.parse(link)!!
        assertEquals(setOf("Europe", "Nl"), request.tags)
        assertEquals("final", request.query)
        assertEquals(true, request.newOnly)
        assertEquals(3L, request.collectionId)
        assertTrue(LibraryViews.fromJson("nonsense").isEmpty())
    }

    @Test fun `es-de favourites are read and matched by name without extension`() {
        val xml = """<?xml version="1.0"?><gameList>
            <game><path>./Pixel Quest (USA).gba</path><name>Pixel Quest</name><favorite>true</favorite></game>
            <game><path>./Moon Miners (USA).gba</path><favorite>false</favorite></game>
            <game><path>./sub/Tom &amp; Jerry (Europe).zip</path><favorite>true</favorite></game>
        </gameList>"""
        val favs = EsdeFavourites.favouriteFiles(xml)
        assertEquals(listOf("Pixel Quest (USA).gba", "Tom & Jerry (Europe).zip"), favs)
        assertEquals(listOf("Pixel%20Quest%20(USA).zip", "Tom & Jerry (Europe).7z"),
            EsdeFavourites.match(favs, listOf("Pixel%20Quest%20(USA).zip", "Moon Miners (USA).zip", "Tom & Jerry (Europe).7z")))
    }

    @Test fun `statistics group by month and console`() {
        val zone = ZoneOffset.UTC
        fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()
        val log = listOf(DownloadLogEntry(at(2026, 9, 5), "snes", 100), DownloadLogEntry(at(2026, 10, 1), "snes", 50), DownloadLogEntry(at(2026, 10, 2), "psx", 700))
        assertEquals(log, DownloadStats.parse(log.joinToString("\n") { DownloadStats.line(it) } + "\nbroken line"))
        val s = DownloadStats.summarize(log, at(2026, 10, 3), months = 3, zone = zone)
        assertEquals(listOf("2026-08", "2026-09", "2026-10"), s.perMonth.map { it.first })
        assertEquals(listOf(0, 1, 2), s.perMonth.map { it.second })
        assertEquals("psx", s.perConsole.first().first)
        assertEquals(850L, s.totalBytes)
        val day = 24L * 3_600_000
        assertEquals(listOf(1, 2), DownloadStats.newPerWeek(listOf(at(2026, 9, 25), at(2026, 10, 2), at(2026, 10, 3)), at(2026, 10, 3) + 1, weeks = 2))
        assertTrue(day > 0)
    }

    @Test fun `redump systems prefer the specific console`() {
        assertEquals("ps2", RedumpSystems.systemFor("sony_playstation_2"))
        assertEquals("psx", RedumpSystems.systemFor("sony_playstation"))
        assertEquals("ss", RedumpSystems.systemFor("sega_saturn"))
        assertEquals("mcd", RedumpSystems.systemFor("sega_mega_cd"))
        assertNull(RedumpSystems.systemFor("nintendo_gameboy_advance"))
        assertEquals("http://redump.org/datfile/psx/", RedumpSystems.url("psx"))
    }

    @Test fun `shared text gives the link and what it is`() {
        assertEquals(SharedLink.Kind.MAGNET, SharedLinks.parse("Look: magnet:?xt=urn:btih:abc&dn=x nice")!!.kind)
        val file = SharedLinks.parse("Mario https://example.org/roms/Super%20Mario%20(USA).zip.")!!
        assertEquals(SharedLink.Kind.FILE, file.kind)
        assertEquals("Super Mario (USA).zip", file.fileName)
        assertEquals(SharedLink.Kind.DIRECTORY, SharedLinks.parse("https://example.org/roms/snes/")!!.kind)
        assertEquals(SharedLink.Kind.TORRENT, SharedLinks.parse("https://x.org/a.torrent")!!.kind)
        assertNull(SharedLinks.parse("no link here"))
    }

    @Test fun `mirror urls swap the base address`() {
        assertEquals(listOf("https://b.org/gba/Game%20(USA).zip", "https://c.org/x/gba/Game%20(USA).zip"),
            MirrorUrls.alternatives("https://a.org/gba/Game%20(USA).zip", "https://a.org/gba/", listOf("https://b.org/gba", "https://c.org/x/gba/")))
        assertEquals(listOf("https://a.org/gba/G.zip"), MirrorUrls.alternatives("https://b.org/gba/G.zip", "https://a.org/gba/", listOf("https://b.org/gba/")))
        assertTrue(MirrorUrls.alternatives("https://z.org/G.zip", "https://a.org/gba/", listOf("https://b.org/gba/")).isEmpty())
    }

    @Test fun `automatic backups rotate and know when they are due`() {
        val names = (1..7).map { BackupRotation.fileName(LocalDate.of(2026, 9, it)) } + "dogmatix-backup-2026-01-01.json"
        assertEquals(listOf(BackupRotation.fileName(LocalDate.of(2026, 9, 2)), BackupRotation.fileName(LocalDate.of(2026, 9, 1))), BackupRotation.toDelete(names))
        val day = 24L * 3_600_000
        assertTrue(BackupRotation.isDue(0, 10 * day, 7))
        assertFalse(BackupRotation.isDue(5 * day, 10 * day, 7))
        assertTrue(BackupRotation.isDue(3 * day, 10 * day, 7))
    }

    @Test fun `rom ids come out of romm download urls`() {
        assertEquals(42, RommSource.romIdOf(RommSource.downloadUrl("https://romm.local", 42, "a b.zip")))
        assertNull(RommSource.romIdOf("https://x.org/a.zip"))
    }
}
