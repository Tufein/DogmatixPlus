package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppShortcutsTest {
    @Test fun `plan puts downloads first, then views, then consoles by name, within the limit`() {
        val downloads = AppShortcuts.downloads("Downloads", "downloads")
        val view = LibraryView(id = "v1", name = "GBA Europe", consoles = setOf("nintendo_gameboy_advance"), tags = setOf("Europe"))
        val plan = AppShortcuts.plan(downloads, listOf("sony_psx" to "PlayStation", "nintendo_snes" to "Super Nintendo", "atari_2600" to "Atari 2600"), listOf(view), max = 4)
        assertEquals(listOf("section:downloads", "view:v1", "console:atari_2600", "console:sony_psx"), plan.map { it.id })
        assertEquals("downloads", plan[0].route)
        assertNull(plan[0].deepLink)
        assertEquals("nintendo_gameboy_advance", plan[1].consoleId)
        assertEquals("dogmatix://library?console=sony_psx", plan[3].deepLink)
        assertEquals(DeepLinkParser.parse(plan[3].deepLink)?.consoles, setOf("sony_psx"))
    }
}
