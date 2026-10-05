package com.cortinadev.dogmatix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * The accent colour as ink: for text, thin icons and rings on a surface. In dark themes that is the
 * accent itself; in the light theme a bright accent (yellow, lime, amber…) is darkened until it can
 * be read on the pale surface. Fills keep using `primary`.
 */
@Composable
@ReadOnlyComposable
fun accentInk(): Color {
    val primary = MaterialTheme.colorScheme.primary
    if (LocalDogmatixTokens.current.isDark) return primary
    return inkOnLight(primary)
}

/** [color] darkened for a light surface: untouched when already dark enough, otherwise blended with black. */
fun inkOnLight(color: Color): Color {
    val l = color.luminance()
    if (l <= 0.18f) return color
    // The brighter the accent, the more black; 0.55 at full white luminance.
    return lerp(color, Color.Black, (0.25f + (l - 0.18f) * 0.75f).coerceAtMost(0.55f))
}

/** [color] (a role such as tertiary) as text ink: itself in dark themes, darkened where needed in the light theme. */
@Composable
@ReadOnlyComposable
fun inkOf(color: Color): Color = if (LocalDogmatixTokens.current.isDark) color else inkOnLight(color)
