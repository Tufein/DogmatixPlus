package com.cortinadev.dogmatix.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavTest {

    @Test fun `the four tabs are Games, Downloads, Sources and More`() {
        assertEquals(listOf("home", "downloads", "sources", "more"), NavRoutes.tabs.map { it.route })
        assertFalse(NavRoutes.Settings in NavRoutes.tabs)
    }

    @Test fun `every route is listed once and Settings is still a route`() {
        val routes = NavRoutes.allRoutes.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertTrue(NavRoutes.Settings.route in routes)
        assertTrue(NavRoutes.More.route in routes)
    }

    @Test fun `a tab lights itself, a game page lights Games, everything else lights More`() {
        NavRoutes.tabs.forEach { assertEquals(it.route, tabRouteFor(it.route)) }
        assertEquals(NavRoutes.Home.route, tabRouteFor(NavRoutes.Game.route))
        assertEquals(NavRoutes.Home.route, tabRouteFor("game/snes/Mario.sfc"))
        listOf(NavRoutes.Settings, NavRoutes.Tools, NavRoutes.Cloud, NavRoutes.Wishlist, NavRoutes.Contact, NavRoutes.SearchAll, NavRoutes.Romm)
            .forEach { assertEquals(it.route, NavRoutes.More.route, tabRouteFor(it.route)) }
        assertEquals(NavRoutes.More.route, tabRouteFor(""))
    }

    @Test fun `ZL and ZR step through the tabs and wrap around`() {
        assertEquals(NavRoutes.Downloads, neighbourTab("home", 1))
        assertEquals(NavRoutes.More, neighbourTab("home", -1))
        assertEquals(NavRoutes.Home, neighbourTab("more", 1))
        assertEquals(NavRoutes.Sources, neighbourTab("more", -1))
        // From a screen opened from More the step counts from More.
        assertEquals(NavRoutes.Home, neighbourTab(NavRoutes.Settings.route, 1))
        assertEquals(NavRoutes.Sources, neighbourTab(NavRoutes.Tools.route, -1))
        // From a game page it counts from Games; nothing known yet counts as More.
        assertEquals(NavRoutes.Downloads, neighbourTab(NavRoutes.Game.route, 1))
        assertEquals(NavRoutes.Home, neighbourTab(null, 1))
    }

    @Test fun `screens slide in from the side they are on`() {
        assertEquals(1, travelDirection("home", "sources"))
        assertEquals(-1, travelDirection("more", "downloads"))
        assertEquals(1, travelDirection("more", "settings"))
        assertEquals(-1, travelDirection("settings", "more"))
        assertEquals(1, travelDirection("settings", "tools"))
    }
}
