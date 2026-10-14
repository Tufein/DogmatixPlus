package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RomsetCatalogTest {
    private fun document() = File("src/main/assets/romsets.json").readText()
    private fun root() = JsonParser.parseString(document()).asJsonObject

    /** A second reviewed provider, only for these tests. */
    private val trustedWithExample = RomsetCatalog.TRUSTED +
        ("example_homebrew" to RomsetCatalog.Trusted("https://example.org/homebrew/", "https://example.org/homebrew/list"))

    private fun withExampleProvider(): JsonObject = root().apply {
        getAsJsonArray("providers").add(JsonObject().apply {
            addProperty("id", "example_homebrew")
            addProperty("name", "Example Homebrew Archive")
            addProperty("homepage", "https://example.org/")
            addProperty("provenance", "https://example.org/homebrew/list")
            addProperty("licence", "public_domain")
            addProperty("baseUrl", "https://example.org/homebrew/")
        })
        getAsJsonArray("entries").add(JsonObject().apply {
            addProperty("provider", "example_homebrew")
            addProperty("id", "nintendo_gameboy")
            addProperty("manufacturerId", "nintendo")
            addProperty("manufacturerName", "Nintendo")
            addProperty("consoleKey", "gameboy")
            addProperty("consoleName", "Game Boy")
            addProperty("shortName", "GB")
            add("aliases", JsonArray().apply { add("gb") })
            addProperty("folder", "Game Boy")
            addProperty("fileCount", 3)
        })
    }

    /** The bundled catalogue written the way 3.0 wrote it (schema 1, one publisher). */
    private fun legacy(): JsonObject = root().apply {
        addProperty("schema", 1)
        remove("providers")
        addProperty("publisher", "Libretro")
        addProperty("provenance", "https://github.com/libretro/libretro-content")
        val libretro = getAsJsonArray("entries").filter { it.asJsonObject.get("provider").asString == "libretro" }
        add("entries", JsonArray().apply { libretro.forEach { add(it) } })
        getAsJsonArray("entries").forEach { it.asJsonObject.remove("provider") }
    }

    @Test fun bundledCatalogUsesUniqueSourcesWithRealFileTablesAndEncodedFolderNames() {
        val catalog = RomsetCatalog.parse(document())
        val libretro = catalog.entriesOf("libretro")
        assertEquals(29, libretro.size)
        assertEquals(317, libretro.sumOf { it.fileCount })
        assertEquals(30, catalog.entries.size)
        val urls = catalog.entries.flatMap { it.urls }
        assertEquals(urls.size, urls.toSet().size)
        catalog.entries.forEach { e ->
            e.urls.forEach {
                assertTrue(it.startsWith(e.baseUrl))
                assertFalse(it.contains(' '))
                assertTrue(it.endsWith('/'))
            }
        }
        libretro.forEach { assertTrue(it.url.startsWith("https://buildbot.libretro.com/assets/cores/")) }
        assertEquals("https://buildbot.libretro.com/assets/cores/Nintendo%20-%20GameCube%20-%20Wii/",
            catalog.entries.single { it.id == "nintendo_gamecube" }.url)
    }

    @Test fun everyProviderIsAttributedWithHomepageProvenanceAndTerms() {
        val catalog = RomsetCatalog.parse(document())
        assertEquals(listOf("libretro", "scummvm"), catalog.providers.map { it.id })
        val libretro = catalog.provider("libretro")!!
        assertEquals("Libretro", libretro.name)
        assertEquals(RomsetCatalog.Licence.PUBLISHED, libretro.licence)
        assertEquals("https://github.com/libretro/libretro-content", libretro.provenance)
        val scummvm = catalog.provider("scummvm")!!
        assertEquals("ScummVM", scummvm.name)
        assertEquals(RomsetCatalog.Licence.PUBLISHED, scummvm.licence)
        assertEquals("https://www.scummvm.org/games/", scummvm.provenance)
        assertEquals("https://downloads.scummvm.org/frs/extras/", scummvm.baseUrl)
        catalog.providers.forEach { assertTrue(it.homepage.startsWith("https://")) }
        assertTrue(catalog.entries.all { catalog.provider(it.providerId) != null })
    }

    @Test fun theScummVmEntryBringsOneFolderPerFreewareGameTogetherUnderOneConsole() {
        val catalog = RomsetCatalog.parse(document())
        val entry = catalog.entriesOf("scummvm").single()
        assertEquals("pc_scummvm", entry.id)
        assertEquals(9, entry.urls.size)
        assertEquals(entry.url, entry.urls.first())
        assertTrue("https://downloads.scummvm.org/frs/extras/Drascula_%20The%20Vampire%20Strikes%20Back/" in entry.urls)
        assertTrue("https://downloads.scummvm.org/frs/extras/Beneath%20a%20Steel%20Sky/" in entry.urls)
        assertEquals(57, entry.fileCount)
        // A console of the user's own with that name gets the folders; otherwise a new one is made.
        assertEquals(RomsetCatalog.Mapping("my_scummvm", "ScummVM", true),
            RomsetCatalog.mapConsole(entry, listOf(RomsetCatalog.ConsoleRef("my_scummvm", "ScummVM"))))
        assertEquals(RomsetCatalog.Mapping("pc_scummvm", "ScummVM", false),
            RomsetCatalog.mapConsole(entry, listOf(RomsetCatalog.ConsoleRef("nintendo_nes", "NES"))))
        assertEquals(setOf("scummvm"), RomsetCatalog.filter(catalog, null, "scumm").map { it.providerId }.toSet())
    }

    @Test fun anEntryMayListSeveralFoldersButOnlyValidUniqueOnes() {
        fun scummvm(change: (JsonObject) -> Unit): String = root().also { r ->
            change(r.getAsJsonArray("entries").last().asJsonObject)
        }.toString()
        fun folders(vararg names: String) = JsonArray().apply { names.forEach { add(it) } }
        // Both spellings at once, an escaping or duplicated folder, too many folders, none at all.
        assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(scummvm { it.addProperty("folder", "Dreamweb") }) }
        assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(scummvm { it.add("folders", folders("Dreamweb", "../x")) }) }
        assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(scummvm { it.add("folders", folders("Dreamweb", "a/b")) }) }
        assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(scummvm { it.add("folders", folders("Dreamweb", "Dreamweb")) }) }
        assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(scummvm { it.add("folders", folders()) }) }
        assertThrows(IllegalArgumentException::class.java) {
            RomsetCatalog.parse(scummvm { it.add("folders", folders(*Array(RomsetCatalog.MAX_FOLDERS + 1) { n -> "Game $n" })) })
        }
        // One address may not be offered by two entries.
        val clash = root().also { r ->
            val entries = r.getAsJsonArray("entries")
            entries.add(JsonParser.parseString(entries.last().toString()).asJsonObject.apply {
                addProperty("id", "pc_scummvm_more"); addProperty("consoleKey", "scummvm_more")
                add("folders", folders("Dreamweb"))
            })
        }
        assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(clash.toString()) }
        // A single `folder` still reads as before.
        val single = RomsetCatalog.parse(scummvm { it.remove("folders"); it.addProperty("folder", "Dreamweb") })
        assertEquals(1, single.entriesOf("scummvm").single().urls.size)
    }

    @Test fun schemaOneCatalogsStillParseAsTheLibretroProvider() {
        val old = RomsetCatalog.parse(legacy().toString())
        val now = RomsetCatalog.parse(document())
        assertEquals(now.entriesOf("libretro").map { it.url }, old.entries.map { it.url })
        assertEquals(listOf("libretro"), old.providers.map { it.id })
        val bad = legacy().apply { addProperty("publisher", "Unknown") }
        assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(bad.toString()) }
    }

    @Test fun unknownSchemaDuplicateIdsAndUntrustedPublisherAreRejected() {
        for (change in listOf("schema", "provider", "duplicate", "baseUrl", "provenance", "licence", "entryProvider", "homepage")) {
            val root = root()
            val provider = root.getAsJsonArray("providers")[0].asJsonObject
            val entry = root.getAsJsonArray("entries")[0].asJsonObject
            when (change) {
                "schema" -> root.addProperty("schema", 3)
                "provider" -> provider.addProperty("id", "unknown")
                "duplicate" -> root.getAsJsonArray("entries").add(root.getAsJsonArray("entries")[0])
                "baseUrl" -> provider.addProperty("baseUrl", "https://mirror.example/assets/cores/")
                "provenance" -> provider.addProperty("provenance", "https://example.org/other")
                "licence" -> provider.addProperty("licence", "trust_me")
                "entryProvider" -> entry.addProperty("provider", "example_homebrew")
                else -> provider.addProperty("homepage", "http://www.libretro.com/")
            }
            assertThrows(change, IllegalArgumentException::class.java) { RomsetCatalog.parse(root.toString()) }
        }
    }

    @Test fun unsafeFolderPathsWrongIdsAndFractionalCountsAreRejected() {
        for ((field, value) in listOf("folder" to "../other", "folder" to "wrong\\folder", "folder" to "..", "id" to "foreign_console", "fileCount" to "1.5")) {
            val root = root()
            val entry = root.getAsJsonArray("entries")[0].asJsonObject
            if (field == "fileCount") entry.add(field, JsonParser.parseString(value)) else entry.addProperty(field, value)
            assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(root.toString()) }
        }
    }

    @Test fun oversizedCatalogIsRejectedBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(" ".repeat(RomsetCatalog.MAX_BYTES + 1)) }
    }

    @Test fun aReviewedSecondProviderMayOfferTheSameConsoleAndIsGroupedAndFilteredApart() {
        val doc = withExampleProvider().toString()
        // Not reviewed in the app: refused.
        assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(doc) }
        val catalog = RomsetCatalog.parse(doc, trustedWithExample)
        assertEquals(listOf("libretro", "scummvm", "example_homebrew"), catalog.providers.map { it.id })
        val gameBoys = catalog.entries.filter { it.id == "nintendo_gameboy" }
        assertEquals(2, gameBoys.size)
        assertEquals(2, gameBoys.map { it.key }.toSet().size)
        assertEquals("https://example.org/homebrew/Game%20Boy/", gameBoys.single { it.providerId == "example_homebrew" }.url)
        assertEquals(RomsetCatalog.Licence.PUBLIC_DOMAIN, catalog.provider("example_homebrew")!!.licence)

        val onlyExample = RomsetCatalog.filter(catalog, "example_homebrew", "")
        assertEquals(listOf("nintendo_gameboy"), onlyExample.map { it.id })
        val groups = RomsetCatalog.groups(catalog, RomsetCatalog.filter(catalog, null, "game boy"))
        assertEquals(listOf("libretro", "example_homebrew"), groups.map { it.first.id })
        // The provider's name is searchable too.
        assertEquals(setOf("example_homebrew"), RomsetCatalog.filter(catalog, null, "Example Homebrew").map { it.providerId }.toSet())
    }

    @Test fun aSecondProviderCannotPointOutsideItsReviewedAddress() {
        val root = withExampleProvider()
        root.getAsJsonArray("providers").last().asJsonObject.addProperty("baseUrl", "https://example.org/other/")
        assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(root.toString(), trustedWithExample) }
    }

    @Test fun searchMatchesConsoleNamesShortNamesAndFolderAliases() {
        val catalog = RomsetCatalog.parse(document())
        assertEquals(listOf("sega_genesis"), RomsetCatalog.filter(catalog, null, "megadrive").map { it.id })
        assertEquals(listOf("sony_playstation"), RomsetCatalog.filter(catalog, null, "psx").map { it.id })
        assertTrue(RomsetCatalog.filter(catalog, null, "Libretro").size == catalog.entriesOf("libretro").size)
        assertTrue(RomsetCatalog.filter(catalog, null, "zzz").isEmpty())
    }

    @Test fun entriesGoToTheUsersOwnConsoleThroughTheAliasRules() {
        val catalog = RomsetCatalog.parse(document())
        val gb = catalog.entries.single { it.id == "nintendo_gameboy" }
        val genesis = catalog.entries.single { it.id == "sega_genesis" }
        val psx = catalog.entries.single { it.id == "sony_playstation" }

        // Same id: that console.
        assertEquals(RomsetCatalog.Mapping("nintendo_gameboy", "My GB", true),
            RomsetCatalog.mapConsole(gb, listOf(RomsetCatalog.ConsoleRef("nintendo_gameboy", "My GB"))))
        // A hand-made console with another id but the same system name.
        val handMade = listOf(RomsetCatalog.ConsoleRef("nintendo_game_boy", "Game Boy"), RomsetCatalog.ConsoleRef("nintendo_game_boy_color", "Game Boy Color"))
        assertEquals("nintendo_game_boy", RomsetCatalog.mapConsole(gb, handMade).consoleId)
        // A folder alias configured on the console.
        val aliased = listOf(RomsetCatalog.ConsoleRef("sega_md", "Sega 16-bit", aliases = listOf("megadrive")))
        assertEquals(RomsetCatalog.Mapping("sega_md", "Sega 16-bit", true), RomsetCatalog.mapConsole(genesis, aliased))
        // Nothing matching: a new console with the entry's id and name.
        assertEquals(RomsetCatalog.Mapping("sony_playstation", "PlayStation", false), RomsetCatalog.mapConsole(psx, handMade))
    }

    @Test fun anAmbiguousMatchMakesANewConsoleInsteadOfGuessing() {
        val catalog = RomsetCatalog.parse(document())
        val gb = catalog.entries.single { it.id == "nintendo_gameboy" }
        val two = listOf(RomsetCatalog.ConsoleRef("handheld_a", "Handheld A", aliases = listOf("gb")), RomsetCatalog.ConsoleRef("handheld_b", "Handheld B", aliases = listOf("gameboy")))
        assertFalse(RomsetCatalog.mapConsole(gb, two).existing)
        // The same manufacturer settles it.
        val oneNintendo = two + RomsetCatalog.ConsoleRef("nintendo_handheld", "Handheld", aliases = listOf("gb"))
        assertEquals("nintendo_handheld", RomsetCatalog.mapConsole(gb, oneNintendo).consoleId)
        // SNES never lands on NES.
        val snes = catalog.entries.single { it.id == "nintendo_super_nintendo_entertainment_system" }
        assertFalse(RomsetCatalog.mapConsole(snes, listOf(RomsetCatalog.ConsoleRef("nintendo_nes", "NES"))).existing)
    }

    @Test fun h5aiSourcesPreserveRootRelativeDownloadLinksAndParseTheActualSizeColumn() {
        // Same markup as the publisher's fallback directory: icon/name/date/size.
        val html = """<table><tr><td class="fb-i"><img alt="file"></td>
            <td class="fb-n"><a href="/assets/cores/Nintendo%20-%20Nintendo%20Entertainment%20System/Alter%20Ego.nes">Alter Ego.nes</a></td>
            <td class="fb-d">2026-10-09 23:35</td><td class="fb-s">40 KB</td></tr></table>"""
        val row = Jsoup.parse(html).selectFirst("tr")!!
        val base = RomsetCatalog.parse(document()).entries.first { it.shortName == "NES" }.url
        val file = FileParsingUtils.parseFileFromRow(row, base, "nintendo_nintendo_entertainment_system").first!!
        assertEquals("Alter Ego.nes", file.name)
        assertEquals(40_000L, file.fileSize)
        assertEquals("Alter Ego.nes", FileParsingUtils.storageFileName(file.fileName))
        assertEquals("https://buildbot.libretro.com/assets/cores/Nintendo%20-%20Nintendo%20Entertainment%20System/Alter%20Ego.nes", file.downloadUrl)
    }
}
