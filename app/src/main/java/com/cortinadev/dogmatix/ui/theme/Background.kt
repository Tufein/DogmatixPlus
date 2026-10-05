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
 */
@Composable
fun Modifier.dogmatixBackground(): Modifier {
    val background = MaterialTheme.colorScheme.background
    val tokens = LocalDogmatixTokens.current
    val glow = tokens.glow
    val glowAlt = tokens.glowAlt
    val strength = tokens.glowStrength
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
