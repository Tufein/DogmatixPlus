package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.SelectHold.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickMenuTest {
    @Test fun `items swap pause all for resume all while the queue is held`() {
        val running = QuickMenu.items(queueHeld = false)
        val held = QuickMenu.items(queueHeld = true)
        assertEquals(7, running.size)
        assertEquals(QuickMenuItem.SEARCH, running[0])
        assertTrue(QuickMenuItem.PAUSE_ALL in running && QuickMenuItem.RESUME_ALL !in running)
        assertTrue(QuickMenuItem.RESUME_ALL in held && QuickMenuItem.PAUSE_ALL !in held)
        assertEquals(running.indexOf(QuickMenuItem.PAUSE_ALL), held.indexOf(QuickMenuItem.RESUME_ALL))
        assertEquals(setOf(QuickMenuItem.SEARCH, QuickMenuItem.SEARCH_ALL, QuickMenuItem.SURPRISE, QuickMenuItem.DOWNLOADS, QuickMenuItem.TOOLS, QuickMenuItem.SETTINGS), running.toSet() - QuickMenuItem.PAUSE_ALL)
    }

    @Test fun `stick direction picks the slice, clockwise from the top`() {
        assertEquals(0, QuickMenu.sliceAt(0f, -1f, 4))
        assertEquals(1, QuickMenu.sliceAt(1f, 0f, 4))
        assertEquals(2, QuickMenu.sliceAt(0f, 1f, 4))
        assertEquals(3, QuickMenu.sliceAt(-1f, 0f, 4))
        // Just left of straight up still belongs to slice 0, not the last one.
        assertEquals(0, QuickMenu.sliceAt(-0.1f, -1f, 7))
        assertEquals(6, QuickMenu.sliceAt(-0.8f, -0.6f, 7))
        assertEquals(1, QuickMenu.sliceAt(0.8f, -0.6f, 7))
    }

    @Test fun `a resting stick picks nothing`() {
        assertNull(QuickMenu.sliceAt(0.2f, -0.3f, 7))
        assertNull(QuickMenu.sliceAt(0f, 0f, 7))
        assertNull(QuickMenu.sliceAt(1f, 0f, 0))
    }

    @Test fun `slice positions agree with slice lookup`() {
        for (count in listOf(4, 7, 8)) for (i in 0 until count) {
            val (x, y) = QuickMenu.positionOf(i, count)
            assertEquals(i, QuickMenu.sliceAt(x, y, count))
        }
    }

    @Test fun `d-pad with nothing selected jumps to the slice in that direction`() {
        assertEquals(0, QuickMenu.step(null, PadDirection.UP, 7))
        assertEquals(2, QuickMenu.step(null, PadDirection.RIGHT, 7))
        assertEquals(5, QuickMenu.step(null, PadDirection.LEFT, 7))
        // Straight down sits between slices 3 and 4 of seven; either is fine, but it must be one of them.
        assertTrue(QuickMenu.step(null, PadDirection.DOWN, 7) in setOf(3, 4))
    }

    @Test fun `d-pad walks round the ring like focus search`() {
        // From the top, down goes to a neighbour and keeps going round that side.
        val first = QuickMenu.step(0, PadDirection.DOWN, 7)!!
        assertTrue(first == 1 || first == 6)
        assertEquals(2, QuickMenu.step(1, PadDirection.DOWN, 7))
        assertEquals(3, QuickMenu.step(2, PadDirection.DOWN, 7))
        // Right from the top goes clockwise, left anticlockwise.
        assertEquals(1, QuickMenu.step(0, PadDirection.RIGHT, 7))
        assertEquals(6, QuickMenu.step(0, PadDirection.LEFT, 7))
        // Up from the top: nothing is further up, the selection stays.
        assertEquals(0, QuickMenu.step(0, PadDirection.UP, 7))
        // A four-slice ring: left from the right slice crosses to the left one.
        assertEquals(3, QuickMenu.step(1, PadDirection.LEFT, 4))
    }

    @Test fun `short press fires on release`() {
        val hold = SelectHold(400)
        assertEquals(Outcome.NONE, hold.down(1000, menuOpen = false))
        assertTrue(hold.isPressed)
        assertEquals(Outcome.NONE, hold.poll(1200))
        assertEquals(Outcome.SHORT_PRESS, hold.up(1250))
        assertFalse(hold.isPressed)
    }

    @Test fun `holding opens the menu once and releasing activates`() {
        val hold = SelectHold(400)
        hold.down(1000, menuOpen = false)
        assertEquals(Outcome.OPEN_MENU, hold.poll(1400))
        assertEquals(Outcome.NONE, hold.poll(1500))
        assertEquals(Outcome.NONE, hold.down(1500, menuOpen = true))   // key repeat
        assertEquals(Outcome.RELEASE, hold.up(2000))
    }

    @Test fun `a late timer still opens the menu on release, without a favourite`() {
        val hold = SelectHold(400)
        hold.down(1000, menuOpen = false)
        assertEquals(Outcome.OPEN_MENU, hold.up(1450))
    }

    @Test fun `select while the menu is open closes it and its release does nothing`() {
        val hold = SelectHold(400)
        assertEquals(Outcome.CLOSE_MENU, hold.down(1000, menuOpen = true))
        assertEquals(Outcome.NONE, hold.poll(1600))
        assertEquals(Outcome.NONE, hold.up(1700))
    }

    @Test fun `a cancelled press fires nothing`() {
        val hold = SelectHold(400)
        hold.down(1000, menuOpen = false)
        hold.cancel()
        assertEquals(Outcome.NONE, hold.up(1100))
        assertEquals(Outcome.NONE, hold.poll(2000))
    }
}
