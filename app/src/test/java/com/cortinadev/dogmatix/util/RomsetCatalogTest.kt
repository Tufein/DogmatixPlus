package com.cortinadev.dogmatix.util

import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RomsetCatalogTest {
    private fun document() = File("src/main/assets/romsets.json").readText()

    @Test fun bundledCatalogUsesUniqueSourcesWithRealFileTablesAndEncodedFolderNames() {
        val catalog = RomsetCatalog.parse(document())
        assertEquals(23, catalog.entries.size)
        assertEquals(134, catalog.entries.sumOf { it.fileCount })
        assertEquals(catalog.entries.size, catalog.entries.map { it.url }.toSet().size)
        catalog.entries.forEach {
            assertTrue(it.url.startsWith("https://buildbot.libretro.com/assets/cores/"))
            assertFalse(it.url.contains(' '))
            assertTrue(it.url.endsWith('/'))
        }
    }

    @Test fun unknownSchemaDuplicateIdsAndUntrustedPublisherAreRejected() {
        for (change in listOf("schema", "publisher", "duplicate")) {
            val root = JsonParser.parseString(document()).asJsonObject
            when (change) {
                "schema" -> root.addProperty("schema", 2)
                "publisher" -> root.addProperty("publisher", "Unknown")
                else -> root.getAsJsonArray("entries").add(root.getAsJsonArray("entries")[0])
            }
            assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(root.toString()) }
        }
    }

    @Test fun unsafeFolderPathsWrongIdsAndFractionalCountsAreRejected() {
        for ((field, value) in listOf("folder" to "../other", "folder" to "wrong\\folder", "id" to "foreign_console", "fileCount" to "1.5")) {
            val root = JsonParser.parseString(document()).asJsonObject
            val entry = root.getAsJsonArray("entries")[0].asJsonObject
            if (field == "fileCount") entry.add(field, JsonParser.parseString(value)) else entry.addProperty(field, value)
            assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(root.toString()) }
        }
    }

    @Test fun oversizedCatalogIsRejectedBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) { RomsetCatalog.parse(" ".repeat(RomsetCatalog.MAX_BYTES + 1)) }
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
