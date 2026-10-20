package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.ContentType
import com.tufein.valkyr.data.model.UrlEntry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The Homebrew Hub reader against answers of the live API (hh3.gbdev.io, 2026-10-15): the first
 * search page for Game Boy and for Game Boy Color, and two pages of the "Open Source" tag search
 * (more licence spellings). The fetch tool gave the answers field by field, not as raw bytes, so
 * the fixtures are those fields written back as JSON, trimmed (descriptions, screenshots and tool
 * details left out); key names and values are as served.
 */
class HomebrewHubTest {
    private fun fixture(name: String) = File("src/test/resources/homebrewhub/$name").readText()
    private fun page(name: String, number: Int = 1) = HomebrewHub.parsePage(fixture(name), number)
    private fun games(name: String, platform: HomebrewHub.Platform, number: Int = 1) = HomebrewHub.games(page(name, number).entries, platform)
    private fun manifest(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test fun aSearchPageGivesItsPositionTotalsAndManifests() {
        val gb = page("search_gb_page1.json")
        assertEquals(1, gb.current)
        assertEquals(92, gb.total)
        assertEquals(913, gb.results)
        assertEquals(10, gb.entries.size)
        val gbc = page("search_gbc_page1.json")
        assertEquals(45, gbc.total)
        assertEquals(448, gbc.results)
        assertEquals(2, page("search_gb_open_source_page2.json", 2).current)
    }

    @Test fun anAnswerThatIsNotTheExpectedPageFailsTheWholeSource() {
        // Another page than asked for (the API answers an out-of-range page with another one).
        assertThrows(IllegalArgumentException::class.java) { page("search_gb_page1.json", 2) }
        // A web page, an error object, a list without totals, too many pages.
        assertThrows(Exception::class.java) { HomebrewHub.parsePage("<html><body>Bad gateway</body></html>", 1) }
        assertThrows(Exception::class.java) { HomebrewHub.parsePage("""{"detail":"Not found."}""", 1) }
        assertThrows(Exception::class.java) { HomebrewHub.parsePage("""{"entries":[]}""", 1) }
        val huge = """{"results":99999,"page_total":${HomebrewHub.MAX_PAGES + 1},"page_current":1,"page_elements":10,"entries":[]}"""
        assertThrows(IllegalArgumentException::class.java) { HomebrewHub.parsePage(huge, 1) }
        val tooMany = JsonParser.parseString(fixture("search_gb_page1.json")).asJsonObject.apply {
            getAsJsonArray("entries").add(getAsJsonArray("entries")[0])
        }
        assertThrows(IllegalArgumentException::class.java) { HomebrewHub.parsePage(tooMany.toString(), 1) }
        assertThrows(IllegalArgumentException::class.java) { HomebrewHub.parsePage(" ".repeat(HomebrewHub.MAX_PAGE_BYTES + 1), 1) }
        // A page that is empty or short for its place in the results (a cut-off answer) is not accepted.
        val cut = JsonParser.parseString(fixture("search_gb_page1.json")).asJsonObject.apply { add("entries", com.google.gson.JsonArray()) }
        assertThrows(IllegalArgumentException::class.java) { HomebrewHub.parsePage(cut.toString(), 1) }
        val lastPageShort = """{"results":23,"page_total":3,"page_current":3,"page_elements":10,"entries":[]}"""
        assertThrows(IllegalArgumentException::class.java) { HomebrewHub.parsePage(lastPageShort, 3) }
        // An empty search is a valid answer.
        assertTrue(HomebrewHub.parsePage("""{"results":0,"page_total":0,"page_current":1,"page_elements":10,"entries":[]}""", 1).entries.isEmpty())
    }

    @Test fun onlyEntriesUnderAFreeLicenceAreOfferedOnTheGameBoyPage() {
        val games = games("search_gb_page1.json", HomebrewHub.Platform.GB)
        // Six of the ten have no licence at all.
        assertEquals(listOf("dorotea", "scorching-light", "totp-gb", "maxpirateeb"), games.map { it.slug })
        val dorotea = games.first()
        assertEquals("Dorotea", dorotea.title)
        assertEquals(listOf("CC-BY-NC-ND-4.0"), dorotea.licences)
        assertEquals(listOf("Pasquale 'Pasqui industry' Pignataro"), dorotea.developers)
        // The default ROM (English), not the Italian one next to it.
        assertEquals("pasquiindustry_dorotea_en_v_1_1_0.gb", dorotea.file)
        // Game and assets under the same licence: one tag.
        assertEquals(listOf("MIT"), games.single { it.slug == "totp-gb" }.licences)
        assertEquals("totp-gb.gb", games.single { it.slug == "totp-gb" }.file)
    }

    @Test fun downloadsComeFromTheDatabaseRepositoryWithEscapedPaths() {
        val max = games("search_gb_page1.json", HomebrewHub.Platform.GB).single { it.slug == "maxpirateeb" }
        // This address answered with the ROM on 2026-10-15.
        assertEquals("https://raw.githubusercontent.com/gbdev/database/master/entries/maxpirateeb/maxpirateeb.gb", max.downloadUrl)
        assertEquals("maxpirateeb/maxpirateeb.gb", max.fileName)
        val gbc = games("search_gbc_page1.json", HomebrewHub.Platform.GBC)
        assertEquals(listOf("ur4guys", "gbhack"), gbc.map { it.slug })
        assertEquals("https://raw.githubusercontent.com/gbdev/database/master/entries/gbhack/gbhack.gbc", gbc.last().downloadUrl)
        // A file in a sub-folder with a space in its name (from the same answer, no licence there).
        val palace = page("search_gbc_page1.json").entries.single { it.get("slug").asString == "syfartistry_memory-palace" }
            .deepCopy().apply { addProperty("license", "MIT") }
        val game = HomebrewHub.gameOf(palace, HomebrewHub.Platform.GBC)!!
        assertEquals("https://raw.githubusercontent.com/gbdev/database/master/entries/syfartistry_memory-palace/files/MEMORYPALACE_jam%20.gbc", game.downloadUrl)
        assertEquals("syfartistry_memory-palace-MEMORYPALACE_jam .gbc", FileParsingUtils.storageFileName(game.fileName))
    }

    @Test fun eachGameBecomesOneRowTaggedWithLicenceAndAuthor() {
        val max = games("search_gb_page1.json", HomebrewHub.Platform.GB).single { it.slug == "maxpirateeb" }
        val (row, tags) = HomebrewHub.row(max, "nintendo_gameboy", listOf("Game"))
        assertEquals("Max Pirate Extra Boom", row.name)
        assertEquals("nintendo_gameboy", row.consoleId)
        assertEquals(".gb", row.fileExtension)
        assertEquals(0L, row.fileSize)
        assertNull(row.expectedHash)
        assertEquals(listOf("MIT", "Lemmy Hawkins", "Game"), tags)
        // Titles keep their brackets: "(Boy)" is part of the name, not a tag.
        val life = games("search_gb_open_source_page2.json", HomebrewHub.Platform.GB, 2).single { it.slug == "game_boy_of_life" }
        assertEquals("Game (Boy) of Life", HomebrewHub.row(life, "nintendo_gameboy").first.name)
    }

    @Test fun separateLicencesForGameAndAssetsMustBothAllowSharing() {
        val page2 = games("search_gb_open_source_page2.json", HomebrewHub.Platform.GB, 2)
        assertEquals(listOf("rex-runner-gb", "game_boy_of_life", "bit_bang", "life-gb", "maxpirate", "gbccardgame", "gb-clock", "gbcshell"),
            page2.map { it.slug })
        // `license` with the older `assetLicense` spelling: both are tags.
        assertEquals(listOf("GPL-3.0-or-later", "CC-BY-SA-4.0"), page2.single { it.slug == "bit_bang" }.licences)
        // The release archive and the instruction booklet are not ROMs; the ROM next to them is used.
        assertEquals("pelmanism.gb", page2.single { it.slug == "gbccardgame" }.file)
        assertEquals("hoverdefender.gb", HomebrewHub.romOf(page("search_gb_open_source_page2.json", 2).entries.single { it.get("slug").asString == "hover-defender" }))

        val page1 = games("search_gb_open_source_page1.json", HomebrewHub.Platform.GB)
        // Only the game's licence given (`gameLicense`, no assets licence): the assets' terms are unknown.
        assertEquals(listOf("totp-gb", "maxpirateeb"), page1.map { it.slug })
        val assetsOnly = manifest("""{"assetsLicense":"CC-BY-4.0"}""")
        assertNull(HomebrewHub.licenceOf(assetsOnly))
        val closedAssets = manifest("""{"gameLicense":"MIT","assetsLicense":"All rights reserved"}""")
        assertNull(HomebrewHub.licenceOf(closedAssets))
        assertEquals(listOf("MIT", "CC-BY-NC-4.0"), HomebrewHub.licenceOf(manifest("""{"gameLicense":"MIT","assetsLicense":"CC-BY-NC-4.0"}""")))
    }

    @Test fun entriesWithTheSameRomNameGetDifferentNamesInTheLibrary() {
        // `game_boy_of_life` and `life-gb` both ship `life.gb`; the library keeps files by base name only.
        val page2 = games("search_gb_open_source_page2.json", HomebrewHub.Platform.GB, 2)
        val names = page2.map { FileParsingUtils.storageFileName(it.fileName).lowercase() }
        assertEquals(names.size, names.toSet().size)
        assertEquals("game_boy_of_life-life.gb", FileParsingUtils.storageFileName(page2.single { it.slug == "game_boy_of_life" }.fileName))
        assertEquals("life-gb", page2.single { it.slug == "life-gb" }.fileName.substringBefore('/'))
        // A name that already starts with the entry's slug stays as published.
        assertEquals("hoverdefender.gb", HomebrewHub.romOf(page("search_gb_open_source_page2.json", 2).entries.single { it.get("slug").asString == "hover-defender" }))
    }

    @Test fun anEntryWhoseLibraryNameCannotBeStoredIsLeftOut() {
        val base = page("search_gb_page1.json").entries.single { it.get("slug").asString == "maxpirateeb" }
        val long = base.deepCopy().apply {
            getAsJsonArray("files").forEach { it.asJsonObject.addProperty("filename", "x".repeat(190) + ".gb") }
            addProperty("slug", "y".repeat(100))
        }
        assertNull(HomebrewHub.gameOf(long, HomebrewHub.Platform.GB))
    }

    @Test fun licencesAreRecognisedByTheirSpdxIds() {
        listOf("MIT", "Zlib", "GPL-3.0-only", "GPL-3.0-or-later", "LGPL-2.1", "Apache-2.0", "BSD-3-Clause", "CC0-1.0", "Unlicense",
            "CC-BY-4.0", "CC-BY-SA-4.0", "CC-BY-NC-4.0", "CC-BY-NC-SA-4.0", "CC-BY-NC-ND-4.0", "CC-BY-ND-3.0", "cc-by-nc-nd-4.0")
            .forEach { assertTrue(it, HomebrewHub.isOpenLicence(it)) }
        listOf("", "Proprietary", "All rights reserved", "Freeware", "CC-BY", "CC-BY-5.0", "GPL", "Public Domain", "MIT or ask me")
            .forEach { assertFalse(it, HomebrewHub.isOpenLicence(it)) }
    }

    @Test fun romHacksOtherRepositoriesOtherConsolesAndUnsafePathsAreLeftOut() {
        val base = page("search_gb_page1.json").entries.single { it.get("slug").asString == "maxpirateeb" }
        fun variant(change: JsonObject.() -> Unit) = HomebrewHub.gameOf(base.deepCopy().apply(change), HomebrewHub.Platform.GB)
        assertNotNull(variant { })
        assertNull(variant { addProperty("typetag", "hackrom") })
        assertNull(variant { addProperty("basepath", "database-gba") })
        assertNull(variant { addProperty("baserepo", "https://github.com/someone/else") })
        assertNull(variant { addProperty("platform", "GBC") })
        assertNull(variant { addProperty("slug", "../other") })
        assertNull(variant { addProperty("slug", "a/b") })
        fun file(name: String) = variant { add("files", JsonParser.parseString("""[{"default":true,"filename":"$name","playable":true}]""")) }
        assertNull(file("../escape.gb"))
        assertNull(file("/abs.gb"))
        assertNull(file("files//x.gb"))
        assertNull(file("game.zip"))
        assertNotNull(file("CLK.GB"))
        // A file marked as not playable is not the ROM, and no files at all is no game.
        assertNull(variant { add("files", JsonParser.parseString("""[{"filename":"x.gb","playable":false}]""")) })
        assertNull(variant { remove("files") })
        // No title: the slug stands in.
        assertEquals("maxpirateeb", variant { remove("title") }!!.title)
        // Pages may shift while they are read: an entry seen twice is one game.
        assertEquals(1, HomebrewHub.games(listOf(base, base.deepCopy()), HomebrewHub.Platform.GB).size)
    }

    @Test fun developersMayBeOneNameOrAList() {
        val rupa = page("search_gbc_page1.json").entries.first()
        assertEquals(listOf("Maja Milas", "Giorgio Pomettini", "Noemi Frulio"), HomebrewHub.developersOf(rupa))
        assertEquals(emptyList<String>(), HomebrewHub.developersOf(manifest("{}")))
        assertEquals(listOf("A", "B", "C"), HomebrewHub.developersOf(manifest("""{"developer":["A"," B ","A","C","D"]}""")))
    }

    @Test fun sourceAddressesAreTheApiSearchForOneConsole() {
        assertEquals("https://hh3.gbdev.io/api/search?platform=GB", HomebrewHub.sourceUrl(HomebrewHub.Platform.GB))
        assertEquals("https://hh3.gbdev.io/api/search?platform=GBC", HomebrewHub.sourceUrl(HomebrewHub.Platform.GBC))
        assertEquals(HomebrewHub.Platform.GBC, HomebrewHub.platformOf("https://hh3.gbdev.io/api/search?platform=GBC"))
        assertEquals("https://hh3.gbdev.io/api/search?platform=GB&page=3&page_elements=10", HomebrewHub.pageUrl(HomebrewHub.Platform.GB, 3))
        listOf("http://hh3.gbdev.io/api/search?platform=GB", "https://hh3.gbdev.io/api/search?platform=GBA",
            "https://hh3.gbdev.io/api/search?platform=GB&tags=x", "https://hh3.gbdev.io/api/all", "https://evil.example/api/search?platform=GB",
            "https://hh3.gbdev.io/api/search", "romm://gb", "https://buildbot.libretro.com/assets/cores/Nintendo%20-%20GameBoy/")
            .forEach { assertFalse(it, HomebrewHub.isSource(it)) }
        // A source the user has never scanned, or set up differently, is read in full.
        val source = UrlEntry(HomebrewHub.sourceUrl(HomebrewHub.Platform.GB), ContentType.GAME)
        val hash = ListingCheck.hash(fixture("search_gb_page1.json").toByteArray())
        val previous = ListingCheck.Previous(true, ListingCheck.fingerprint(source), null, null, hash, null, rows = 4, files = 4)
        assertTrue(ListingCheck.sameBody(source, previous, force = false, bodyHash = hash))
        assertFalse(ListingCheck.sameBody(source, previous, force = true, bodyHash = hash))
        assertFalse(ListingCheck.sameBody(source, previous.copy(rows = 3), force = false, bodyHash = hash))
        assertFalse(ListingCheck.sameBody(source, previous, force = false, bodyHash = ListingCheck.hash(fixture("search_gbc_page1.json").toByteArray())))
    }
}
