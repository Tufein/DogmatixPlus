package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.UrlEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesExportTest {
    @Test fun `reserve addresses survive a round trip and never repeat the address itself`() {
        val entries = listOf(UrlEntry("https://a.example/gba/", mirrors = listOf("https://b.example/gba/", "https://c.example/gba/")))
        val back = SourcesJson.parseUrlEntries(SourcesJson.serializeUrlEntries(entries))
        assertEquals(entries, back)
        val weird = SourcesJson.parseUrlEntries("""[{"url":"https://a/","mirrors":["https://a/","https://b/"]}]""")
        assertEquals(listOf("https://b/"), weird.single().mirrors)
        assertTrue(SourcesJson.parseUrlEntries("""[{"url":"https://a/"}]""").single().mirrors.isEmpty())
    }

    @Test fun `collections travel in the export document`() {
        val doc = SourcesJson.serializeDocument(emptyList(), collections = listOf(SourceCollection("Couch co-op", listOf("snes" to "a.zip", "n64" to "b.zip"))))
        val back = SourcesJson.parseCollections(doc)
        assertEquals(1, back.size)
        assertEquals("Couch co-op", back.single().name)
        assertEquals(listOf("snes" to "a.zip", "n64" to "b.zip"), back.single().items)
        assertTrue(SourcesJson.parseCollections("{}").isEmpty())
        assertTrue(SourcesJson.parseCollections("not json").isEmpty())
    }

    @Test fun `deep links can ask for new games and a collection`() {
        val request = DeepLinkParser.parse("dogmatix://library?new=1&collection=7")!!
        assertEquals(true, request.newOnly)
        assertEquals(7L, request.collectionId)
        assertNull(DeepLinkParser.parse("dogmatix://library?collection=abc")!!.collectionId)
    }
}
