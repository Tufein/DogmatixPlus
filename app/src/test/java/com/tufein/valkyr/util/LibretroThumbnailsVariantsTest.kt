package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4.3.0: more names and kinds of picture to try on libretro-thumbnails. */
class LibretroThumbnailsVariantsTest {

    @Test fun `a european file also tries the american and the region free name`() {
        val names = LibretroThumbnails.boxartNames("Super Mario World (Europe) (Rev 1).sfc", listOf("Europe", "USA"))
        assertEquals(listOf("Super Mario World (Europe) (Rev 1)", "Super Mario World (Europe)"), names.take(2))
        assertTrue(names.contains("Super Mario World (USA)"))
        assertTrue(names.contains("Super Mario World (USA, Europe)"))
        assertTrue(names.contains("Super Mario World (World)"))
        assertEquals("Super Mario World", names.last())
        assertTrue(names.size <= LibretroThumbnails.MAX_BOXART_NAMES)
        assertEquals(names, names.distinct())
    }

    @Test fun `preferred regions come first and use no-intro spelling`() {
        val names = LibretroThumbnails.boxartNames("Game.zip", listOf("UK", "Japan"))
        assertEquals(listOf("Game", "Game (United Kingdom)", "Game (Japan)", "Game (USA, Europe)"), names.take(4))
    }

    @Test fun `only the first three preferred regions and the cap`() {
        val many = listOf("World", "Europe", "USA", "Japan", "Australia", "Asia", "Korea", "Brazil", "Canada")
        val names = LibretroThumbnails.boxartNames("Game (Brazil) (Disc 1).chd", many)
        assertTrue(names.none { it == "Game (Australia)" })
        assertTrue(names.size <= LibretroThumbnails.MAX_BOXART_NAMES)
        assertTrue(names.contains("Game"))
    }

    @Test fun `names are made safe like libretro does`() {
        val names = LibretroThumbnails.boxartNames("Tom & Jerry (Europe).gb", emptyList())
        assertTrue(names.all { '&' !in it })
        assertTrue(names.contains("Tom _ Jerry (USA)"))
    }

    @Test fun `title screens and snaps only when allowed, after all box art`() {
        val boxOnly = LibretroThumbnails.plan("Game (USA).nes", emptyList(), nonBoxArt = false)
        assertTrue(boxOnly.all { it.first == LibretroThumbnails.Art.BOXART })
        val all = LibretroThumbnails.plan("Game (USA).nes", emptyList(), nonBoxArt = true)
        val firstOther = all.indexOfFirst { it.first != LibretroThumbnails.Art.BOXART }
        assertEquals(boxOnly.size, firstOther)
        assertEquals(LibretroThumbnails.Art.TITLE to "Game (USA)", all[firstOther])
        assertEquals(LibretroThumbnails.Art.SNAP, all.last().first)
        val sys = LibretroThumbnails.systemFor("nes")!!
        assertEquals(
            "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Nintendo_Entertainment_System/master/Named_Titles/Game%20%28USA%29.png",
            LibretroThumbnails.artUrl(sys, LibretroThumbnails.Art.TITLE, "Game (USA)")
        )
    }
}
