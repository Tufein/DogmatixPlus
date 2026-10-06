package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.GamePageModel.Action
import com.cortinadev.dogmatix.util.GamePageModel.Primary
import com.cortinadev.dogmatix.util.GamePageModel.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class GamePageModelTest {

    @Test fun `route encodes both parts as path segments`() {
        val route = GamePageModel.route("snes", "Super Mario World (USA) [!] #1 50% a+b/c?.zip")
        assertTrue(route.startsWith("game/snes/"))
        val segment = route.removePrefix("game/snes/")
        assertFalse(segment.contains('/'))
        assertFalse(segment.contains(' '))
        assertFalse(segment.contains('?'))
        assertFalse(segment.contains('#'))
        assertFalse(segment.contains('+'))
        assertTrue(segment.contains("%20"))
        // What Navigation's Uri.decode gives back (no '+' to space folding in a path).
        assertEquals("Super Mario World (USA) [!] #1 50% a+b/c?.zip", URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8"))
    }

    @Test fun `route pattern names both arguments`() {
        assertEquals("game/{${GamePageModel.ARG_CONSOLE}}/{${GamePageModel.ARG_FILE}}", GamePageModel.ROUTE)
    }

    @Test fun `only About for a single version without progress or similar games`() {
        assertEquals(listOf(Tab.ABOUT), GamePageModel.tabs(versionCount = 1, progress = false, similarCount = 0))
    }

    @Test fun `every tab in a fixed order when everything is known`() {
        assertEquals(listOf(Tab.ABOUT, Tab.VERSIONS, Tab.PROGRESS, Tab.SIMILAR), GamePageModel.tabs(3, progress = true, similarCount = 2))
    }

    @Test fun `step wraps both ways`() {
        val tabs = listOf(Tab.ABOUT, Tab.VERSIONS, Tab.SIMILAR)
        assertEquals(Tab.VERSIONS, GamePageModel.step(tabs, Tab.ABOUT, 1))
        assertEquals(Tab.SIMILAR, GamePageModel.step(tabs, Tab.ABOUT, -1))
        assertEquals(Tab.ABOUT, GamePageModel.step(tabs, Tab.SIMILAR, 1))
    }

    @Test fun `step from a tab that went away goes to the first`() {
        assertEquals(Tab.ABOUT, GamePageModel.step(listOf(Tab.ABOUT, Tab.VERSIONS), Tab.PROGRESS, 1))
        assertEquals(Tab.ABOUT, GamePageModel.step(emptyList(), Tab.PROGRESS, 1))
    }

    @Test fun `visible keeps the tab while it exists`() {
        assertEquals(Tab.VERSIONS, GamePageModel.visible(listOf(Tab.ABOUT, Tab.VERSIONS), Tab.VERSIONS))
        assertEquals(Tab.ABOUT, GamePageModel.visible(listOf(Tab.ABOUT), Tab.SIMILAR))
    }

    @Test fun `primary follows download state first`() {
        assertEquals(Primary.DOWNLOAD, GamePageModel.primary(owned = false, downloading = false))
        assertEquals(Primary.PLAY, GamePageModel.primary(owned = true, downloading = false))
        assertEquals(Primary.DOWNLOADING, GamePageModel.primary(owned = true, downloading = true))
        assertEquals(Primary.DOWNLOADING, GamePageModel.primary(owned = false, downloading = true))
    }

    @Test fun `a new game offers download when but no remove`() {
        val actions = GamePageModel.actions(owned = false, downloading = false, betterVersion = false, updateAvailable = false, missingDlc = 0)
        assertEquals(listOf(Action.DOWNLOAD_WHEN, Action.FAVOURITE, Action.COLLECTIONS, Action.SHARE), actions)
    }

    @Test fun `an owned game offers remove and no download when`() {
        val actions = GamePageModel.actions(owned = true, downloading = false, betterVersion = true, updateAvailable = true, missingDlc = 2)
        assertEquals(
            listOf(Action.DOWNLOAD_BEST, Action.UPDATE, Action.DLC, Action.FAVOURITE, Action.COLLECTIONS, Action.SHARE, Action.REMOVE),
            actions
        )
    }

    @Test fun `nothing to remove or schedule while downloading`() {
        val actions = GamePageModel.actions(owned = true, downloading = true, betterVersion = false, updateAvailable = false, missingDlc = 0, share = false)
        assertFalse(Action.REMOVE in actions)
        assertFalse(Action.DOWNLOAD_WHEN in actions)
        assertFalse(Action.SHARE in actions)
        assertTrue(Action.FAVOURITE in actions)
    }

    @Test fun `versions put the best first and the current one next`() {
        val names = listOf("a (Japan).zip", "b (Europe).zip", "c (USA).zip", "d (Beta).zip")
        assertEquals(
            listOf("c (USA).zip", "b (Europe).zip", "a (Japan).zip", "d (Beta).zip"),
            GamePageModel.orderVersions(names, { it }, current = "b (Europe).zip", best = "c (USA).zip")
        )
    }

    @Test fun `versions keep library order without a best pick`() {
        val names = listOf("a.zip", "b.zip", "c.zip")
        assertEquals(listOf("c.zip", "a.zip", "b.zip"), GamePageModel.orderVersions(names, { it }, current = "c.zip", best = null))
    }

    @Test fun `hero tags keep regions and languages only`() {
        val tags = listOf("USA", "En", "Fr", "Rev 1", "Beta", "USA", "De", "Es")
        val hero = GamePageModel.heroTags(tags)
        assertTrue(hero.size <= 4)
        assertFalse("Rev 1" in hero)
        assertFalse("Beta" in hero)
        assertEquals(hero.distinct(), hero)
        assertEquals("USA", hero.first())
    }
}
