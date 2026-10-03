package com.cortinadev.dogmatix.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.cortinadev.dogmatix.R

enum class ThemeMode(val labelRes: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
    TRUE_BLACK(R.string.theme_true_black);

    companion object {
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * Accent colours offered in Settings, stored as the hex string. [dynamic] stands for Material You:
 * the whole colour scheme then comes from the wallpaper (Android 12 and later).
 */
object AccentPresets {
    /** All bright enough for the dark text drawn on top of them. */
    val all: List<Color> = listOf(
        Color(0xFFFF7F00), // orange (default)
        Color(0xFFFFB300), // amber
        Color(0xFFD4E157), // lime
        Color(0xFF7BE03A), // green
        Color(0xFF2ED6B8), // teal
        Color(0xFF3CC8FF), // cyan
        Color(0xFF4E99FF), // blue
        Color(0xFF8C9EFF), // indigo
        Color(0xFFC390E8), // lilac
        Color(0xFFE57BFF), // magenta
        Color(0xFFFF4D8D), // pink
        Color(0xFFFF6B5A)  // coral
    )
    val default: Color = all.first()

    /** Material You (wallpaper colours); a marker, never drawn itself. */
    val dynamic: Color = Color.Unspecified
    const val DYNAMIC = "dynamic"

    val dynamicAvailable: Boolean get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    /** What Settings offers, Material You first where the system supports it. */
    val choices: List<Color> get() = (if (dynamicAvailable) listOf(dynamic) else emptyList()) + all

    fun toHex(color: Color): String =
        if (color == dynamic) DYNAMIC else String.format("#%06X", 0xFFFFFF and color.toArgb())

    fun fromHex(hex: String?): Color {
        if (hex.isNullOrBlank()) return default
        if (hex == DYNAMIC) return if (dynamicAvailable) dynamic else default
        return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(default)
    }
}
