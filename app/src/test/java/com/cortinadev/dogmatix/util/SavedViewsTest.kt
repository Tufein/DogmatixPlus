package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedViewsTest {
    @Test fun `saved views survive json and make a deep link`() {
        val view = LibraryView("v1", "PS1 Europe", "final", setOf("sony_playstation"), setOf("Europe", "Nl"), newOnly = true, collectionId = 3)
        assertEquals(listOf(view), LibraryViews.fromJson(LibraryViews.toJson(listOf(view))))
        val link = view.deepLink()
        assertTrue(link.startsWith("dogmatix://library?console=sony_playstation"))
        val request = DeepLinkParser.parse(link)!!
        assertEquals(setOf("Europe", "Nl"), request.tags)
        assertEquals("final", request.query)
        assertEquals(true, request.newOnly)
        assertEquals(3L, request.collectionId)
        assertTrue(LibraryViews.fromJson("nonsense").isEmpty())
    }
}
