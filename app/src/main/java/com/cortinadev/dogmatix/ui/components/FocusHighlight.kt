package com.cortinadev.dogmatix.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.accentInk
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion
import com.cortinadev.dogmatix.util.TvMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Settings → "Bold focus ring": a thicker ring with a dark edge, readable on a TV or in sunlight. */
val LocalBoldFocus = staticCompositionLocalOf { false }

/**
 * The 5.0 focus look, drawn while the element owns keyboard / D-pad focus: a soft tonal fill
 * behind the content, an accent ring and a faint halo outside it, faded in and out. A touch press
 * flashes the same fill, so rows built with `indication = null` still answer a tap.
 *
 * Pair it with a `clickable(interactionSource = source, ...)` on the same element. Everything is
 * animated in the draw phase: focus changes never recompose the element.
 *
 * @param onAccent the element is itself filled with the accent (a selected chip, a primary button):
 *   the ring then uses the text colour so it stays visible.
 * @param fill draw the tonal fill (off for elements that already change colour when focused).
 */
@Composable
fun Modifier.focusRing(
    interactionSource: MutableInteractionSource,
    cornerRadius: Dp = 8.dp,
    width: Dp = 1.5.dp,
    /** Shift (px) of the ring's left edge, read while drawing: follows the filter panel animation. */
    startShift: () -> Int = { 0 },
    onAccent: Boolean = false,
    fill: Boolean = true
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val bold = LocalBoldFocus.current
    val tv = LocalTvMode.current
    val reduce = LocalReduceMotion.current
    val ringColor = if (onAccent) scheme.onSurface else accentInk()
    val fillColor = scheme.primary.copy(alpha = if (tokens.isDark) 0.14f else 0.12f)
    val pressColor = scheme.onSurface.copy(alpha = 0.08f)
    val edge = scheme.scrim
    val focus = remember(interactionSource) { Animatable(0f) }
    val press = remember(interactionSource) { Animatable(0f) }
    LaunchedEffect(interactionSource, reduce) {
        trackInteractions(interactionSource) { focused, pressed ->
            val target = if (focused) 1f else 0f
            if (focus.targetValue != target) launch {
                if (reduce) focus.snapTo(target) else focus.animateTo(target, Motion.spec(false, if (focused) Motion.FAST else 90))
            }
            if (pressed) launch { press.snapTo(1f) }
            else if (press.targetValue != 0f) launch { if (reduce) press.snapTo(0f) else press.animateTo(0f, Motion.spec(false, Motion.SLOW)) }
        }
    }
    return this.drawWithContent {
        val f = focus.value
        val p = press.value
        val radius = CornerRadius(cornerRadius.toPx())
        val shift = startShift().toFloat()
        val area = Size(size.width - shift, size.height)
        if (fill && f > 0f) drawRoundRect(fillColor, topLeft = Offset(shift, 0f), size = area, cornerRadius = radius, alpha = f)
        if (p > 0f) drawRoundRect(pressColor, topLeft = Offset(shift, 0f), size = area, cornerRadius = radius, alpha = p)
        drawContent()
        if (f <= 0f) return@drawWithContent
        // 8.0 TV mode: as thick as the bold ring, with a wider halo, readable from the sofa.
        val stroke = (if (bold || tv) maxOf(width, TvMode.FOCUS_RING_DP.dp) else maxOf(width, 2.dp)).toPx()
        if (!bold) {
            // Halo: a wider, faint ring just outside the element.
            val halo = (if (tv) 6.dp else 3.dp).toPx()
            drawRoundRect(
                ringColor.copy(alpha = 0.22f * f),
                topLeft = Offset(shift - halo / 2, -halo / 2),
                size = Size(area.width + halo, area.height + halo),
                cornerRadius = CornerRadius(cornerRadius.toPx() + halo / 2),
                style = Stroke(halo)
            )
        } else {
            drawRoundRect(
                edge,
                topLeft = Offset(shift + stroke * 1.5f, stroke * 1.5f),
                size = Size(area.width - stroke * 3, area.height - stroke * 3),
                cornerRadius = CornerRadius((cornerRadius.toPx() - stroke).coerceAtLeast(0f)),
                style = Stroke(stroke / 2),
                alpha = f
            )
        }
        drawRoundRect(
            ringColor,
            topLeft = Offset(shift + stroke / 2, stroke / 2),
            size = Size(area.width - stroke, area.height - stroke),
            cornerRadius = radius,
            style = Stroke(stroke),
            alpha = f
        )
    }
}

/**
 * Grows the element a little while it is focused (cover tiles, cards). Uses the placement-free
 * `graphicsLayer` lambda without alpha, so it needs no offscreen buffer and no recomposition.
 */
@Composable
fun Modifier.focusScale(interactionSource: MutableInteractionSource, scale: Float = 1.04f): Modifier {
    val reduce = LocalReduceMotion.current
    val progress = remember(interactionSource) { Animatable(0f) }
    LaunchedEffect(interactionSource, reduce) {
        trackInteractions(interactionSource) { focused, _ ->
            val target = if (focused) 1f else 0f
            if (progress.targetValue != target) launch {
                if (reduce) progress.snapTo(target) else progress.animateTo(target, Motion.spec(false, Motion.MEDIUM))
            }
        }
    }
    return graphicsLayer {
        val s = 1f + (scale - 1f) * progress.value
        scaleX = s
        scaleY = s
    }
}

/**
 * Follows [source]'s focus and press interactions with one collector (cheaper than one
 * `collectIs…AsState` each, which matters in long lists) and reports every change.
 */
private suspend fun CoroutineScope.trackInteractions(
    source: MutableInteractionSource,
    onChange: CoroutineScope.(focused: Boolean, pressed: Boolean) -> Unit
) {
    val focuses = ArrayList<FocusInteraction.Focus>(1)
    val presses = ArrayList<PressInteraction.Press>(1)
    val scope = this
    source.interactions.collect { interaction ->
        when (interaction) {
            is FocusInteraction.Focus -> focuses += interaction
            is FocusInteraction.Unfocus -> focuses -= interaction.focus
            is PressInteraction.Press -> presses += interaction
            is PressInteraction.Release -> presses -= interaction.press
            is PressInteraction.Cancel -> presses -= interaction.press
            else -> return@collect
        }
        scope.onChange(focuses.isNotEmpty(), presses.isNotEmpty())
    }
}

@Composable
fun rememberFocusSource(): MutableInteractionSource = remember { MutableInteractionSource() }
