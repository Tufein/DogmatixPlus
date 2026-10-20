package com.tufein.valkyr.util

import kotlin.math.abs

/**
 * 3.9.0: the small decisions behind "smoother controls", kept free of Android so they can be
 * unit-tested: where the first D-pad press goes when the cursor is out of sight, how far a
 * focus move scrolls a list, and how far L1 / R1 jump.
 */
object PadFocus {

    /** Where a press that brings the cursor back lands. */
    enum class WakeTarget {
        /** The active section in the rail or bottom bar. */
        TAB,
        /** The first item of the screen (or the item it asked for, see `Gamepad.contentEntry`). */
        CONTENT
    }

    /**
     * The cursor is hidden (after ZL / ZR) or nothing has focus (after a tap on a tab, a closed
     * dialog, a list that changed under it): the press that brings it back also enters the
     * screen. Only ◀ with the rail on the left goes to the rail, which is where it points.
     * [direction] is null for A / centre.
     */
    fun wakeTarget(direction: PadDirection?, railOnLeft: Boolean): WakeTarget =
        if (railOnLeft && direction == PadDirection.LEFT) WakeTarget.TAB else WakeTarget.CONTENT

    /** Room kept between a focused item and the edge of its list, as a share of the list's size. */
    const val EDGE_MARGIN = 0.10f

    /**
     * How far a list scrolls to show a focused item ([offset] = its leading edge in the viewport,
     * [size] its length, [container] the viewport length; all in px). Compose's default scrolls
     * just enough to show the item and leaves it against the edge, so a held D-pad keeps running
     * into the unknown; this keeps [EDGE_MARGIN] of the viewport free beyond it, so the next item
     * already peeks in. An item that is (nearly) as big as the viewport gets no margin; one
     * bigger than the viewport is left alone while it covers it.
     */
    fun scrollDistance(offset: Float, size: Float, container: Float, marginShare: Float = EDGE_MARGIN): Float {
        val margin = minOf(container * marginShare, (container - size) / 2f).coerceAtLeast(0f)
        val leading = offset - margin
        val trailing = offset + size + margin
        val span = trailing - leading
        return when {
            leading >= 0f && trailing <= container -> 0f
            leading < 0f && trailing > container -> 0f
            span > container -> if (leading > 0f) leading else trailing - container
            abs(leading) < abs(trailing - container) -> leading
            else -> trailing - container
        }
    }

    /** Height of a typical list row (dp) used to turn a viewport into a number of D-pad steps. */
    const val ROW_DP = 72

    /**
     * L1 / R1 in a long list: how many ▲ / ▼ steps make one page of a viewport [viewportDp] high.
     * One row less than fits, so the row that was at the edge stays in sight; at least 3.
     */
    fun pageSteps(viewportDp: Int): Int = (viewportDp / ROW_DP - 1).coerceAtLeast(3)

    /**
     * The screens whose own L1 / R1 already mean something (panels, tabs, filters, setting
     * groups): the shell does not page them. Routes as in `NavRoutes`; a game page is "game/…".
     */
    private val OWN_SHOULDERS = setOf("home", "settings", "romm", "action_history", "up_next")

    /** Whether L1 / R1 page the focused list on [route]. */
    fun shouldersPage(route: String?): Boolean {
        if (route == null) return false
        if (route.startsWith("game/")) return false
        return route !in OWN_SHOULDERS
    }
}
