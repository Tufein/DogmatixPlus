package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.SettingsKeys
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupJsonTest {

    private fun setting(type: String, value: String) = JsonParser.parseString("""{"t":"$type","v":$value}""")

    @Test
    fun everySettingTypeRoundTrips() {
        listOf<Pair<String, Any>>(
            SettingsKeys.AUTO_UNZIP.name to true,
            SettingsKeys.CONCURRENT_DOWNLOADS.name to 4,
            "some_future_long" to 1234567890123L,
            SettingsKeys.LIMIT_SPEED.name to Float.POSITIVE_INFINITY,
            "some_future_double" to 2.5,
            SettingsKeys.THEME_MODE.name to "DARK",
            SettingsKeys.FAVORITE_LANGUAGES.name to setOf("EN", "NL")
        ).forEach { (name, value) ->
            val encoded = BackupJson.encodeSetting(value)
            // Through text, as in a real backup file.
            assertEquals(name, value, BackupJson.decodeSetting(name, JsonParser.parseString(encoded.toString())))
        }
    }

    @Test
    fun aKnownSettingWithTheWrongTypeIsDropped() {
        assertNull(BackupJson.decodeSetting(SettingsKeys.THEME_MODE.name, setting("i", "3")))
        assertNull(BackupJson.decodeSetting(SettingsKeys.ONBOARDING_DONE.name, setting("s", "\"yes\"")))
        assertNull(BackupJson.decodeSetting(SettingsKeys.CONCURRENT_DOWNLOADS.name, setting("l", "3")))
        assertNull(BackupJson.decodeSetting(SettingsKeys.CONCURRENT_DOWNLOADS.name, setting("i", "\"3\"")))
    }

    @Test
    fun malformedSettingsAreDropped() {
        assertNull(BackupJson.decodeSetting("x", JsonParser.parseString("42")))
        assertNull(BackupJson.decodeSetting("x", JsonParser.parseString("""{"v":1}""")))
        assertNull(BackupJson.decodeSetting("x", setting("zz", "1")))
        assertNull(BackupJson.decodeSetting(SettingsKeys.LIMIT_SPEED.name, setting("f", "\"fast\"")))
    }

    @Test
    fun numbersAreKeptWithinWhatSettingsOffers() {
        assertEquals(1, BackupJson.decodeSetting(SettingsKeys.CONCURRENT_DOWNLOADS.name, setting("i", "0")))
        assertEquals(10, BackupJson.decodeSetting(SettingsKeys.CONCURRENT_DOWNLOADS.name, setting("i", "99")))
        assertEquals(0, BackupJson.decodeSetting(SettingsKeys.MAX_SEARCH_RESULTS.name, setting("i", "-5")))
        assertEquals(Float.POSITIVE_INFINITY, BackupJson.decodeSetting(SettingsKeys.LIMIT_SPEED.name, setting("f", "\"-1\"")))
    }

    @Test
    fun idValueSetsDropEntriesTheirReadersCannotSplit() {
        val decoded = BackupJson.decodeSetting(
            SettingsKeys.ROMM_PLATFORM_MAP.name, setting("ss", """["sony_psp:12","broken",":5","nintendo_gba:"]""")
        )
        assertEquals(setOf("sony_psp:12"), decoded)
    }

    @Test
    fun favouritesRoundTripWithFixedFieldNames() {
        val rows = listOf(FavouriteEntity("sony_psp", "Game (USA).iso", 1000L))
        val json = BackupJson.favouritesToJson(rows)
        assertEquals("sony_psp", (json[0] as JsonObject).get("consoleId").asString)
        assertEquals(rows, BackupJson.favouritesFromJson(JsonParser.parseString(json.toString())))
    }

    @Test
    fun historyRoundTripsAndDropsDebridIds() {
        val row = DownloadHistoryEntity(
            fileName = "Game.zip", name = "Game", consoleId = "nintendo_gba", downloadUrl = "magnet:?xt=1",
            fileSize = 99L, fileExtension = ".zip", torrentFileIndex = 3, torrentMagnet = "magnet:?xt=1",
            status = "COMPLETED", startedAt = 1L, finishedAt = 2L,
            debridProvider = "TORBOX", debridTorrentId = "77", debridFileId = 5
        )
        val restored = BackupJson.historyFromJson(JsonParser.parseString(BackupJson.historyToJson(listOf(row)).toString()))
        assertEquals(listOf(row.copy(debridProvider = null, debridTorrentId = null, debridFileId = null)), restored)
    }

    @Test
    fun brokenRowsAreSkippedInsteadOfFailingTheRestore() {
        val array = JsonParser.parseString("""[
            null, 5, {"fileName":"a"},
            {"fileName":"a","name":"A","consoleId":"c","downloadUrl":"u","status":"NOT_A_STATUS","startedAt":1},
            {"fileName":"b","name":"B","consoleId":"c","downloadUrl":"u","status":"FAILED","startedAt":"soon"}
        ]""") as JsonArray
        val restored = BackupJson.historyFromJson(array)
        assertEquals(listOf("b"), restored.map { it.fileName })
        assertEquals(0L, restored.single().startedAt)
        assertEquals(emptyList<FavouriteEntity>(), BackupJson.favouritesFromJson(JsonParser.parseString("""[{"consoleId":1}]""")))
        assertEquals(emptyList<FavouriteEntity>(), BackupJson.favouritesFromJson(null))
    }
}
