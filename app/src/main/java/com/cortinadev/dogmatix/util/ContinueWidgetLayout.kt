package com.cortinadev.dogmatix.util

/**
 * How the "Continue playing" widget fills itself: how many games fit the height, the second line
 * of a row ("2 hr. ago · Thor") and the console tile when there is no cover. Pure JVM for the tests.
 */
object ContinueWidgetLayout {
    /** The most rows the layout has. */
    const val MAX_ROWS = 4

    /** Height (dp) of the padding and the header. */
    private const val BASE_DP = 40

    /** Height (dp) of one game row. */
    private const val ROW_DP = 46

    /** Rows to show in a widget [heightDp] tall (the launcher's minimum height) when [available] games exist; unknown height = 3. */
    fun rows(heightDp: Int, available: Int): Int {
        val room = if (heightDp <= 0) 3 else ((heightDp - BASE_DP) / ROW_DP).coerceIn(1, MAX_ROWS)
        return room.coerceAtMost(available.coerceAtLeast(0))
    }

    /** "2 hr. ago · Thor"; just the time when the device is unknown, just the device when the time is. */
    fun subtitle(ago: String?, via: String?): String =
        listOfNotNull(ago?.takeIf { it.isNotBlank() }, via?.takeIf { it.isNotBlank() }).joinToString(" · ")

    /** Width in pixels of a row's cover (34 dp; its height is 4/3 of it): sharp, yet small for the RemoteViews size limit. */
    fun thumbPx(density: Float): Int = (34f * density).toInt().coerceIn(68, 136)
}
