package com.cortinadev.dogmatix.ui.components

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.util.TvMode
import com.cortinadev.dogmatix.util.TvModeSetting

/**
 * 8.0: the TV layout is on (Android TV, or a handheld docked to a television; see [TvMode]).
 * Item composables check it to grow (library rows, covers); the focus ring gets thicker.
 */
val LocalTvMode = staticCompositionLocalOf { false }

/** Whether the TV layout applies on this device with [setting]; re-read when the configuration changes. */
@Composable
fun rememberTvModeActive(setting: TvModeSetting): Boolean {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val television = remember(context) {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
    val uiModeTelevision = television ||
        config.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    return TvMode.isActive(
        setting = setting,
        uiModeTelevision = uiModeTelevision,
        screenWidthDp = config.screenWidthDp,
        screenHeightDp = config.screenHeightDp,
        hasTouchscreen = config.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH
    )
}

/**
 * Provides [LocalTvMode] for [setting] and, when it is on, the larger TV text (+15 % on top of
 * the density already provided, so the text size setting still applies).
 */
@Composable
fun ProvideTvMode(setting: TvModeSetting, content: @Composable () -> Unit) {
    val active = rememberTvModeActive(setting)
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalTvMode provides active,
        LocalDensity provides if (active) Density(density.density, density.fontScale * TvMode.TEXT_FACTOR) else density
    ) { content() }
}

/**
 * Overscan-safe margins in TV mode (5 % of the screen, at least 48 dp at the sides and 27 dp top
 * and bottom): televisions may crop the picture's edges. Nothing in the normal layout.
 */
@Composable
fun Modifier.tvSafeArea(): Modifier {
    if (!LocalTvMode.current) return this
    val config = LocalConfiguration.current
    return padding(
        horizontal = TvMode.sidePaddingDp(config.screenWidthDp).dp,
        vertical = TvMode.edgePaddingDp(config.screenHeightDp).dp
    )
}

/** [size] grown for TV mode (library rows, covers); unchanged otherwise. */
@Composable
fun tvSized(size: Dp): Dp = if (LocalTvMode.current) size * TvMode.ITEM_FACTOR else size
