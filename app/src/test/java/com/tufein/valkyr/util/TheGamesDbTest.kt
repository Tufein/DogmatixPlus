package com.tufein.valkyr.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TheGamesDB as a cover source. The JSON samples are written from TheGamesDB's public API documentation, not captured from the service. */
class TheGamesDbTest {

    /** `Games/ByGameName` with `include=boxart`. */
    private val search = """
    {"code":200,"status":"Success","data":{"count":3,"games":[
      {"id":1500,"game_title":"Advance Wars 2: Black Hole Rising","release_date":"2003-07-25","platform":5,"region_id":1,"country_id":50,"developers":[10]},
      {"id":1499,"game_title":"Advance Wars","release_date":"2001-09-10","platform":5,"region_id":1,"country_id":50,"developers":[10]},
      {"id":2999,"game_title":"Advance Wars","release_date":"2001-09-10","platform":8,"region_id":1,"country_id":50,"developers":[10]}
    ]},
    "include":{"boxart":{"base_url":{"original":"https://cdn.thegamesdb.net/images/original/","small":"https://cdn.thegamesdb.net/images/small/","medium":"https://cdn.thegamesdb.net/images/medium/","large":"https://cdn.thegamesdb.net/images/large/"},
      "data":{"1499":[{"id":77,"type":"boxart","side":"back","filename":"boxart/back/1499-1.jpg","resolution":"1024x1024"},{"id":78,"type":"boxart","side":"front","filename":"boxart/front/1499-1.jpg","resolution":"1024x1024"}],"1500":[{"id":80,"type":"boxart","side":"front","filename":"boxart/front/1500-1.jpg"}],"2999":[]}}},
    "pages":{"previous":null,"current":1,"next":null},"remaining_monthly_allowance":4987,"extra_allowance":0}
    """.trimIndent()

    /** `Games/Images?games_id=1499&filter[type]=boxart`. */
    private val images = """
    {"code":200,"status":"Success","data":{"count":2,"base_url":{"original":"https://cdn.thegamesdb.net/images/original/","medium":"https://cdn.thegamesdb.net/images/medium/","large":"https://cdn.thegamesdb.net/images/large/"},
      "images":{"1499":[{"id":77,"type":"boxart","side":"back","filename":"boxart/back/1499-1.jpg"},{"id":78,"type":"boxart","side":"front","filename":"boxart/front/1499-1.jpg"}]}},
    "pages":{"previous":null,"current":1,"next":null},"remaining_monthly_allowance":4986,"extra_allowance":0}
    """.trimIndent()

    @Test fun `the search address carries the key, the name and the platform`() {
        val url = TheGamesDb.searchUrl("k&1", "Advance Wars", 5)
        assertTrue(url.startsWith("https://api.thegamesdb.net/v1/Games/ByGameName?apikey=k%261&name=Advance+Wars&include=boxart"))
        assertTrue(url.endsWith("&filter%5Bplatform%5D=5"))
        assertFalse(TheGamesDb.searchUrl("k", "Zelda", null).contains("filter"))
        assertEquals(
            "https://api.thegamesdb.net/v1/Games/Images?apikey=k&games_id=1499&filter%5Btype%5D=boxart",
            TheGamesDb.imagesUrl("k", "1499")
        )
        assertTrue(CoverApis.sendsKeyTo(CoverSources.Source.THEGAMESDB, url))
        assertTrue(CoverApis.sendsKeyTo(CoverSources.Source.THEGAMESDB, TheGamesDb.imagesUrl("k", "1")))
        assertTrue(CoverApis.sendsKeyTo(CoverSources.Source.THEGAMESDB, TheGamesDb.platformsUrl("k")))
    }

    @Test fun `every console has at most one platform and the ids are plain numbers from the list`() {
        assertEquals(5, TheGamesDb.platformId("nintendo_gameboy_advance"))
        assertEquals(10, TheGamesDb.platformId("sony_playstation"))
        assertEquals(18, TheGamesDb.platformId("sega_genesis"))
        assertEquals(7, TheGamesDb.platformId("nes"))
        assertNull(TheGamesDb.platformId("some_unknown_computer"))
        // Only real libretro consoles are keyed, and no two consoles share a platform.
        val repos = LibretroThumbnails.systems.map { it.repo }.toSet()
        assertTrue(TheGamesDb.PLATFORM_IDS.keys.all { it in repos })
        assertEquals(TheGamesDb.PLATFORM_IDS.size, TheGamesDb.PLATFORM_IDS.values.toSet().size)
        // The ids GameMetadataService already uses for the game details are the same here.
        assertEquals(4912, TheGamesDb.PLATFORM_IDS["Nintendo_-_Nintendo_3DS"])
        assertEquals(41, TheGamesDb.PLATFORM_IDS["Nintendo_-_Game_Boy_Color"])
    }

    @Test fun `the search answer is read with its box art and allowance`() {
        val page = TheGamesDb.parse(JsonParser.parseString(search))!!
        assertEquals(3, page.games.size)
        assertEquals(TheGamesDb.Game("1499", "Advance Wars", 5), page.games[1])
        assertTrue(page.boxartIncluded)
        assertEquals(4987, page.remaining)
        assertEquals("https://cdn.thegamesdb.net/images/large/", page.baseUrls["large"])
        assertEquals(2, page.images.getValue("1499").size)
        assertTrue(page.images.getValue("2999").isEmpty())
    }

    @Test fun `the title must match and the platform is preferred`() {
        val page = TheGamesDb.parse(JsonParser.parseString(search))!!
        // "Advance Wars" is not "Advance Wars 2: Black Hole Rising", and the platform decides between the two equal titles.
        assertEquals("1499", TheGamesDb.pick("Advance Wars", page, 5)?.id)
        assertEquals("2999", TheGamesDb.pick("Advance Wars", page, 8)?.id)
        // Without a platform the service's order decides between identical titles.
        assertEquals("1499", TheGamesDb.pick("Advance Wars", page, null)?.id)
        assertEquals("1500", TheGamesDb.pick("Advance Wars 2 Black Hole Rising", page, 5)?.id)
        // Nothing close: no cover, never the first result.
        assertNull(TheGamesDb.pick("Fire Emblem", page, 5))
        assertNull(TheGamesDb.pick("Advance", page, 5))
    }

    @Test fun `only the front of the box is a cover, at large size, on thegamesdb`() {
        val page = TheGamesDb.parse(JsonParser.parseString(search))!!
        assertEquals("https://cdn.thegamesdb.net/images/large/boxart/front/1499-1.jpg", TheGamesDb.frontCover(page.images.getValue("1499"), page.baseUrls))
        // A game with only a back cover, or with none, gets nothing.
        assertNull(TheGamesDb.frontCover(listOf(TheGamesDb.Image("boxart", "back", "boxart/back/1-1.jpg")), page.baseUrls))
        assertNull(TheGamesDb.frontCover(page.images.getValue("2999"), page.baseUrls))
        assertNull(TheGamesDb.frontCover(listOf(TheGamesDb.Image("fanart", "front", "fanart/1.jpg")), page.baseUrls))
        // A smaller size when large is not offered.
        assertEquals("https://x.thegamesdb.net/m/a.jpg", TheGamesDb.frontCover(listOf(TheGamesDb.Image("boxart", "front", "a.jpg")), mapOf("medium" to "https://x.thegamesdb.net/m/")))
        assertNull(TheGamesDb.frontCover(listOf(TheGamesDb.Image("boxart", "front", "a.jpg")), emptyMap()))
    }

    @Test fun `an image address that leaves thegamesdb or is not https is refused`() {
        val front = listOf(TheGamesDb.Image("boxart", "front", "a.jpg"))
        assertNull(TheGamesDb.frontCover(front, mapOf("large" to "http://cdn.thegamesdb.net/images/large/")))
        assertNull(TheGamesDb.frontCover(front, mapOf("large" to "https://evil.example/images/large/")))
        assertNull(TheGamesDb.frontCover(listOf(TheGamesDb.Image("boxart", "front", "../../x.jpg")), mapOf("large" to "https://cdn.thegamesdb.net/images/large/")))
        assertNull(TheGamesDb.frontCover(listOf(TheGamesDb.Image("boxart", "front", "https://evil.example/a.jpg")), mapOf("large" to "https://cdn.thegamesdb.net/images/large/")))
    }

    @Test fun `the images endpoint answer is read too`() {
        val page = TheGamesDb.parse(JsonParser.parseString(images))!!
        assertFalse(page.boxartIncluded)
        assertTrue(page.games.isEmpty())
        assertEquals("https://cdn.thegamesdb.net/images/large/boxart/front/1499-1.jpg", TheGamesDb.frontCover(page.images.getValue("1499"), page.baseUrls))
        assertEquals(4986, page.remaining)
    }

    @Test fun `answers that are not of that shape are not read`() {
        assertNull(TheGamesDb.parse(null))
        assertNull(TheGamesDb.parse(JsonParser.parseString("[]")))
        assertNull(TheGamesDb.parse(JsonParser.parseString("""{"code":403,"status":"Invalid API key"}""")))
        // No hit is an empty list, not an error.
        val none = TheGamesDb.parse(JsonParser.parseString("""{"code":200,"status":"Success","data":{"count":0},"remaining_monthly_allowance":10}"""))!!
        assertTrue(none.games.isEmpty())
        assertEquals(10, none.remaining)
        // Games without an id are skipped.
        val odd = TheGamesDb.parse(JsonParser.parseString("""{"data":{"games":[{"game_title":"x"},{"id":"5","game_title":"Y"}]}}"""))!!
        assertEquals(listOf("5"), odd.games.map { it.id })
    }
}
