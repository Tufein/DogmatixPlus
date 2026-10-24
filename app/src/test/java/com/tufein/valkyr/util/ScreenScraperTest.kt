package com.tufein.valkyr.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** ScreenScraper as a cover source. The JSON samples follow the shape in ScreenScraper's API documentation. */
class ScreenScraperTest {

    private val creds = ScreenScraper.Credentials("mydev", "devpw&1", "", "sam", "pw 2")

    @Test fun `every libretro console has a screenscraper system`() {
        LibretroThumbnails.systems.forEach { s -> assertTrue(s.repo, ScreenScraper.SYSTEM_IDS[s.repo] != null) }
        assertEquals(12, ScreenScraper.systemId("nintendo_gameboy_advance"))
        assertEquals(57, ScreenScraper.systemId("sony_playstation"))
        assertEquals(58, ScreenScraper.systemId("playstation_2"))
        assertEquals(1, ScreenScraper.systemId("sega_genesis"))
        assertEquals(4, ScreenScraper.systemId("snes"))
        assertNull(ScreenScraper.systemId("some_unknown_computer"))
        // No two consoles share an id.
        assertEquals(ScreenScraper.SYSTEM_IDS.size, ScreenScraper.SYSTEM_IDS.values.toSet().size)
    }

    @Test fun `regions follow the preference, then world, us, eu, jp`() {
        assertEquals(listOf("eu", "nl", "wor", "us", "jp", "ss"), ScreenScraper.regionCodes(listOf("Europe", "Netherlands", "Narnia")))
        assertEquals(listOf("wor", "us", "eu", "jp", "ss"), ScreenScraper.regionCodes(emptyList()))
        assertEquals(listOf("uk", "sp", "wor", "us", "eu", "jp", "ss"), ScreenScraper.regionCodes(listOf("UK", "spain")))
    }

    @Test fun `credentials are needed, encoded and never printed`() {
        assertTrue(creds.ready)
        assertTrue(creds.hasLogin)
        assertEquals("Valkyr", creds.softName)
        assertFalse(ScreenScraper.Credentials(devId = "x").ready)
        assertFalse(creds.toString().contains("devpw"))
        assertFalse(creds.toString().contains("sam"))
        val url = ScreenScraper.gameUrl(creds, 12, "Advance Wars (USA).gba")
        assertTrue(url.startsWith("https://api.screenscraper.fr/api2/jeuInfos.php?devid=mydev&devpassword=devpw%261&softname=Valkyr&output=json&ssid=sam&sspassword=pw+2"))
        assertTrue(url.endsWith("&systemeid=12&romtype=rom&romnom=Advance+Wars+%28USA%29.gba"))
        val anon = ScreenScraper.Credentials("d", "p", "MyApp")
        assertFalse(ScreenScraper.searchUrl(anon, 3, "Zelda").contains("ssid"))
        assertTrue(ScreenScraper.searchUrl(anon, 3, "Zelda").contains("softname=MyApp"))
    }

    @Test fun `status codes`() {
        assertEquals(ScreenScraper.Status.OK, ScreenScraper.status(200))
        assertEquals(ScreenScraper.Status.NOT_FOUND, ScreenScraper.status(404))
        assertEquals(ScreenScraper.Status.RATE_LIMITED, ScreenScraper.status(429))
        assertEquals(ScreenScraper.Status.QUOTA, ScreenScraper.status(430))
        assertEquals(ScreenScraper.Status.QUOTA, ScreenScraper.status(431))
        assertEquals(ScreenScraper.Status.LOGIN_REJECTED, ScreenScraper.status(401))
        assertEquals(ScreenScraper.Status.LOGIN_REJECTED, ScreenScraper.status(403))
        assertEquals(ScreenScraper.Status.CLOSED, ScreenScraper.status(423))
        assertEquals(ScreenScraper.Status.CLOSED, ScreenScraper.status(426))
        assertEquals(ScreenScraper.Status.BAD_REQUEST, ScreenScraper.status(400))
        assertEquals(ScreenScraper.Status.SERVER_ERROR, ScreenScraper.status(503))
    }

    private val gameJson = """
        {"header":{"APIversion":"2.0","success":"true"},
         "response":{
           "ssuser":{"id":"sam","niveau":"1","maxthreads":"2","requeststoday":"120","maxrequestsperday":"20000"},
           "jeu":{"id":"3","noms":[{"region":"wor","text":"Sonic The Hedgehog"},{"region":"jp","text":"Sonic"}],
             "medias":[
               {"type":"ss","parent":"jeu","url":"https://neoclone.screenscraper.fr/api2/mediaJeu.php?devid=mydev&devpassword=devpw&softname=x&ssid=sam&sspassword=pw&systemeid=1&jeuid=3&media=ss","region":"wor","format":"png"},
               {"type":"box-2D","parent":"jeu","url":"https://neoclone.screenscraper.fr/api2/mediaJeu.php?devid=mydev&devpassword=devpw&softname=x&ssid=sam&sspassword=pw&systemeid=1&jeuid=3&media=box-2D(jp)","region":"jp","format":"png"},
               {"type":"box-2D","parent":"jeu","url":"https://neoclone.screenscraper.fr/api2/mediaJeu.php?devid=mydev&devpassword=devpw&softname=x&ssid=sam&sspassword=pw&systemeid=1&jeuid=3&media=box-2D(eu)","region":"eu","format":"png"},
               {"type":"box-2D","parent":"jeu","url":"https://neoclone.screenscraper.fr/api2/mediaJeu.php?devid=mydev&devpassword=devpw&softname=x&ssid=sam&sspassword=pw&systemeid=1&jeuid=3&media=box-2D(us)","region":"us","format":"png"}
             ]}}}
    """.trimIndent()

    @Test fun `game, cover and user from jeuInfos`() {
        val json = JsonParser.parseString(gameJson)
        val game = ScreenScraper.parseGame(json)!!
        assertEquals("3", game.id)
        assertEquals(listOf("Sonic The Hedgehog", "Sonic"), game.names)
        assertEquals(
            "https://neoclone.screenscraper.fr/api2/mediaJeu.php?systemeid=1&jeuid=3&media=box-2D(eu)",
            ScreenScraper.pickCover(game, ScreenScraper.regionCodes(listOf("Europe")), nonBoxArt = false)
        )
        assertEquals(
            "https://neoclone.screenscraper.fr/api2/mediaJeu.php?systemeid=1&jeuid=3&media=box-2D(us)",
            ScreenScraper.pickCover(game, ScreenScraper.regionCodes(emptyList()), nonBoxArt = false)
        )
        val user = ScreenScraper.parseUser(json)!!
        assertEquals("sam", user.name)
        assertEquals(2, user.maxThreads)
        assertEquals(19_880, user.remainingToday)
        assertFalse(user.quotaUsedUp)
    }

    @Test fun `no box art is a miss unless title screens may stand in`() {
        val game = ScreenScraper.Game("1", listOf("X"), listOf(ScreenScraper.Media("ss", "wor", "https://www.screenscraper.fr/api2/mediaJeu.php?devid=a&jeuid=1&media=ss", "png")))
        assertNull(ScreenScraper.pickCover(game, listOf("wor"), nonBoxArt = false))
        assertEquals("https://www.screenscraper.fr/api2/mediaJeu.php?jeuid=1&media=ss", ScreenScraper.pickCover(game, listOf("wor"), nonBoxArt = true))
    }

    @Test fun `search results and an empty search`() {
        val found = JsonParser.parseString(
            """{"response":{"jeux":[{"id":"10","noms":[{"text":"Zelda II"}],"medias":[]},{"id":"11","noms":[{"text":"The Legend of Zelda"}],"medias":[]}]}}"""
        )
        val games = ScreenScraper.parseSearch(found)
        assertEquals(listOf("10", "11"), games.map { it.id })
        assertEquals("11", ScreenScraper.bestMatch(games, "Legend of Zelda")?.id)
        assertNull(ScreenScraper.bestMatch(games, "Metroid"))
        assertEquals(emptyList<ScreenScraper.Game>(), ScreenScraper.parseSearch(JsonParser.parseString("""{"response":{"jeux":[{}]}}""")))
        assertNull(ScreenScraper.parseGame(JsonParser.parseString("""{"response":{}}""")))
        assertNull(ScreenScraper.parseUser(JsonParser.parseString("""[]""")))
    }

    @Test fun `a used up quota is seen`() {
        val user = ScreenScraper.parseUser(JsonParser.parseString("""{"response":{"ssuser":{"id":"a","maxthreads":"40","requeststoday":"20001","maxrequestsperday":"20000"}}}"""))!!
        assertTrue(user.quotaUsedUp)
        assertEquals(0, user.remainingToday)
        assertEquals(ScreenScraper.MAX_THREADS, user.maxThreads)
    }

    @Test fun `credentials are stripped for storage and added back on the way out`() {
        val stored = ScreenScraper.stripCredentials("https://neoclone.screenscraper.fr/api2/mediaJeu.php?devid=a&devpassword=b&softname=c&ssid=d&sspassword=e&systemeid=1&jeuid=3&media=box-2D(eu)")
        assertEquals("https://neoclone.screenscraper.fr/api2/mediaJeu.php?systemeid=1&jeuid=3&media=box-2D(eu)", stored)
        assertTrue(ScreenScraper.isApiUrl(stored))
        assertFalse(ScreenScraper.isApiUrl("https://raw.githubusercontent.com/libretro-thumbnails/x.png"))
        assertFalse(ScreenScraper.isApiUrl("https://evil.example/screenscraper.fr/api2/mediaJeu.php"))
        assertFalse(ScreenScraper.isApiUrl("https://screenscraper.fr.evil.example/api2/mediaJeu.php"))
        val signed = ScreenScraper.withCredentials(stored, creds)
        assertEquals("https://neoclone.screenscraper.fr/api2/mediaJeu.php?devid=mydev&devpassword=devpw%261&softname=Valkyr&ssid=sam&sspassword=pw+2&systemeid=1&jeuid=3&media=box-2D(eu)", signed)
        // Signing twice does not add them twice; other hosts never get them.
        assertEquals(signed, ScreenScraper.withCredentials(signed, creds))
        assertEquals("https://example.com/a.png", ScreenScraper.withCredentials("https://example.com/a.png", creds))
        assertEquals(stored, ScreenScraper.withCredentials(stored, ScreenScraper.Credentials()))
    }

    @Test fun `the quota pause ends at the next midnight in France`() {
        val paris = ZoneId.of("Europe/Paris")
        val now = ZonedDateTime.of(2026, 10, 21, 22, 30, 0, 0, paris).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2026, 10, 22, 0, 0, 0, 0, paris).toInstant().toEpochMilli(), ScreenScraper.nextReset(now))
    }

    @Test fun `passwords never travel in a backup and come back only for the same account`() {
        assertTrue(CloudSettingKeys.isSecret(ScreenScraper.DEV_PASSWORD_KEY))
        assertTrue(CloudSettingKeys.isSecret(ScreenScraper.PASSWORD_KEY))
        assertFalse(CloudSettingKeys.isSecret(ScreenScraper.USER_KEY))
        val setting = JsonParser.parseString("""{"t":"s","v":"hunter2"}""")
        assertNull(BackupJson.decodeSetting(ScreenScraper.PASSWORD_KEY, setting))
        assertNull(BackupJson.decodeSetting(ScreenScraper.DEV_PASSWORD_KEY, setting))
        assertEquals("hunter2", BackupJson.decodeSetting(ScreenScraper.USER_KEY, setting))

        val current = mapOf(
            ScreenScraper.DEV_ID_KEY to "dev", ScreenScraper.DEV_PASSWORD_KEY to "dp",
            ScreenScraper.USER_KEY to "sam", ScreenScraper.PASSWORD_KEY to "up"
        )
        assertEquals(
            mapOf(ScreenScraper.DEV_PASSWORD_KEY to "dp", ScreenScraper.PASSWORD_KEY to "up"),
            ScreenScraper.keptSecrets(current, mapOf(ScreenScraper.DEV_ID_KEY to "dev", ScreenScraper.USER_KEY to "sam"), backupHasScreenScraper = true)
        )
        assertEquals(
            mapOf(ScreenScraper.DEV_PASSWORD_KEY to "dp"),
            ScreenScraper.keptSecrets(current, mapOf(ScreenScraper.DEV_ID_KEY to "dev", ScreenScraper.USER_KEY to "kim"), backupHasScreenScraper = true)
        )
        assertEquals(emptyMap<String, String>(), ScreenScraper.keptSecrets(current, emptyMap(), backupHasScreenScraper = true))
        assertEquals(2, ScreenScraper.keptSecrets(current, emptyMap(), backupHasScreenScraper = false).size)
    }

    @Test fun `credentials only ever go to https`() {
        assertFalse(ScreenScraper.isApiUrl("http://neoclone.screenscraper.fr/api2/mediaJeu.php?jeuid=3"))
        assertEquals("http://neoclone.screenscraper.fr/api2/mediaJeu.php?jeuid=3", ScreenScraper.withCredentials("http://neoclone.screenscraper.fr/api2/mediaJeu.php?jeuid=3", creds))
        assertEquals("https://a.screenscraper.fr/x", ScreenScraper.httpsOnly("http://a.screenscraper.fr/x"))
        assertEquals("", ScreenScraper.httpsOnly("ftp://a/x"))
        val json = JsonParser.parseString(
            """{"response":{"jeu":{"id":"3","noms":[],"medias":[{"type":"box-2D","region":"eu","url":"http://neoclone.screenscraper.fr/api2/mediaJeu.php?devid=a&devpassword=b&jeuid=3&media=box-2D(eu)"}]}}}"""
        )
        val cover = ScreenScraper.pickCover(ScreenScraper.parseGame(json)!!, listOf("eu"), nonBoxArt = false)
        assertEquals("https://neoclone.screenscraper.fr/api2/mediaJeu.php?jeuid=3&media=box-2D(eu)", cover)
        assertTrue(ScreenScraper.isApiUrl(cover!!))
    }

    @Test fun `level and unrecognised ROM counters are read`() {
        val user = ScreenScraper.parseUser(JsonParser.parseString("""{"response":{"ssuser":{"id":"a","niveau":"3","maxthreads":"2","requeststoday":"5","maxrequestsperday":"20000","requestskotoday":"1","maxrequestskoperday":"2000"}}}"""))!!
        assertEquals("3", user.level)
        assertEquals(1, user.requestsKoToday)
        assertEquals(2000, user.maxRequestsKoPerDay)
        val bare = ScreenScraper.parseUser(JsonParser.parseString("""{"response":{"ssuser":{"id":"a"}}}"""))!!
        assertEquals("", bare.level)
        assertNull(bare.requestsKoToday)
    }
}
