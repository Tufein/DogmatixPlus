package com.cortinadev.dogmatix.ui.components

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Accent ring drawn while the element owns keyboard / D-pad focus.
 * Pair it with a `clickable(interactionSource = source, ...)` on the same element.
 */
/** Settings → "Bold focus ring": a thicker ring with a dark edge, readable on a TV or in sunlight. */
val LocalBoldFocus = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun Modifier.focusRing(
    interactionSource: MutableInteractionSource,
    cornerRadius: Dp = 8.dp,
    width: Dp = 1.5.dp,
    /** Shift (px) of the ring's left edge, read while drawing: follows the filter panel animation. */
    startShift: () -> Int = { 0 }
): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val color = MaterialTheme.colorScheme.primary
    val bold = LocalBoldFocus.current
    val edge = MaterialTheme.colorScheme.scrim
    return this.drawWithContent {
        drawContent()
        if (!focused) return@drawWithContent
        val stroke = (if (bold) maxOf(width, 3.5.dp) else width).toPx()
        val shift = startShift().toFloat()
        if (bold) drawRoundRect(
            edge,
            topLeft = Offset(shift + stroke * 1.5f, stroke * 1.5f),
            size = Size(size.width - shift - stroke * 3, size.height - stroke * 3),
            cornerRadius = CornerRadius((cornerRadius.toPx() - stroke).coerceAtLeast(0f)),
            style = Stroke(stroke / 2)
        )
        drawRoundRect(
            color,
            topLeft = Offset(shift + stroke / 2, stroke / 2),
            size = Size(size.width - shift - stroke, size.height - stroke),
            cornerRadius = CornerRadius(cornerRadius.toPx()),
            style = Stroke(stroke)
        )
    }
}

@Composable
fun rememberFocusSource(): MutableInteractionSource = remember { MutableInteractionSource() }
