package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadFocusTest {

    @Test
    fun `the press that wakes the cursor enters the screen`() {
        for (direction in listOf(PadDirection.UP, PadDirection.DOWN, PadDirection.RIGHT, null)) {
            assertEquals(PadFocus.WakeTarget.CONTENT, PadFocus.wakeTarget(direction, railOnLeft = true))
        }
        for (direction in PadDirection.entries + listOf(null)) {
            assertEquals(PadFocus.WakeTarget.CONTENT, PadFocus.wakeTarget(direction, railOnLeft = false))
        }
    }

    @Test
    fun `left with the rail on the left goes to the rail`() {
        assertEquals(PadFocus.WakeTarget.TAB, PadFocus.wakeTarget(PadDirection.LEFT, railOnLeft = true))
    }

    @Test
    fun `an item well inside the list does not scroll`() {
        assertEquals(0f, PadFocus.scrollDistance(offset = 300f, size = 100f, container = 1000f), 0.001f)
    }

    @Test
    fun `moving down keeps a margin below the item`() {
        // Item 900..1000 in a 1000 px list: 10 % (100 px) margin wanted below it.
        assertEquals(100f, PadFocus.scrollDistance(offset = 900f, size = 100f, container = 1000f), 0.001f)
        // Partly below the edge.
        assertEquals(150f, PadFocus.scrollDistance(offset = 950f, size = 100f, container = 1000f), 0.001f)
    }

    @Test
    fun `moving up keeps a margin above the item`() {
        assertEquals(-100f, PadFocus.scrollDistance(offset = 0f, size = 100f, container = 1000f), 0.001f)
        assertEquals(-150f, PadFocus.scrollDistance(offset = -50f, size = 100f, container = 1000f), 0.001f)
    }

    @Test
    fun `the margin is settled once reached`() {
        // After the scroll above the item sits at 800..900: nothing more to do (no endless nudging).
        assertEquals(0f, PadFocus.scrollDistance(offset = 800f, size = 100f, container = 1000f), 0.001f)
        assertEquals(0f, PadFocus.scrollDistance(offset = 100f, size = 100f, container = 1000f), 0.001f)
    }

    @Test
    fun `a big item gets a smaller margin and a huge one none`() {
        // 900 px item in 1000 px: margin shrinks to 50 px on each side.
        assertEquals(-50f, PadFocus.scrollDistance(offset = 0f, size = 900f, container = 1000f), 0.001f)
        // Bigger than the list and covering it: left alone.
        assertEquals(0f, PadFocus.scrollDistance(offset = -100f, size = 1500f, container = 1000f), 0.001f)
        // Bigger than the list, below it: its top comes to the top.
        assertEquals(200f, PadFocus.scrollDistance(offset = 200f, size = 1500f, container = 1000f), 0.001f)
    }

    @Test
    fun `without a margin it matches the plain minimal scroll`() {
        assertEquals(50f, PadFocus.scrollDistance(950f, 100f, 1000f, marginShare = 0f), 0.001f)
        assertEquals(-50f, PadFocus.scrollDistance(-50f, 100f, 1000f, marginShare = 0f), 0.001f)
        assertEquals(0f, PadFocus.scrollDistance(0f, 100f, 1000f, marginShare = 0f), 0.001f)
    }

    @Test
    fun `a page is a screenful of rows less one, at least three`() {
        assertEquals(6, PadFocus.pageSteps(520))
        assertEquals(3, PadFocus.pageSteps(200))
        assertEquals(3, PadFocus.pageSteps(0))
    }

    @Test
    fun `screens with their own shoulder buttons are not paged`() {
        for (route in listOf("home", "settings", "romm", "action_history", "up_next", "game/{consoleId}/{fileName}")) {
            assertFalse(route, PadFocus.shouldersPage(route))
        }
        for (route in listOf("downloads", "sources", "more", "tools", "wishlist", "files")) {
            assertTrue(route, PadFocus.shouldersPage(route))
        }
        assertFalse(PadFocus.shouldersPage(null))
    }
}
