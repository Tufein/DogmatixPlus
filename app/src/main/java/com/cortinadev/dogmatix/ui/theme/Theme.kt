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

/** Extra tokens the Material scheme has no slot for. */
data class DogmatixTokens(
    val gradientTop: Color,
    val knobOff: Color,
    val mutedStrong: Color,
    /** Background of list cards (Sources). */
    val card: Color,
    val isDark: Boolean
)

val LocalDogmatixTokens = staticCompositionLocalOf {
    DogmatixTokens(DogmatixDark.bg2, DogmatixDark.knobOff, DogmatixDark.muted2, DogmatixDark.card, isDark = true)
}

private fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = OnAccent,
    primaryContainer = accent,
    onPrimaryContainer = OnAccent,
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
    primaryContainer = accent,
    onPrimaryContainer = OnAccent,
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
    primaryContainer = accent,
    onPrimaryContainer = OnAccent,
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
            isDark = darkTheme
        )
    } else {
        val preset = if (accent == AccentPresets.dynamic) AccentPresets.default else accent
        colorScheme = when {
            trueBlack -> blackScheme(preset)
            darkTheme -> darkScheme(preset)
            else -> lightScheme(preset)
        }
        tokens = when {
            trueBlack -> DogmatixTokens(DogmatixBlack.bg2, DogmatixBlack.knobOff, DogmatixBlack.muted2, DogmatixBlack.card, isDark = true)
            darkTheme -> DogmatixTokens(DogmatixDark.bg2, DogmatixDark.knobOff, DogmatixDark.muted2, DogmatixDark.card, isDark = true)
            else -> DogmatixTokens(DogmatixLight.bg2, DogmatixLight.knobOff, DogmatixLight.muted2, DogmatixLight.card, isDark = false)
        }
    }
    // One call site for both kinds of scheme, so switching never resets what the app shows.
    CompositionLocalProvider(LocalDogmatixTokens provides tokens) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
    }
}
