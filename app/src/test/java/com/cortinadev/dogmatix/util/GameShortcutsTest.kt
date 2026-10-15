package com.cortinadev.dogmatix.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameShortcutsTest {
    private val downloads = AppShortcuts.downloads("Downloads", "downloads")
    private val surprise = AppShortcuts.surprise("Surprise me")
    private val search = AppShortcuts.search("Search")
    private val consoles = listOf("sony_psx" to "PlayStation", "atari_2600" to "Atari 2600")
    private val view = LibraryView(id = "v1", name = "GBA Europe", consoles = setOf("nintendo_gba"), tags = setOf("Europe"))

    private fun game(console: String, file: String, label: String = file) = AppShortcuts.game(console, file, label)!!

    @Test fun `a library key survives the trip through the intent and the shortcut id`() {
        val key = GameShortcuts.key("nintendo_gba", "Pokemon - Emerald (USA).zip")!!
        assertEquals("nintendo_gba|Pokemon - Emerald (USA).zip", key.value)
        assertEquals("game:nintendo_gba|Pokemon - Emerald (USA).zip", key.shortcutId)
        assertEquals(key, GameShortcuts.parse(key.value))
        assertEquals(key, GameShortcuts.parseId(key.shortcutId))
        assertEquals(key, GameShortcuts.fromIntent(GameShortcuts.ACTION_PLAY, key.value))
    }

    @Test fun `the console id ends at the first bar, a file name may hold one`() {
        val key = GameShortcuts.parse("sony_psx|Odd|Name.chd")!!
        assertEquals("sony_psx", key.consoleId)
        assertEquals("Odd|Name.chd", key.fileName)
    }

    @Test fun `malformed keys and other intents are refused`() {
        assertNull(GameShortcuts.parse(null))
        assertNull(GameShortcuts.parse("no separator"))
        assertNull(GameShortcuts.parse("|Game.zip"))
        assertNull(GameShortcuts.parse("gba|"))
        assertNull(GameShortcuts.parse("gba|   "))
        assertNull(GameShortcuts.parse("gba|Bad\u0000name.zip"))
        assertNull(GameShortcuts.key("gb|a", "Game.zip"))
        assertNull(GameShortcuts.key("gba", "x".repeat(300)))
        assertNull(GameShortcuts.key("x".repeat(65), "Game.zip"))
        assertNotNull(GameShortcuts.key("gba", "x".repeat(255)))
        assertNull(GameShortcuts.parseId("console:gba"))
        assertNull(GameShortcuts.fromIntent("android.intent.action.VIEW", "gba|Game.zip"))
        assertNull(GameShortcuts.fromIntent(QuickActions.ACTION_QUICK, "gba|Game.zip"))
        assertNull(AppShortcuts.game("gb|a", "Game.zip", "Game"))
    }

    @Test fun `the label is the clean title`() {
        assertEquals("Final Fantasy VII", GameShortcuts.label("Final Fantasy VII (USA) (Disc 1).chd"))
        assertEquals("Legend of Zelda", GameShortcuts.label("Legend%20of%20Zelda%20(Europe).zip"))
        assertEquals("(Proto)", GameShortcuts.label("(Proto)"))
    }

    @Test fun `recent games come first, then the quick actions, views and consoles`() {
        val games = listOf(game("sony_psx", "Crash.chd"), game("nintendo_gba", "Golden Sun.gba"))
        val plan = AppShortcuts.plan(downloads, consoles, listOf(view), surprise = surprise, search = search, games = games)
        assertEquals(
            listOf("game:sony_psx|Crash.chd", "game:nintendo_gba|Golden Sun.gba", "section:surprise", "section:downloads", "section:search",
                "view:v1", "console:atari_2600", "console:sony_psx"),
            plan.map { it.id }
        )
        assertEquals("sony_psx|Crash.chd", plan[0].game)
        assertEquals("sony_psx", plan[0].consoleId)
        assertNull(plan[0].deepLink)
    }

    @Test fun `at most three games, without repeats, and the platform maximum still holds`() {
        val games = listOf(game("a", "1.zip"), game("a", "1.zip"), game("b", "2.zip"), game("c", "3.zip"), game("d", "4.zip"))
        val plan = AppShortcuts.plan(downloads, consoles, listOf(view), surprise = surprise, search = search, games = games)
        assertEquals(listOf("game:a|1.zip", "game:b|2.zip", "game:c|3.zip"), plan.filter { it.game != null }.map { it.id })
        val short = AppShortcuts.plan(downloads, consoles, listOf(view), max = 5, surprise = surprise, search = search, games = games)
        assertEquals(listOf("game:a|1.zip", "game:b|2.zip", "game:c|3.zip", "section:surprise", "section:downloads"), short.map { it.id })
    }

    @Test fun `switched off, the menu has no games`() {
        val plan = AppShortcuts.plan(downloads, consoles, listOf(view), surprise = surprise, search = search,
            games = listOf(game("sony_psx", "Crash.chd")), showGames = false)
        assertTrue(plan.none { it.game != null })
        assertEquals(listOf("section:surprise", "section:downloads", "section:search", "view:v1", "console:atari_2600", "console:sony_psx"), plan.map { it.id })
    }

    @Test fun `recent plays are the profile's own, newest first, one per game`() {
        fun played(at: Long, console: String?, file: String?, profile: String = "") =
            ActionEntry("id$at", at, ActionKind.PLAYED, consoleId = console, fileName = file, profileId = profile)
        val entries = listOf(
            played(1, "sony_psx", "Crash (USA).cue"),
            played(2, "nintendo_gba", "Golden Sun.gba"),
            ActionEntry("r", 3, ActionKind.REMOVED, consoleId = "sega_md", fileName = "Sonic.md"),
            played(4, "sony_psx", "Crash (USA).m3u"),
            played(5, "snes", "Kids game.sfc", profile = "kid"),
            played(6, null, "No console.zip"),
            played(7, "atari_2600", "Pitfall.a26")
        )
        val recent = GameShortcuts.recentPlays(entries, "", limit = 10)
        assertEquals(listOf("Pitfall.a26", "Crash (USA).m3u", "Golden Sun.gba"), recent.map { it.fileName })
        assertEquals(listOf(7L, 4L, 2L), recent.map { it.at })
        assertEquals(listOf("Pitfall.a26", "Crash (USA).m3u"), GameShortcuts.recentPlays(entries, "", limit = 2).map { it.fileName })
        assertEquals(listOf("Kids game.sfc"), GameShortcuts.recentPlays(entries, "kid", limit = 3).map { it.fileName })
        assertTrue(GameShortcuts.recentPlays(entries, "", limit = 0).isEmpty())
    }

    @Test fun `a started file finds its library row`() {
        val names = listOf("Crash (USA).zip", "crash (usa).cue", "Crash (Europe).zip", "Crash%20(USA).7z", "Other.zip")
        assertEquals(listOf("crash (usa).cue", "Crash (USA).zip", "Crash%20(USA).7z"), GameShortcuts.libraryMatches("sony_psx", "crash (usa).cue", names))
        assertEquals(listOf("crash (usa).cue", "Crash (USA).zip", "Crash%20(USA).7z"), GameShortcuts.libraryMatches("sony_psx", "Crash (USA).CUE", names))
        assertEquals(listOf("Crash (USA).zip", "crash (usa).cue", "Crash%20(USA).7z"), GameShortcuts.libraryMatches("sony_psx", "Crash (USA).m3u", names))
        assertTrue(GameShortcuts.libraryMatches("sony_psx", "Spyro.cue", names).isEmpty())
    }

    @Test fun `a removal reaches every shortcut of the same game and no other`() {
        val ids = listOf("game:sony_psx|Crash (USA).zip", "game:sony_psx|crash (usa).cue", "game:nintendo_gba|Crash (USA).zip",
            "game:sony_psx|Crash (Europe).zip", "console:sony_psx", "section:downloads")
        assertEquals(listOf("game:sony_psx|Crash (USA).zip", "game:sony_psx|crash (usa).cue"), GameShortcuts.affected(ids, "sony_psx", "Crash (USA).zip"))
        assertTrue(GameShortcuts.affected(ids, "sega_md", "Sonic.md").isEmpty())
    }

    @Test fun `the icon shows the square middle of the cover`() {
        assertArrayEquals(intArrayOf(0, 50, 300, 350), GameShortcuts.squareCrop(300, 400))
        assertArrayEquals(intArrayOf(50, 0, 350, 300), GameShortcuts.squareCrop(400, 300))
        assertArrayEquals(intArrayOf(0, 0, 64, 64), GameShortcuts.squareCrop(64, 64))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), GameShortcuts.squareCrop(0, 0))
    }
}
