package com.cortinadev.dogmatix.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Extra tokens the Material scheme has no slot for. */
data class DogmatixTokens(
    val gradientTop: Color,
    val knobOff: Color,
    val mutedStrong: Color,
    /** Background of list cards (Sources). */
    val card: Color,
    val isDark: Boolean,
    /** 5.0: the ambient glow painted behind every screen (accent, and a hue-shifted partner). */
    val glow: Color = Color.Transparent,
    val glowAlt: Color = Color.Transparent,
    /** Peak alpha of [glow]; 0 switches the glow off. */
    val glowStrength: Float = 0f,
    /** Soft light along the top edge of panels (dark themes only). */
    val highlight: Color = Color.Transparent,
    /** Panel border. */
    val hairline: Color = Color.Transparent
)

val LocalDogmatixTokens = staticCompositionLocalOf {
    DogmatixTokens(DogmatixDark.bg2, DogmatixDark.knobOff, DogmatixDark.muted2, DogmatixDark.card, isDark = true)
}

private fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = OnAccent,
    primaryContainer = lerp(DogmatixDark.panel, accent, 0.24f),
    onPrimaryContainer = lerp(accent, Color.White, 0.30f),
    secondaryContainer = DogmatixDark.raised,
    onSecondaryContainer = DogmatixDark.text,
    tertiaryContainer = lerp(DogmatixDark.panel, StatusSuccess, 0.22f),
    onTertiaryContainer = lerp(StatusSuccess, Color.White, 0.35f),
    errorContainer = lerp(DogmatixDark.panel, StatusDanger, 0.24f),
    onErrorContainer = lerp(StatusDanger, Color.White, 0.45f),
    scrim = Color.Black,
    surfaceBright = DogmatixDark.raised,
    surfaceDim = DogmatixDark.bg,
    inversePrimary = lerp(accent, Color.Black, 0.35f),
    surfaceTint = accent,
    secondary = DogmatixDark.muted2,
    onSecondary = DogmatixDark.bg,
    tertiary = StatusSuccess,
    onTertiary = OnAccent,
    error = StatusDanger,
    onError = Color.White,
    background = DogmatixDark.bg,
    onBackground = DogmatixDark.text,
    surface = DogmatixDark.bg,
    onSurface = DogmatixDark.text,
    surfaceVariant = DogmatixDark.panel,
    onSurfaceVariant = DogmatixDark.muted,
    surfaceContainerLowest = DogmatixDark.bg,
    surfaceContainerLow = DogmatixDark.panel,
    surfaceContainer = DogmatixDark.panel,
    surfaceContainerHigh = DogmatixDark.raised,
    surfaceContainerHighest = DogmatixDark.knobOff,
    outline = DogmatixDark.line2,
    outlineVariant = DogmatixDark.line,
    inverseSurface = DogmatixDark.text,
    inverseOnSurface = DogmatixDark.bg
)

private fun blackScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = OnAccent,
    primaryContainer = lerp(DogmatixBlack.panel, accent, 0.24f),
    onPrimaryContainer = lerp(accent, Color.White, 0.30f),
    secondaryContainer = DogmatixBlack.raised,
    onSecondaryContainer = DogmatixBlack.text,
    tertiaryContainer = lerp(DogmatixBlack.panel, StatusSuccess, 0.22f),
    onTertiaryContainer = lerp(StatusSuccess, Color.White, 0.35f),
    errorContainer = lerp(DogmatixBlack.panel, StatusDanger, 0.24f),
    onErrorContainer = lerp(StatusDanger, Color.White, 0.45f),
    scrim = Color.Black,
    surfaceBright = DogmatixBlack.raised,
    surfaceDim = DogmatixBlack.bg,
    inversePrimary = lerp(accent, Color.Black, 0.35f),
    surfaceTint = accent,
    secondary = DogmatixBlack.muted2,
    onSecondary = DogmatixBlack.bg,
    tertiary = StatusSuccess,
    onTertiary = OnAccent,
    error = StatusDanger,
    onError = Color.White,
    background = DogmatixBlack.bg,
    onBackground = DogmatixBlack.text,
    surface = DogmatixBlack.bg,
    onSurface = DogmatixBlack.text,
    surfaceVariant = DogmatixBlack.panel,
    onSurfaceVariant = DogmatixBlack.muted,
    surfaceContainerLowest = DogmatixBlack.bg,
    surfaceContainerLow = DogmatixBlack.panel,
    surfaceContainer = DogmatixBlack.panel,
    surfaceContainerHigh = DogmatixBlack.raised,
    surfaceContainerHighest = DogmatixBlack.knobOff,
    outline = DogmatixBlack.line2,
    outlineVariant = DogmatixBlack.line,
    inverseSurface = DogmatixBlack.text,
    inverseOnSurface = DogmatixBlack.bg
)

private fun lightScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = OnAccent,
    primaryContainer = lerp(DogmatixLight.card, accent, 0.20f),
    onPrimaryContainer = lerp(accent, Color.Black, 0.55f),
    secondaryContainer = DogmatixLight.raised,
    onSecondaryContainer = DogmatixLight.text,
    tertiaryContainer = lerp(DogmatixLight.card, Color(0xFF3E9D1B), 0.18f),
    onTertiaryContainer = Color(0xFF235C0E),
    errorContainer = lerp(DogmatixLight.card, Color(0xFFD8322F), 0.16f),
    onErrorContainer = Color(0xFF8C1512),
    scrim = Color.Black,
    surfaceBright = DogmatixLight.card,
    surfaceDim = DogmatixLight.panel,
    inversePrimary = lerp(accent, Color.White, 0.35f),
    surfaceTint = accent,
    secondary = DogmatixLight.muted2,
    onSecondary = DogmatixLight.bg,
    tertiary = Color(0xFF3E9D1B),
    onTertiary = Color.White,
    error = Color(0xFFD8322F),
    onError = Color.White,
    background = DogmatixLight.bg,
    onBackground = DogmatixLight.text,
    surface = DogmatixLight.bg,
    onSurface = DogmatixLight.text,
    surfaceVariant = DogmatixLight.panel,
    onSurfaceVariant = DogmatixLight.muted,
    surfaceContainerLowest = DogmatixLight.bg2,
    surfaceContainerLow = DogmatixLight.panel,
    surfaceContainer = DogmatixLight.panel,
    surfaceContainerHigh = DogmatixLight.raised,
    surfaceContainerHighest = DogmatixLight.knobOff,
    outline = DogmatixLight.line2,
    outlineVariant = DogmatixLight.line,
    inverseSurface = DogmatixLight.text,
    inverseOnSurface = DogmatixLight.bg
)

@Composable
fun DogmatixTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accent: Color = AccentPresets.default,
    /** Settings → Look → background glow. */
    glow: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.TRUE_BLACK -> true
    }
    val trueBlack = themeMode == ThemeMode.TRUE_BLACK
    val context = LocalContext.current
    val dynamic = accent == AccentPresets.dynamic && AccentPresets.dynamicAvailable
    val colorScheme: ColorScheme
    val tokens: DogmatixTokens
    if (dynamic) {
        // Material You: the whole scheme follows the wallpaper; the Dogmatix tokens are taken from it.
        val base = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        colorScheme = if (trueBlack) base.copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black) else base
        tokens = DogmatixTokens(
            gradientTop = if (trueBlack) Color.Black else colorScheme.surfaceContainerLow,
            knobOff = colorScheme.surfaceContainerHighest,
            mutedStrong = colorScheme.onSurfaceVariant,
            card = if (darkTheme) colorScheme.surfaceContainerHigh else colorScheme.surfaceContainerLowest,
            isDark = darkTheme,
            glow = colorScheme.primary,
            glowAlt = colorScheme.tertiary,
            glowStrength = if (glow) glowStrength(darkTheme, trueBlack) else 0f,
            highlight = if (darkTheme) Color.White.copy(alpha = 0.045f) else Color.Transparent,
            hairline = colorScheme.outlineVariant.copy(alpha = if (darkTheme) 0.75f else 0.9f)
        )
    } else {
        val preset = if (accent == AccentPresets.dynamic) AccentPresets.default else accent
        colorScheme = when {
            trueBlack -> blackScheme(preset)
            darkTheme -> darkScheme(preset)
            else -> lightScheme(preset)
        }
        val glowColor = preset
        val glowAlt = hueShift(preset, 48f)
        val strength = if (glow) glowStrength(darkTheme, trueBlack) else 0f
        tokens = when {
            trueBlack -> DogmatixTokens(DogmatixBlack.bg2, DogmatixBlack.knobOff, DogmatixBlack.muted2, DogmatixBlack.card, isDark = true,
                glow = glowColor, glowAlt = glowAlt, glowStrength = strength, highlight = Color.White.copy(alpha = 0.035f), hairline = DogmatixBlack.line)
            darkTheme -> DogmatixTokens(DogmatixDark.bg2, DogmatixDark.knobOff, DogmatixDark.muted2, DogmatixDark.card, isDark = true,
                glow = glowColor, glowAlt = glowAlt, glowStrength = strength, highlight = Color.White.copy(alpha = 0.045f), hairline = DogmatixDark.line)
            else -> DogmatixTokens(DogmatixLight.bg2, DogmatixLight.knobOff, DogmatixLight.muted2, DogmatixLight.card, isDark = false,
                glow = glowColor, glowAlt = glowAlt, glowStrength = strength, highlight = Color.Transparent, hairline = DogmatixLight.line)
        }
    }
    // One call site for both kinds of scheme, so switching never resets what the app shows.
    CompositionLocalProvider(LocalDogmatixTokens provides tokens) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography, shapes = DogmatixShapes, content = content)
    }
}

/** 5.0 corner scale: 4 tags, 8 small controls, 12 panels and rows, 16 sheets, 24 dialogs. */
val DogmatixShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/** How strongly the ambient glow shows: clearly in dark, softer in light, a hint on true black. */
private fun glowStrength(dark: Boolean, trueBlack: Boolean): Float = when {
    trueBlack -> 0.07f
    dark -> 0.16f
    else -> 0.11f
}

/** [color] with its hue turned by [degrees]: the partner colour of the second glow. */
internal fun hueShift(color: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}
