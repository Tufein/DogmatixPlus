package com.cortinadev.dogmatix.util

/**
 * How much of the home-screen widget fits at a given height: the header, the downloads line and
 * the "new games" line always show, the game titles under them as far as there is room. Pure JVM
 * for the tests.
 */
object WidgetLayout {
    /** The most title lines the layout has. */
    const val MAX_TITLES = 3

    /** Height (dp) of everything but the title lines: padding, header, downloads block, "new" line. */
    private const val BASE_DP = 104

    /** Height (dp) of one title line. */
    private const val TITLE_DP = 17

    /**
     * Title lines to show in a widget [heightDp] tall (the launcher's minimum height for it) when
     * [available] titles exist. An unknown height (0 or less) gets two lines, the layout's default.
     */
    fun titleLines(heightDp: Int, available: Int): Int {
        val room = if (heightDp <= 0) 2 else ((heightDp - BASE_DP) / TITLE_DP).coerceIn(0, MAX_TITLES)
        return room.coerceAtMost(available.coerceAtLeast(0))
    }

    /**
     * Text colour for the accent on a panel: the accent as it is on a dark panel, a little darker on
     * a light one, so small figures stay readable. Works on ARGB ints; [darken] is 0..1.
     */
    fun accentTextOn(accentArgb: Int, darkPanel: Boolean, darken: Float = 0.28f): Int {
        if (darkPanel) return accentArgb
        val keep = (1f - darken.coerceIn(0f, 1f))
        val a = accentArgb ushr 24 and 0xFF
        val r = ((accentArgb ushr 16 and 0xFF) * keep).toInt()
        val g = ((accentArgb ushr 8 and 0xFF) * keep).toInt()
        val b = ((accentArgb and 0xFF) * keep).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
