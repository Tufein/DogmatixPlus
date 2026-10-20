package com.cortinadev.dogmatix.ui.components

import android.content.pm.PackageManager
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion
import com.cortinadev.dogmatix.util.PadFocus

/**
 * 3.9.0: asks for the focus until the target is attached and takes it, for up to [frames] frames.
 * A screen's first item is often not laid out yet on the first frame (a dialog window, a sheet
 * sliding in, a list still loading); one try then left the cursor nowhere and the first press only
 * woke it. `requestFocus()` returns false (it does not throw) while the requester is unattached.
 */
suspend fun FocusRequester.requestFocusWhenReady(frames: Int = 5): Boolean {
    repeat(frames) {
        if (runCatching { requestFocus() }.getOrDefault(false)) return true
        withFrameNanos { }
    }
    return false
}

/**
 * 3.9.0: registers [requester] as the item of [route] that the press bringing a hidden cursor
 * back lands on (see [Gamepad.contentEntry]); removed again when it leaves the composition.
 */
@Composable
fun ContentEntry(route: String, requester: FocusRequester) {
    DisposableEffect(route, requester) {
        Gamepad.setContentEntry(route, requester)
        onDispose { Gamepad.clearContentEntry(route, requester) }
    }
}

/**
 * 3.9.0: how lists scroll to the item the D-pad moved to. Compose's default is a soft spring
 * that stops with the item against the edge; a held D-pad (or stick) then outruns the list and
 * moves into rows that are not on screen yet. Here the scroll is a short tween ([Motion.FAST],
 * a snap with animations off) and keeps a little room past the item ([PadFocus.scrollDistance]).
 * Left alone on Android TV hardware, where Compose already centres focused items.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvideSnappyScrolling(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val leanback = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    if (leanback) {
        content()
        return
    }
    val reduce = LocalReduceMotion.current
    val spec = remember(reduce) { SnappyBringIntoViewSpec(reduce) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

private class SnappyBringIntoViewSpec(reduce: Boolean) : BringIntoViewSpec {
    // Deprecated in the interface, but still what Compose 1.10's scroll containers animate with.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> =
        if (reduce) snap() else tween(Motion.FAST, easing = LinearOutSlowInEasing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        PadFocus.scrollDistance(offset, size, containerSize)
}
