package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.ContentType
import com.cortinadev.dogmatix.data.model.UrlEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesJsonTest {

    private val bundledAssetStyle = """
        {
          "nintendo": {
            "gameboy_advance": {
              "urls": [
                { "url": "magnet:?xt=urn:btih:abc&dn=GBA", "contentType": "GAME" },
                { "url": "https://example.org/ra", "contentType": "RETROACHIEVEMENTS", "folders": ["Sets", "More"] }
              ]
            },
            "snes": { "urls": [] }
          },
          "sony": {
            "ps2": { "urls": [ { "url": "https://example.org/ps2" } ] }
          }
        }
    """.trimIndent()

    @Test
    fun enabledFlagDefaultsToTrueAndRoundTripsWhenOff() {
        val doc = SourcesJson.parseDocument(bundledAssetStyle)
        assertTrue(doc[0].consoles[0].urls.all { it.enabled })

        val entries = listOf(
            UrlEntry("magnet:?xt=urn:btih:abc", ContentType.GAME, enabled = false),
            UrlEntry("https://example.org/on", ContentType.GAME)
        )
        val json = SourcesJson.serializeUrlEntries(entries)
        assertTrue(json.contains("\"enabled\""))
        val back = SourcesJson.parseUrlEntries(json)
        assertEquals(listOf(false, true), back.map { it.enabled })
    }

    @Test
    fun parsesTheBundledAssetLayoutAndFormatsNames() {
        val doc = SourcesJson.parseDocument(bundledAssetStyle)

        assertEquals(listOf("nintendo", "sony"), doc.map { it.id })
        assertEquals("Nintendo", doc[0].name)

        val gba = doc[0].consoles[0]
        assertEquals("nintendo_gameboy_advance", gba.id)
        assertEquals("Gameboy Advance", gba.name)
        assertEquals(2, gba.urls.size)
        assertEquals(ContentType.RETROACHIEVEMENTS, gba.urls[1].contentType)
        assertEquals(listOf("Sets", "More"), gba.urls[1].folders)

        assertEquals("SNES", doc[0].consoles[1].name)
        assertEquals("PS2", doc[1].consoles[0].name)
        assertEquals(ContentType.GAME, doc[1].consoles[0].urls[0].contentType)
    }

    @Test
    fun exportRoundTripsDisplayNamesUrlsAndFolders() {
        val original = listOf(
            SourceManufacturer(
                id = "nintendo", name = "Big N",
                consoles = listOf(
                    SourceConsole(
                        id = "nintendo_gameboy_advance", name = "Game Boy Advance",
                        shortName = "GBA", folderAliases = listOf("gba", "Game Boy Advance"),
                        urls = listOf(
                            UrlEntry("magnet:?xt=urn:btih:abc", ContentType.GAME),
                            UrlEntry("https://example.org/ra", ContentType.RETROACHIEVEMENTS, listOf("Sets"))
                        )
                    ),
                    SourceConsole(id = "nintendo_snes", name = "Super Nintendo", urls = emptyList())
                )
            )
        )

        val json = SourcesJson.serializeDocument(original)
        val parsed = SourcesJson.parseDocument(json)

        assertEquals(original, parsed)
        assertTrue(json.contains("\"_name\": \"Big N\""))
        assertTrue(json.contains("\"gameboy_advance\""))
        assertTrue(json.contains("\"short\": \"GBA\""))
        assertTrue(json.contains("\"aliases\""))
    }

    @Test
    fun shortAndAliasesAreOptionalAndCleaned() {
        val json = """{ "sega": { "genesis": { "short": " MD ", "aliases": ["md", " ", "megadrive", "md"], "urls": [] },
                                  "cd": { "urls": [] } } }"""
        val doc = SourcesJson.parseDocument(json)
        assertEquals("MD", doc[0].consoles[0].shortName)
        assertEquals(listOf("md", "megadrive"), doc[0].consoles[0].folderAliases)
        assertEquals(null, doc[0].consoles[1].shortName)
        assertEquals(emptyList<String>(), doc[0].consoles[1].folderAliases)
    }

    @Test
    fun skipsMetadataKeysBadEntriesAndUnknownContentTypes() {
        val json = """
            { "sega": { "_name": "SEGA", "_note": "ignored",
                        "genesis": { "urls": [ { "url": "  " }, { "url": "https://ok", "contentType": "bogus" }, "junk" ] },
                        "broken": "not an object" } }
        """.trimIndent()

        val doc = SourcesJson.parseDocument(json)

        assertEquals(1, doc.size)
        assertEquals("SEGA", doc[0].name)
        assertEquals(listOf("sega_genesis"), doc[0].consoles.map { it.id })
        assertEquals(listOf(UrlEntry("https://ok", ContentType.GAME)), doc[0].consoles[0].urls)
    }

    @Test
    fun urlColumnRoundTripsAndToleratesGarbage() {
        val entries = listOf(
            UrlEntry("https://a", ContentType.MISCELLANEOUS),
            UrlEntry("magnet:?xt=urn:btih:zzz", ContentType.GAME, listOf("Games"))
        )
        assertEquals(entries, SourcesJson.parseUrlEntries(SourcesJson.serializeUrlEntries(entries)))
        assertEquals(emptyList<UrlEntry>(), SourcesJson.parseUrlEntries("not json"))
        assertEquals(emptyList<UrlEntry>(), SourcesJson.parseUrlEntries("[]"))
    }

    @Test
    fun slugsAndConsoleKeysMatchRoomIds() {
        assertEquals("game_boy_advance", SourcesJson.slug("  Game Boy   Advance "))
        assertEquals("gameboy_advance", SourcesJson.consoleKey("nintendo", "nintendo_gameboy_advance"))
        assertEquals("orphan", SourcesJson.consoleKey("sony", "orphan"))
    }

    @Test
    fun favouritesRoundTripAndOlderReadersSkipThem() {
        val doc = SourcesJson.serializeDocument(
            listOf(SourceManufacturer("nintendo", "Nintendo", listOf(SourceConsole("nintendo_gba", "GBA", emptyList())))),
            listOf(SourceFavourite("nintendo_gba", "Zelda (USA).zip", 5L), SourceFavourite("nintendo_gba", "Metroid (USA).zip", 7L))
        )
        // The favourites list is no manufacturer.
        assertEquals(listOf("nintendo"), SourcesJson.parseDocument(doc).map { it.id })
        assertEquals(
            setOf(SourceFavourite("nintendo_gba", "Zelda (USA).zip", 5L), SourceFavourite("nintendo_gba", "Metroid (USA).zip", 7L)),
            SourcesJson.parseFavourites(doc).toSet()
        )
        // Without favourites nothing is written, and documents without them read as none.
        assertEquals(false, SourcesJson.serializeDocument(emptyList()).contains("_favourites"))
        assertEquals(emptyList<SourceFavourite>(), SourcesJson.parseFavourites("""{"nintendo": {}}"""))
        assertEquals(emptyList<SourceFavourite>(), SourcesJson.parseFavourites("not json"))
        assertEquals(
            listOf(SourceFavourite("a", "b", 0L)),
            SourcesJson.parseFavourites("""{"_favourites": [{"console": "a", "file": "b"}, {"file": "no console"}, 3]}""")
        )
    }

    @Test
    fun mergingFavouritesKeepsTheUnionAndTheEarliestDate() {
        val current = listOf(SourceFavourite("a", "1", 100L), SourceFavourite("a", "2", 50L))
        val incoming = listOf(SourceFavourite("a", "1", 80L), SourceFavourite("a", "2", 70L), SourceFavourite("b", "3", 10L))
        assertEquals(
            setOf(SourceFavourite("a", "1", 80L), SourceFavourite("a", "2", 50L), SourceFavourite("b", "3", 10L)),
            SourcesJson.mergeFavourites(current, incoming).toSet()
        )
    }
}
