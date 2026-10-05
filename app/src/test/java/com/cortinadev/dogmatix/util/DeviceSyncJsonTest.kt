package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSyncJsonTest {

    private val chrono = DeviceSyncMerge.itemKey("snes", "Chrono Trigger (USA).sfc")
    private val pipe = DeviceSyncMerge.itemKey("psx", "Odd|Name (Disc 1).chd")

    private val library = SyncLibrary(
        favourites = mapOf(chrono to 1791148442000L, pipe to 5L),
        wishlist = mapOf(DeviceSyncMerge.wishKey("Mother 3", "gba") to SyncWish("Mother 3", "gba", 7L)),
        collections = mapOf("couch co-op" to SyncCollection("Couch co-op", 3L, mapOf(chrono to 4L)))
    )

    @Test fun `a document survives a round trip`() {
        val doc = DeviceSyncJson.Document(library, 99L, "Thor", mapOf("abc" to DeviceSyncJson.Device("Thor", 99L)))
        val parsed = DeviceSyncJson.read(DeviceSyncJson.write(doc))
        assertEquals(DeviceSyncJson.Parsed.Ok(doc), parsed)
    }

    @Test fun `reads a hand-made file and skips what it cannot use`() {
        val text = """
            { "format": "dogmatix-sync", "version": 1,
              "favourites": [
                { "consoleId": "snes", "fileName": "Chrono Trigger (USA).sfc", "addedAt": 10 },
                { "consoleId": "snes", "fileName": "Chrono Trigger (USA).sfc", "addedAt": 30 },
                { "consoleId": "", "fileName": "x.sfc" },
                { "fileName": "no console.sfc" },
                { "consoleId": "a|b", "fileName": "bar in console.sfc" },
                "garbage", 42,
                { "consoleId": "gb", "fileName": "Tetris (World).gb" }
              ],
              "wishlist": [ { "title": "X" }, { "title": "  Mother 3 ", "consoleId": "" }, { "consoleId": "gba" } ],
              "collections": [
                { "name": "  ", "items": [] },
                { "name": "RPG", "createdAt": 5, "items": [ { "consoleId": "snes", "fileName": "Chrono Trigger (USA).sfc", "addedAt": 6 }, {} ] },
                { "name": "rpg", "items": [ { "consoleId": "gb", "fileName": "Tetris (World).gb", "addedAt": 8 } ] }
              ],
              "devices": "not an object" }
        """.trimIndent()
        val parsed = DeviceSyncJson.read(text) as DeviceSyncJson.Parsed.Ok
        val lib = parsed.document.library
        assertEquals(mapOf(chrono to 30L, DeviceSyncMerge.itemKey("gb", "Tetris (World).gb") to 0L), lib.favourites)
        assertEquals(listOf(SyncWish("Mother 3", null, 0L)), lib.wishlist.values.toList())
        assertEquals(setOf("rpg"), lib.collections.keys)
        assertEquals(2, lib.collections["rpg"]?.items?.size)
        assertEquals("RPG", lib.collections["rpg"]?.name)
        assertTrue(parsed.document.devices.isEmpty())
    }

    @Test fun `a file of a newer format is left alone`() {
        assertEquals(DeviceSyncJson.Parsed.Newer(2), DeviceSyncJson.read("""{"format":"dogmatix-sync","version":2,"favourites":[]}"""))
    }

    @Test fun `anything else is invalid`() {
        assertEquals(DeviceSyncJson.Parsed.Invalid, DeviceSyncJson.read("<html>"))
        assertEquals(DeviceSyncJson.Parsed.Invalid, DeviceSyncJson.read("[]"))
        assertEquals(DeviceSyncJson.Parsed.Invalid, DeviceSyncJson.read("""{"format":"dogmatix-backup","version":1}"""))
        assertEquals(DeviceSyncJson.Parsed.Invalid, DeviceSyncJson.read("""{"format":"dogmatix-sync"}"""))
    }

    @Test fun `the base remembers which server file it belongs to`() {
        val base = DeviceSyncJson.Base("https://h/dav/Dogmatix/sync/library.json", library, 12L)
        assertEquals(base, DeviceSyncJson.readBase(DeviceSyncJson.writeBase(base)))
        assertNull(DeviceSyncJson.readBase("{}"))
        assertNull(DeviceSyncJson.readBase("""{"format":"dogmatix-sync-base","version":1,"remote":""}"""))
        assertNull(DeviceSyncJson.readBase("garbage"))
    }

    @Test fun `long collection names are cut like the app cuts them`() {
        val name = "x".repeat(80)
        val text = """{"format":"dogmatix-sync","version":1,"collections":[{"name":"$name","createdAt":1,"items":[]}]}"""
        val lib = (DeviceSyncJson.read(text) as DeviceSyncJson.Parsed.Ok).document.library
        assertEquals(60, lib.collections.values.single().name.length)
    }
}
