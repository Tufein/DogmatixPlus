package com.cortinadev.dogmatix.widget

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.SettingsDataStore
import com.cortinadev.dogmatix.ui.theme.AccentPresets
import com.cortinadev.dogmatix.ui.theme.DogmatixDark
import com.cortinadev.dogmatix.ui.theme.DogmatixLight
import com.cortinadev.dogmatix.ui.theme.ThemeMode
import com.cortinadev.dogmatix.util.WidgetLayout
import kotlinx.coroutines.flow.first

/**
 * The app's theme and accent as the colours of a home-screen widget. RemoteViews cannot take them
 * from the app, so they are resolved here; both widgets share this.
 */
internal class WidgetLook(
    val background: Int,
    val dark: Boolean,
    val accent: Int,
    val accentText: Int,
    val text: Int,
    val muted: Int,
    val track: Int
) {
    companion object {
        suspend fun resolve(context: Context, settings: SettingsDataStore): WidgetLook {
            val mode = ThemeMode.fromName(settings.themeMode.first())
            val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val dark = when (mode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.TRUE_BLACK -> true
            }
            val preset = AccentPresets.fromHex(settings.accentColor.first())
            val accent = when {
                // Material You: the wallpaper's accent, as the app's own scheme takes it (tone 80 dark, 40 light).
                preset == AccentPresets.dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
                preset == AccentPresets.dynamic -> AccentPresets.default.toArgb()
                else -> preset.toArgb()
            }
            return WidgetLook(
                background = when {
                    mode == ThemeMode.TRUE_BLACK -> R.drawable.widget_bg_black
                    dark -> R.drawable.widget_bg_dark
                    else -> R.drawable.widget_bg_light
                },
                dark = dark,
                accent = accent,
                accentText = WidgetLayout.accentTextOn(accent, darkPanel = dark),
                text = (if (dark) DogmatixDark.text else DogmatixLight.text).toArgb(),
                muted = (if (dark) DogmatixDark.muted2 else DogmatixLight.muted).toArgb(),
                track = if (dark) 0x33FFFFFF else 0x1F000000
            )
        }
    }
}
