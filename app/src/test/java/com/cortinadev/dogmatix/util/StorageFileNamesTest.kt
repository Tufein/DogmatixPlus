package com.cortinadev.dogmatix.util

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class StorageFileNamesTest {
    @Test fun sourceIdentityIsSeparateFromTheSafeStorageBasename() {
        for (reference in listOf("Game.gba", "./Game.gba", "nested/Game.gba", "https://example.invalid/gba/Game.gba?token=abc#download", "//example.invalid/gba/Game.gba")) {
            assertEquals("Game.gba", FileParsingUtils.storageFileName(reference))
        }
        assertEquals("C++ Game.gba", FileParsingUtils.storageFileName("nested/C++%20Game.gba"))
        assertEquals("C++ Game.gba", FileParsingUtils.decodeUrlEncodedFileName("C++%20Game.gba"))
        assertEquals("C++ Game.gba", FileParsingUtils.storageFileName("C%2B%2B%20Game.gba"))
    }

    @Test fun unsafeAndAmbiguousEncodedBasenamesAreRejected() {
        for (name in listOf("../", ".", "..", "Game%2Fother.gba", "Game%5Cother.gba", "Game%00.gba", "Game%0A.gba", "bad%2.gba", "bad%C3%28.gba", "C%3A.gba")) {
            assertThrows("Accepted unsafe file reference $name", IllegalArgumentException::class.java) { FileParsingUtils.storageFileName(name) }
        }
    }

    @Test fun listingRowsKeepTheirHrefButUseThePathExtension() {
        val reference = "nested/C++%20Game.gba?token=abc#download"
        val row = Jsoup.parse("<table><tr><td class='link'><a href='$reference'>Game</a></td><td class='size'>20 B</td></tr></table>").selectFirst("tr")!!
        val file = FileParsingUtils.parseFileFromRow(row, "https://example.invalid/gba", "gba").first!!
        assertEquals(reference, file.fileName)
        assertEquals(".gba", file.fileExtension)
        assertEquals("https://example.invalid/gba/nested/C++%20Game.gba?token=abc#download", file.downloadUrl)
    }

    @Test fun sourceUrlsResolveDirectoriesRootLinksAndProtocolRelativeLinks() {
        assertEquals("https://example.invalid/gba/Game.gba", FileParsingUtils.buildDownloadUrl("https://example.invalid/gba", "./Game.gba"))
        assertEquals("https://example.invalid/Game.gba", FileParsingUtils.buildDownloadUrl("https://example.invalid/gba/", "/Game.gba"))
        assertEquals("https://mirror.invalid/Game.gba", FileParsingUtils.buildDownloadUrl("https://example.invalid/gba/", "//mirror.invalid/Game.gba"))
        assertEquals("https://example.invalid/C++%20Game.gba", FileParsingUtils.buildDownloadUrl("https://example.invalid/gba/", "../C++ Game.gba"))
    }
}
