package com.tufein.valkyr.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IGDB as a cover source. The JSON samples are written from the IGDB and Twitch public API documentation, not captured from the services. */
class IgdbTest {

    private val now = 1_000_000L

    private val games = """
    [
      {"id":1020,"name":"Advance Wars 2: Black Hole Rising","cover":{"id":9,"image_id":"co2aaa"},"platforms":[24]},
      {"id":1019,"name":"Advance Wars","cover":{"id":8,"image_id":"co1bbb"},"platforms":[24]},
      {"id":2000,"name":"Advance Wars","platforms":[24]},
      {"id":3000,"name":"Advance Wars","cover":{"id":10,"image_id":"co3ccc"},"platforms":[20]}
    ]
    """.trimIndent()

    @Test fun `the token request is a form body and the secret stays out of the address`() {
        val body = String(Igdb.tokenBody("my id", "s&cret"), Charsets.UTF_8)
        assertEquals("client_id=my+id&client_secret=s%26cret&grant_type=client_credentials", body)
        assertFalse(Igdb.TOKEN_URL.contains("secret"))
        assertEquals("https://id.twitch.tv/oauth2/token", Igdb.TOKEN_URL)
        assertTrue(CoverApis.sendsKeyTo(CoverSources.Source.IGDB, Igdb.TOKEN_URL))
        assertTrue(CoverApis.sendsKeyTo(CoverSources.Source.IGDB, Igdb.GAMES_URL))
    }

    @Test fun `the token is cached until a minute before it expires`() {
        val token = Igdb.parseToken(JsonParser.parseString("""{"access_token":"tok123","expires_in":5587808,"token_type":"bearer"}"""), now)!!
        assertEquals("tok123", token.accessToken)
        assertEquals(now + 5_587_808_000L - 60_000L, token.expiresAt)
        assertTrue(token.validAt(now))
        assertTrue(token.validAt(now + 5_587_808_000L - 60_001L))
        assertFalse(token.validAt(now + 5_587_808_000L - 60_000L))
        assertFalse(token.validAt(now + 5_587_808_000L))
        // The token never prints.
        assertFalse(token.toString().contains("tok123"))
        // A very short life (less than the margin) is already over: it is fetched again.
        assertFalse(Igdb.parseToken(JsonParser.parseString("""{"access_token":"t","expires_in":30}"""), now)!!.validAt(now))
    }

    @Test fun `answers that are not a token are not read`() {
        assertNull(Igdb.parseToken(null, now))
        assertNull(Igdb.parseToken(JsonParser.parseString("""{"status":400,"message":"invalid client"}"""), now))
        assertNull(Igdb.parseToken(JsonParser.parseString("""{"access_token":"","expires_in":100}"""), now))
        assertNull(Igdb.parseToken(JsonParser.parseString("""{"access_token":"t","expires_in":0}"""), now))
        assertNull(Igdb.parseToken(JsonParser.parseString("""{"access_token":"t"}"""), now))
    }

    @Test fun `twitch says which part of the login is wrong`() {
        assertEquals(CoverApis.KeyTest.IdRejected, Igdb.tokenRefusal(400, JsonParser.parseString("""{"status":400,"message":"invalid client"}""")))
        assertEquals(CoverApis.KeyTest.SecretRejected, Igdb.tokenRefusal(403, JsonParser.parseString("""{"status":403,"message":"invalid client secret"}""")))
        assertEquals(CoverApis.KeyTest.Rejected, Igdb.tokenRefusal(401, null))
        assertEquals(CoverApis.KeyTest.Rejected, Igdb.tokenRefusal(400, JsonParser.parseString("""{"message":"missing client secret"}""")))
        assertNull(Igdb.tokenRefusal(500, null))
        assertNull(Igdb.tokenRefusal(429, null))
    }

    @Test fun `the search body names the game, the fields and a limit`() {
        assertEquals("search \"Advance Wars\"; fields name,cover.image_id,platforms; limit 10;", Igdb.searchBody("Advance Wars"))
        // Quotes, backslashes and line breaks cannot break out of the string.
        assertEquals("\"a\\\"b\\\\c d\"", Igdb.quoted("a\"b\\c\nd"))
        assertEquals("fields name; limit 1;", Igdb.PING_BODY)
    }

    @Test fun `the platform table covers the consoles and has no duplicates`() {
        assertEquals(24, Igdb.platformId("gameboy_advance"))
        assertEquals(7, Igdb.platformId("playstation"))
        assertEquals(19, Igdb.platformId("snes"))
        assertEquals(29, Igdb.platformId("sega_genesis"))
        assertNull(Igdb.platformId("some_unknown_computer"))
        // Every console the app matches has an IGDB platform (a missing one would only lose the platform preference).
        LibretroThumbnails.systems.forEach { s -> assertTrue(s.repo, Igdb.PLATFORM_IDS[s.repo] != null) }
        assertEquals(Igdb.PLATFORM_IDS.size, Igdb.PLATFORM_IDS.values.toSet().size)
    }

    @Test fun `games are read with their cover id and platforms`() {
        val list = Igdb.parseGames(JsonParser.parseString(games))!!
        assertEquals(4, list.size)
        assertEquals(Igdb.Game(1019, "Advance Wars", "co1bbb", listOf(24)), list[1])
        assertNull(list[2].imageId)
        assertEquals("https://images.igdb.com/igdb/image/upload/t_cover_big/co1bbb.jpg", Igdb.coverUrl("co1bbb"))
        assertTrue(CoverApis.httpsOn(Igdb.coverUrl("co1bbb"), Igdb.IMAGE_HOST) != null)
    }

    @Test fun `the game on the right console with a cover and the same title wins`() {
        val list = Igdb.parseGames(JsonParser.parseString(games))!!
        // Game Boy Advance: not the sequel, not the one without a cover.
        assertEquals(1019L, Igdb.pick("Advance Wars", list, 24)?.id)
        // Nintendo DS: the DS entry.
        assertEquals(3000L, Igdb.pick("Advance Wars", list, 20)?.id)
        // The sequel by its own title.
        assertEquals(1020L, Igdb.pick("Advance Wars 2 Black Hole Rising", list, 24)?.id)
        assertNull(Igdb.pick("Fire Emblem", list, 24))
        assertNull(Igdb.pick("Advance", list, 24))
    }

    @Test fun `another console is only taken for exactly the same title`() {
        val list = listOf(
            Igdb.Game(1, "Tetris Attack Deluxe", "a", listOf(19)),
            Igdb.Game(2, "Tetris", "b", listOf(18))
        )
        // On the Game Boy (id 33): the NES version has exactly this title, the other title does not qualify.
        assertEquals(2L, Igdb.pick("Tetris", list, 33)?.id)
        assertNull(Igdb.pick("Tetris Attack", list, 33))
        // Without a known platform the title alone decides.
        assertEquals(2L, Igdb.pick("Tetris", list, null)?.id)
    }

    @Test fun `an image id that could change the address is refused`() {
        val odd = """[{"id":1,"name":"Game","cover":{"image_id":"../x?y"},"platforms":[1]},{"id":2,"name":"Game","cover":{"image_id":"co_ok-1"}}]"""
        val list = Igdb.parseGames(JsonParser.parseString(odd))!!
        assertNull(list[0].imageId)
        assertEquals("co_ok-1", list[1].imageId)
        // A 200 that is not an array is unreadable, not "no game": it must never become a cached miss.
        assertNull(Igdb.parseGames(JsonParser.parseString("""{"message":"Authorization Failure","status":401}""")))
        assertNull(Igdb.parseGames(null))
        assertEquals(emptyList<Igdb.Game>(), Igdb.parseGames(JsonParser.parseString("[]")))
    }
}
