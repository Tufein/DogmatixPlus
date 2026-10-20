package com.tufein.valkyr.ui.screens.more

import com.tufein.valkyr.ui.navigation.NavRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoreMenuTest {

    private val items = MoreMenu.groups.flatMap { it.items }

    @Test fun `four groups in the agreed order, none empty`() {
        assertEquals(listOf("yours", "tools", "online", "app"), MoreMenu.groups.map { it.id })
        MoreMenu.groups.forEach { assertTrue(it.id, it.items.isNotEmpty()) }
    }

    @Test fun `every row opens a known screen that is not a tab, once`() {
        val known = NavRoutes.allRoutes.map { it.route }.toSet()
        val tabs = NavRoutes.tabs.map { it.route }.toSet()
        items.forEach {
            assertTrue(it.route.route, it.route.route in known)
            assertTrue(it.route.route, it.route.route !in tabs)
        }
        assertEquals(items.size, items.map { it.route }.toSet().size)
    }

    @Test fun `the screens that left the tab bar are all reachable`() {
        val routes = items.map { it.route }
        listOf(NavRoutes.Settings, NavRoutes.Tools, NavRoutes.Cloud, NavRoutes.Wishlist, NavRoutes.Contact)
            .forEach { assertTrue(it.route, it in routes) }
    }

    @Test fun `rows sit two side by side only when there is room`() {
        assertEquals(1, MoreMenu.columnsFor(360))
        assertEquals(1, MoreMenu.columnsFor(MoreMenu.TWO_COLUMNS_FROM_DP - 1))
        assertEquals(2, MoreMenu.columnsFor(MoreMenu.TWO_COLUMNS_FROM_DP))
        val group = MoreMenu.groups.first { it.items.size % 2 == 1 }
        val lines = MoreMenu.lines(group, 2)
        assertEquals(group.items, lines.flatten())
        assertEquals(1, lines.last().size)
        assertEquals(group.items.size, MoreMenu.lines(group, 1).size)
        assertEquals(group.items.size, MoreMenu.lines(group, 0).size)
    }
}
