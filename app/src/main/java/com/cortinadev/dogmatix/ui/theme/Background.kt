package com.cortinadev.dogmatix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The 5.0 ground behind every screen: the theme background with a soft accent glow from the top
 * left and a fainter partner glow from the bottom right. Brushes are built once per size and
 * drawn in the draw phase, so the glow costs no recomposition.
 * With [amoled] (the settings screens) it is a plain ground: pure #000 in the dark themes.
 */
@Composable
fun Modifier.dogmatixBackground(
    /** 8.1: the settings screens: no glow, and pure black (AMOLED) in the dark themes. */
    amoled: Boolean = false
): Modifier {
    val tokens = LocalDogmatixTokens.current
    val background = if (amoled && tokens.isDark) Color.Black else MaterialTheme.colorScheme.background
    val glow = tokens.glow
    val glowAlt = tokens.glowAlt
    val strength = if (amoled) 0f else tokens.glowStrength
    return drawWithCache {
        val reach = maxOf(size.width, size.height)
        val main = if (strength > 0f && glow.visible()) Brush.radialGradient(
            colors = listOf(glow.copy(alpha = strength), glow.copy(alpha = strength * 0.35f), Color.Transparent),
            center = Offset(size.width * 0.06f, -size.height * 0.12f),
            radius = reach * 0.85f
        ) else null
        val alt = if (strength > 0f && glowAlt.visible()) Brush.radialGradient(
            colors = listOf(glowAlt.copy(alpha = strength * 0.5f), Color.Transparent),
            center = Offset(size.width * 1.02f, size.height * 1.08f),
            radius = reach * 0.65f
        ) else null
        onDrawBehind {
            drawRect(background)
            alt?.let { drawRect(it) }
            main?.let { drawRect(it) }
        }
    }
}

private fun Color.visible(): Boolean = this != Color.Unspecified && alpha > 0f
