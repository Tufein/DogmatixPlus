package com.cortinadev.dogmatix.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** 8.0: what the controller quick menu (hold SELECT) offers. */
enum class QuickMenuItem {
    /** Library with the cursor in its search box ([QuickAction.SEARCH]). */
    SEARCH,
    /** The "search everything" screen (route `search_all`). */
    SEARCH_ALL,
    /** A random game of the library ([QuickAction.SURPRISE]). */
    SURPRISE,
    DOWNLOADS,
    /** Hold the whole download queue; shown while it runs. */
    PAUSE_ALL,
    /** Release the held queue; shown while it is held. */
    RESUME_ALL,
    TOOLS,
    SETTINGS
}

/** A D-pad press while the quick menu is open. */
enum class PadDirection(val x: Float, val y: Float) {
    UP(0f, -1f), DOWN(0f, 1f), LEFT(-1f, 0f), RIGHT(1f, 0f)
}

/**
 * Geometry and contents of the quick menu ring. Slice 0 sits at the top and the slices go round
 * clockwise. Coordinates are screen-like: x to the right, y downwards (the same as a stick's
 * AXIS_X / AXIS_Y). Pure JVM for the tests.
 */
object QuickMenu {
    /** How far the stick must lean before it picks a slice (a resting stick keeps the current one). */
    const val STICK_DEAD_ZONE = 0.5f

    /** The menu for the current queue state: Pause all and Resume all share one slot. */
    fun items(queueHeld: Boolean): List<QuickMenuItem> = listOf(
        QuickMenuItem.SEARCH, QuickMenuItem.SEARCH_ALL, QuickMenuItem.SURPRISE, QuickMenuItem.DOWNLOADS,
        if (queueHeld) QuickMenuItem.RESUME_ALL else QuickMenuItem.PAUSE_ALL,
        QuickMenuItem.TOOLS, QuickMenuItem.SETTINGS
    )

    /** Angle of slice [index]'s centre in radians, clockwise from straight up. */
    fun angleOf(index: Int, count: Int): Double = 2 * PI * index / count

    /** Unit vector to the centre of slice [index] (x right, y down). */
    fun positionOf(index: Int, count: Int): Pair<Float, Float> {
        val a = angleOf(index, count)
        return sin(a).toFloat() to (-cos(a)).toFloat()
    }

    /**
     * The slice in the direction of ([x], [y]), or null when the vector is shorter than [deadZone]
     * (a stick at rest, a tap in the middle of the ring).
     */
    fun sliceAt(x: Float, y: Float, count: Int, deadZone: Float = STICK_DEAD_ZONE): Int? {
        if (count <= 0 || hypot(x, y) < deadZone) return null
        var angle = atan2(x.toDouble(), -y.toDouble())
        if (angle < 0) angle += 2 * PI
        return (angle / (2 * PI / count)).roundToInt() % count
    }

    /**
     * Where a D-pad press goes from [current]. Nothing selected yet: the slice in that direction.
     * Otherwise the nearest slice that lies that way, judged like focus search (distance along the
     * direction plus twice the sideways offset); when none does, the selection stays.
     */
    fun step(current: Int?, direction: PadDirection, count: Int): Int? {
        if (count <= 0) return null
        if (current == null || current !in 0 until count) return sliceAt(direction.x, direction.y, count, deadZone = 0f)
        val (px, py) = positionOf(current, count)
        var best: Int? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until count) {
            if (i == current) continue
            val (qx, qy) = positionOf(i, count)
            val dx = qx - px
            val dy = qy - py
            val along = dx * direction.x + dy * direction.y
            if (along <= 0.05f) continue
            val sideways = abs(dx * direction.y - dy * direction.x)
            val score = along + 2.0 * sideways
            if (score < bestScore) { bestScore = score; best = i }
        }
        return best ?: current
    }
}

/**
 * The SELECT button's hold timing: a short press keeps its old meaning (favourite / tick), fired on
 * release; holding it for [holdMs] opens the quick menu; releasing the press that opened the menu
 * activates the chosen slice; pressing SELECT while the menu is open closes it. Times are any
 * monotonic milliseconds (the key events' uptime). Pure JVM for the tests.
 */
class SelectHold(private val holdMs: Long = HOLD_MS) {
    enum class Outcome {
        NONE,
        /** A quick press: do what SELECT always did. */
        SHORT_PRESS,
        OPEN_MENU,
        /** The press that opened the menu was let go: activate the selected slice, if any. */
        RELEASE,
        CLOSE_MENU
    }

    private var downAt = -1L
    private var opened = false
    private var closing = false

    val isPressed: Boolean get() = downAt >= 0

    /** SELECT went down; [menuOpen] says whether the quick menu is already showing. */
    fun down(now: Long, menuOpen: Boolean): Outcome {
        if (downAt >= 0) return Outcome.NONE   // a key repeat
        downAt = now
        opened = false
        closing = menuOpen
        return if (menuOpen) Outcome.CLOSE_MENU else Outcome.NONE
    }

    /** Called by a timer (and on key repeats): opens the menu once the hold is long enough. */
    fun poll(now: Long): Outcome {
        if (downAt < 0 || opened || closing || now - downAt < holdMs) return Outcome.NONE
        opened = true
        return Outcome.OPEN_MENU
    }

    /** SELECT came up. A late timer still opens the menu (it then stays open after the release). */
    fun up(now: Long): Outcome {
        if (downAt < 0) return Outcome.NONE
        val outcome = when {
            closing -> Outcome.NONE
            opened -> Outcome.RELEASE
            now - downAt >= holdMs -> Outcome.OPEN_MENU
            else -> Outcome.SHORT_PRESS
        }
        cancel()
        return outcome
    }

    /** The press was cancelled (focus loss, a cancelled key event): forget it without firing. */
    fun cancel() {
        downAt = -1L
        opened = false
        closing = false
    }

    companion object {
        const val HOLD_MS = 400L
    }
}
