package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EsdeArtworkTest {
    @Test fun `es-de artwork paths and gamelist entries`() {
        assertEquals("downloaded_media/gba/covers/Game (Europe).png", EsdeArtwork.coverPath("gba", "Game (Europe).zip", "PNG"))
        assertEquals("webp", EsdeArtwork.imageExtension("https://x/y/cover.webp?size=2"))
        assertEquals("jpg", EsdeArtwork.imageExtension("https://x/y/cover"))
        assertEquals("20041121T000000", EsdeArtwork.esdeDate("2004-11-21"))
        assertNull(EsdeArtwork.esdeDate("soon"))
        val fresh = EsdeArtwork.gamelistWithGame(null, "Tom & Jerry.zip", "Tom & Jerry", "A <cat>.", "1993-05", "Hudson", "Action")!!
        assertTrue(fresh.contains("<path>./Tom &amp; Jerry.zip</path>"))
        assertTrue(fresh.contains("<desc>A &lt;cat&gt;.</desc>"))
        assertTrue(fresh.contains("<releasedate>19930501T000000</releasedate>"))
        assertNull(EsdeArtwork.gamelistWithGame(fresh, "Tom & Jerry.zip", "x", "y"))   // ES-DE's own entry wins
        val added = EsdeArtwork.gamelistWithGame(fresh, "Other.zip", "Other", "")!!
        assertEquals(2, Regex("<game>").findAll(added).count())
    }
}
