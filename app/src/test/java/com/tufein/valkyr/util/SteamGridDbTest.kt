package com.tufein.valkyr.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SteamGridDB as a cover source. The JSON samples are written from SteamGridDB's public API documentation, not captured from the service. */
class SteamGridDbTest {

    private val search = """
    {"success":true,"data":[
      {"id":5254,"name":"Super Mario World 2: Yoshi's Island","types":["steam"],"verified":false},
      {"id":5300,"name":"Super Mario World","types":["gog"],"verified":true}
    ]}
    """.trimIndent()

    private val grids = """
    {"success":true,"page":0,"total":5,"limit":50,"data":[
      {"id":1,"score":0,"style":"alternate","width":920,"height":430,"nsfw":false,"humor":false,"mime":"image/png","language":"en","url":"https://cdn2.steamgriddb.com/grid/wide.png","thumb":"https://cdn2.steamgriddb.com/thumb/wide.jpg","upvotes":50,"downvotes":0},
      {"id":2,"score":0,"style":"alternate","width":600,"height":900,"nsfw":false,"humor":false,"mime":"image/png","language":"en","url":"https://cdn2.steamgriddb.com/grid/a.png","thumb":"x","upvotes":2,"downvotes":0},
      {"id":3,"score":0,"style":"material","width":600,"height":900,"nsfw":false,"humor":false,"mime":"image/jpeg","language":"en","url":"https://cdn2.steamgriddb.com/grid/b.jpg","thumb":"x","upvotes":9,"downvotes":1},
      {"id":4,"score":0,"style":"alternate","width":600,"height":900,"nsfw":true,"humor":false,"mime":"image/png","language":"en","url":"https://cdn2.steamgriddb.com/grid/adult.png","thumb":"x","upvotes":99,"downvotes":0},
      {"id":5,"score":0,"style":"alternate","width":600,"height":900,"nsfw":false,"humor":false,"mime":"image/png","language":"en","url":"https://cdn2.steamgriddb.com/grid/c.png","thumb":"x","upvotes":8,"downvotes":0}
    ]}
    """.trimIndent()

    @Test fun `addresses and the bearer header`() {
        assertEquals("https://www.steamgriddb.com/api/v2/search/autocomplete/Super%20Mario%20World", SteamGridDb.searchUrl("Super Mario World"))
        assertEquals(
            "https://www.steamgriddb.com/api/v2/grids/game/5300?dimensions=600x900,342x482,660x930&types=static&nsfw=false&humor=any",
            SteamGridDb.gridsUrl(5300)
        )
        assertEquals("Bearer abc", SteamGridDb.authorization("abc"))
        // The key is never in an address, and the addresses only go to steamgriddb.com.
        assertFalse(SteamGridDb.searchUrl("x").contains("abc"))
        assertTrue(CoverApis.sendsKeyTo(CoverSources.Source.STEAMGRIDDB, SteamGridDb.searchUrl("Sonic & Knuckles / 3")))
        assertTrue(CoverApis.sendsKeyTo(CoverSources.Source.STEAMGRIDDB, SteamGridDb.gridsUrl(1)))
    }

    @Test fun `the search is read and the closely matching game is picked`() {
        val games = SteamGridDb.parseSearch(JsonParser.parseString(search))
        assertEquals(listOf(5254L, 5300L), games.map { it.id })
        assertEquals(true, games[1].verified)
        // The first hit is the sequel: it is not taken.
        assertEquals(5300L, SteamGridDb.pickGame("Super Mario World", games)?.id)
        assertNull(SteamGridDb.pickGame("Super Mario Kart", games))
        // No platform to check: one extra plain word is not enough either, only the identical title.
        val doom = listOf(SteamGridDb.Game(1, "Doom Eternal", false), SteamGridDb.Game(2, "Doom Eternal Deluxe Pack", false))
        assertNull(SteamGridDb.pickGame("Doom Eternal Deluxe", doom))
        assertEquals(1L, SteamGridDb.pickGame("Doom Eternal", doom)?.id)
        // Of several identical titles a verified one wins, else the first.
        val twin = listOf(SteamGridDb.Game(1, "Doom", false), SteamGridDb.Game(2, "Doom", true), SteamGridDb.Game(3, "Doom", true))
        assertEquals(2L, SteamGridDb.pickGame("Doom", twin)?.id)
        assertEquals(1L, SteamGridDb.pickGame("Doom", twin.map { it.copy(verified = false) })?.id)
        assertNull(SteamGridDb.pickGame("Mario", games))
    }

    @Test fun `the best voted portrait grid is the cover`() {
        val list = SteamGridDb.parseGrids(JsonParser.parseString(grids))
        assertEquals(5, list.size)
        // Not the wide one (most votes), not the adult one: b.jpg has net 8, c.png net 8 (listed later), a.png net 2.
        assertEquals("https://cdn2.steamgriddb.com/grid/b.jpg", SteamGridDb.pickGrid(list))
    }

    @Test fun `a tie goes to the grid listed first and a disliked grid is never taken`() {
        fun g(url: String, up: Int, down: Int, w: Int = 600, h: Int = 900, nsfw: Boolean = false) =
            SteamGridDb.Grid("https://cdn2.steamgriddb.com/grid/$url", w, h, up, down, nsfw, false)
        assertEquals("https://cdn2.steamgriddb.com/grid/first.png", SteamGridDb.pickGrid(listOf(g("first.png", 3, 0), g("second.png", 3, 0))))
        assertNull(SteamGridDb.pickGrid(listOf(g("bad.png", 1, 5))))
        assertNull(SteamGridDb.pickGrid(listOf(g("wide.png", 9, 0, w = 920, h = 430))))
        assertNull(SteamGridDb.pickGrid(listOf(g("adult.png", 9, 0, nsfw = true))))
        assertNull(SteamGridDb.pickGrid(emptyList()))
    }

    @Test fun `grid addresses must be https on steamgriddb`() {
        val bad = """{"success":true,"data":[
          {"width":600,"height":900,"url":"http://cdn2.steamgriddb.com/grid/a.png","upvotes":1,"downvotes":0},
          {"width":600,"height":900,"url":"https://evil.example/grid/a.png","upvotes":1,"downvotes":0},
          {"width":600,"height":900,"url":"https://cdn2.steamgriddb.com.evil.example/a.png","upvotes":1,"downvotes":0}]}"""
        assertEquals(emptyList<SteamGridDb.Grid>(), SteamGridDb.parseGrids(JsonParser.parseString(bad)))
    }

    @Test fun `answers that are not of that shape are not read`() {
        assertEquals(emptyList<SteamGridDb.Game>(), SteamGridDb.parseSearch(null))
        assertEquals(emptyList<SteamGridDb.Game>(), SteamGridDb.parseSearch(JsonParser.parseString("""{"success":false,"errors":["Game not found"]}""")))
        assertEquals(emptyList<SteamGridDb.Game>(), SteamGridDb.parseSearch(JsonParser.parseString("""{"success":true,"data":[]}""")))
        assertEquals(emptyList<SteamGridDb.Grid>(), SteamGridDb.parseGrids(JsonParser.parseString("""{"success":true,"data":{}}""")))
        assertEquals(emptyList<SteamGridDb.Game>(), SteamGridDb.parseSearch(JsonParser.parseString("""{"success":true,"data":[{"name":"No id"},{"id":"x","name":"Bad id"}]}""")))
    }
}
