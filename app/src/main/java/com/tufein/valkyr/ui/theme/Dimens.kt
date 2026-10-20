package com.tufein.valkyr.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 3.8.0 "covers first" look: the shared sizes of the new shell, so cards, rows and bars keep the
 * same rounding and air everywhere. Colours stay in [Color] / the theme; this is only shape and
 * spacing.
 */
object Dimens {
    /** Corner radius of the big cards: More rows, the hero card, game page sections. */
    val cardRadius = 20.dp
    /** Corner radius of cover tiles and smaller cards. */
    val tile = 16.dp
    /** Corner radius of pills and chips (half of a 32dp height). */
    val pill = 16.dp

    /** The smallest touch target (Material guidance). */
    val touch = 48.dp
    /** A comfortable touch target: big rows, the primary button on the game page. */
    val touchLarge = 56.dp
    /** Height of a bottom bar entry and of a rail entry. */
    val navItem = 64.dp

    /** Side padding of a screen. */
    val gutter = 16.dp
    /** Gap between cards in a list or a row of covers. */
    val gap = 12.dp
    /** Gap between sections (shelves, card groups). */
    val section = 24.dp

    /** Icon tile in a big row. */
    val rowIcon = 44.dp
    /** Width of the selection pill behind a tab icon, and its height. */
    val indicatorWidth = 56.dp
    val indicatorHeight = 32.dp
    /** The landscape navigation rail: its width grows for long labels, between these two. */
    val railMinWidth = 80.dp
    val railMaxWidth = 120.dp
}
