package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickShortcutsTest {
    private val downloads = AppShortcuts.downloads("Downloads", "downloads")
    private val consoles = listOf("sony_psx" to "PlayStation", "atari_2600" to "Atari 2600")

    @Test fun `quick shortcuts lead the plan around downloads`() {
        val plan = AppShortcuts.plan(
            downloads, consoles, emptyList(), max = 10,
            surprise = AppShortcuts.surprise("Surprise me"), search = AppShortcuts.search("Search")
        )
        assertEquals(
            listOf("section:surprise", "section:downloads", "section:search", "console:atari_2600", "console:sony_psx"),
            plan.map { it.id }
        )
    }

    @Test fun `the quick shortcuts carry their action and are filed as sections`() {
        val surprise = AppShortcuts.surprise("Surprise me")
        val search = AppShortcuts.search("Search")
        assertEquals(QuickAction.SURPRISE, surprise.quick)
        assertEquals(QuickAction.SEARCH, search.quick)
        assertNull(surprise.deepLink)
        assertNull(search.route)
        // The shortcut picker (CreateShortcutActivity) groups by this prefix.
        assertTrue(surprise.id.startsWith("section:") && search.id.startsWith("section:"))
        assertEquals(setOf(AppShortcuts.SURPRISE_ID, AppShortcuts.SEARCH_ID), setOf(surprise.id, search.id))
    }

    @Test fun `without quick shortcuts the plan is what it was`() {
        assertEquals(listOf("section:downloads", "console:atari_2600", "console:sony_psx"), AppShortcuts.plan(downloads, consoles, emptyList()).map { it.id })
    }

    @Test fun `the limit counts the quick shortcuts, so the console ones give way`() {
        val plan = AppShortcuts.plan(
            downloads, consoles, emptyList(), max = 4,
            surprise = AppShortcuts.surprise("Surprise me"), search = AppShortcuts.search("Search")
        )
        assertEquals(4, plan.size)
        assertEquals(listOf("section:surprise", "section:downloads", "section:search", "console:atari_2600"), plan.map { it.id })
    }
}
