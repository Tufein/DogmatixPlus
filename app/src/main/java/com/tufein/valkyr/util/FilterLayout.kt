package com.tufein.valkyr.util

/**
 * The measures of the Library tab's filter UI (search row, view switch, chips, filter panel), in dp, in one
 * place so every row of it starts at the same edge, keeps the same gap and the same height.
 */
object FilterLayout {
    /** Height of every chip, segment and filter row (the 48 dp touch target). */
    const val CHIP_HEIGHT = 48

    /** Gap between chips, in a row and between wrapped rows. */
    const val GAP = 8

    /** Start / end inset of the filter rows in portrait: the search field's edge. */
    const val EDGE_PORTRAIT = 16

    /** In landscape the list column already carries its own 12 dp, so the rows add nothing. */
    const val EDGE_LANDSCAPE = 0

    /** Width of the landscape filter panel (the label column needs room for a wrapped label and ‹ value ›). */
    const val PANEL_WIDTH = 264

    /** A filter row is label left, control right: this share of its width is the label's, in every row. */
    const val LABEL_SHARE = 0.4f

    /** From this text scale up the view switch fills the line (no icons, labels may wrap) instead of overflowing it. */
    const val BIG_TEXT_SCALE = 1.3f

    fun stackViewSwitch(fontScale: Float): Boolean = fontScale > BIG_TEXT_SCALE

    /** Label and control weights of a filter row (always sum to 1, label first). */
    fun weights(): Pair<Float, Float> = LABEL_SHARE to (1f - LABEL_SHARE)

    /** Where the first character of a filter row's label sits from the panel's edge (panel padding + row start + mark + gap). */
    fun labelStart(panelPadding: Int = 8, rowStart: Int = 8, mark: Int = 3, gap: Int = 5): Int = panelPadding + rowStart + mark + gap
}
