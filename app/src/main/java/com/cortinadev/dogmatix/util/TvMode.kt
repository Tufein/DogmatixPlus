package com.cortinadev.dogmatix.util

import kotlin.math.max
import kotlin.math.roundToInt

/** Settings → TV mode: follow the device (default), or force the TV layout on or off. */
enum class TvModeSetting {
    AUTO, ON, OFF;

    companion object {
        /** The stored name back to a setting; anything unknown (an old or broken value) is AUTO. */
        fun fromKey(key: String?): TvModeSetting = entries.firstOrNull { it.name == key } ?: AUTO
    }
}

/** A TV remote key the app treats like a gamepad shortcut (see [TvMode.remoteKey]). */
enum class RemoteKey { PREV_SECTION, NEXT_SECTION, DETAILS, SEARCH }

/**
 * 8.0 TV mode: the layout for Android TV and docked handhelds on a television. Decides when it is
 * on and holds its sizes; the Compose side lives in ui/components/TvLayout.kt.
 */
object TvMode {
    /** Text grows by this much on top of the text size setting (read from across the room). */
    const val TEXT_FACTOR = 1.15f
    /** Focus ring stroke in TV mode, in dp (the normal ring is 2 dp, the bold one 3.5 dp). */
    const val FOCUS_RING_DP = 3.5f
    /** Library rows and covers grow by this much. */
    const val ITEM_FACTOR = 1.25f

    /** Overscan-safe margins: 5 % of the screen, at least 48 dp at the sides and 27 dp at top and bottom. */
    const val MIN_SIDE_DP = 48
    const val MIN_EDGE_DP = 27
    const val SAFE_FRACTION = 0.05f

    /** A screen this wide (dp, longer side) without a touchscreen counts as a television. */
    const val LARGE_SCREEN_DP = 900

    /**
     * Whether the TV layout is on. AUTO turns it on when Android says the device is a television
     * (UI mode TELEVISION), or when the screen is large and cannot be touched (a docked device,
     * a set-top box that does not report itself as a TV).
     */
    fun isActive(
        setting: TvModeSetting,
        uiModeTelevision: Boolean,
        screenWidthDp: Int,
        screenHeightDp: Int,
        hasTouchscreen: Boolean
    ): Boolean = when (setting) {
        TvModeSetting.ON -> true
        TvModeSetting.OFF -> false
        TvModeSetting.AUTO -> uiModeTelevision ||
            (!hasTouchscreen && max(screenWidthDp, screenHeightDp) >= LARGE_SCREEN_DP)
    }

    /** Side margin (dp) for a screen [widthDp] wide. */
    fun sidePaddingDp(widthDp: Int): Int = max(MIN_SIDE_DP, (widthDp * SAFE_FRACTION).roundToInt())

    /** Top and bottom margin (dp) for a screen [heightDp] tall. */
    fun edgePaddingDp(heightDp: Int): Int = max(MIN_EDGE_DP, (heightDp * SAFE_FRACTION).roundToInt())

    /** The next setting [delta] steps away, wrapping (Settings row: A cycles, ◀ ▶ step). */
    fun shift(current: TvModeSetting, delta: Int): TvModeSetting {
        val all = TvModeSetting.entries
        return all[((current.ordinal + delta) % all.size + all.size) % all.size]
    }

    /**
     * TV remote keys that stand in for the gamepad shortcuts, so a remote with only a D-pad, OK,
     * Back and a few extra keys reaches everything: channel / page / rewind-forward switch
     * sections (ZL / ZR), Menu opens the details (X), the search key opens search (Y).
     * Takes android.view.KeyEvent key codes; null = not a remote shortcut.
     */
    fun remoteKey(keyCode: Int): RemoteKey? = when (keyCode) {
        KEYCODE_CHANNEL_UP, KEYCODE_PAGE_UP, KEYCODE_MEDIA_REWIND -> RemoteKey.PREV_SECTION
        KEYCODE_CHANNEL_DOWN, KEYCODE_PAGE_DOWN, KEYCODE_MEDIA_FAST_FORWARD -> RemoteKey.NEXT_SECTION
        KEYCODE_MENU, KEYCODE_INFO -> RemoteKey.DETAILS
        KEYCODE_SEARCH -> RemoteKey.SEARCH
        else -> null
    }

    // android.view.KeyEvent values, repeated so this stays plain Kotlin (unit tests run without Android).
    const val KEYCODE_MENU = 82
    const val KEYCODE_SEARCH = 84
    const val KEYCODE_MEDIA_REWIND = 89
    const val KEYCODE_MEDIA_FAST_FORWARD = 90
    const val KEYCODE_PAGE_UP = 92
    const val KEYCODE_PAGE_DOWN = 93
    const val KEYCODE_INFO = 165
    const val KEYCODE_CHANNEL_UP = 166
    const val KEYCODE_CHANNEL_DOWN = 167
}
