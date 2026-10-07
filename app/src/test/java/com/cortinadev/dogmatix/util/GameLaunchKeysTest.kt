package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameLaunchKeysTest {
    private data class H(val key: String, val pkg: String)

    private val duck = H(GameLaunchKeys.catalogue("duckstation"), "com.github.stenzek.duckstation")
    private val raPcsx = H(GameLaunchKeys.catalogue("retroarch", "pcsx_rearmed"), "com.retroarch")
    private val raSwan = H(GameLaunchKeys.catalogue("retroarch", "swanstation"), "com.retroarch")
    private val viewer = H("org.example.viewer/org.example.viewer.Open", "org.example.viewer")
    private val handlers = listOf(duck, raPcsx, raSwan, viewer)

    private fun resolve(stored: String?) = GameLaunchKeys.resolve(stored, handlers, { it.key }, { it.pkg })

    @Test fun `catalogue keys round-trip`() {
        assertEquals("catalog:duckstation", duck.key)
        assertEquals("catalog:retroarch|pcsx_rearmed", raPcsx.key)
        assertEquals("catalog:retroarch", GameLaunchKeys.catalogue("retroarch", " "))
        assertEquals("retroarch" to "pcsx_rearmed", GameLaunchKeys.parseCatalogue(raPcsx.key))
        assertEquals("duckstation" to null, GameLaunchKeys.parseCatalogue(" catalog:duckstation "))
        assertNull(GameLaunchKeys.parseCatalogue("catalog:"))
        assertNull(GameLaunchKeys.parseCatalogue(viewer.key))
        assertNull(GameLaunchKeys.parseCatalogue(null))
    }

    @Test fun `component package only for components`() {
        assertEquals("org.example.viewer", GameLaunchKeys.componentPackage(viewer.key))
        assertNull(GameLaunchKeys.componentPackage(duck.key))
        assertNull(GameLaunchKeys.componentPackage(GameLaunchKeys.AUTOMATIC))
        assertNull(GameLaunchKeys.componentPackage("garbage"))
        assertNull(GameLaunchKeys.componentPackage("/cls"))
    }

    @Test fun `stored values resolve exactly, automatic takes the first`() {
        assertEquals(raSwan, resolve(raSwan.key))
        assertEquals(viewer, resolve(viewer.key))
        assertEquals(duck, resolve(GameLaunchKeys.AUTOMATIC))
        assertNull(resolve(null))
        assertNull(resolve(""))
        assertNull(GameLaunchKeys.resolve(GameLaunchKeys.AUTOMATIC, emptyList<H>(), { it.key }, { it.pkg }))
    }

    @Test fun `a component stored by 2_3_0 for a catalogue app maps to its recipe`() {
        assertEquals(duck, resolve("com.github.stenzek.duckstation/com.github.stenzek.duckstation.MainActivity"))
        assertEquals(raPcsx, resolve("com.retroarch/com.retroarch.browser.mainmenu.MainMenuActivity"))
    }

    @Test fun `an uninstalled or unknown choice is no match, so Play asks again`() {
        assertNull(resolve(GameLaunchKeys.catalogue("dolphin")))
        assertNull(resolve(GameLaunchKeys.catalogue("retroarch", "beetle_unknown")))
        assertNull(resolve("org.gone.app/org.gone.app.Main"))
        assertNull(resolve("not a value"))
    }

    @Test fun `catalogue handlers come first and generic ones are deduplicated by package`() {
        val genericDuck = H("com.github.stenzek.duckstation/x.View", "com.github.stenzek.duckstation")
        val other1 = H("org.other/org.other.A", "org.other")
        val other2 = H("org.other/org.other.B", "org.other")
        val merged = GameLaunchKeys.merge(listOf(duck, raPcsx), listOf(genericDuck, other1, other2, viewer)) { it.pkg }
        assertEquals(listOf(duck, raPcsx, other1, viewer), merged)
    }
}
