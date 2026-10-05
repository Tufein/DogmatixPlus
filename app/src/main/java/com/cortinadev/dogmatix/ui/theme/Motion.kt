package com.cortinadev.dogmatix.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Settings → Look → Animations off (or the system's "remove animations"): every 5.0 animation
 * snaps to its end state instead of moving.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Durations of the 5.0 motion: quick enough for a gamepad, calm enough to read. */
object Motion {
    const val FAST = 120
    const val MEDIUM = 200
    const val SLOW = 280

    /** [tween] with the house easing, or [snap] when motion is reduced. */
    fun <T> spec(reduce: Boolean, durationMillis: Int = MEDIUM, delayMillis: Int = 0): AnimationSpec<T> =
        if (reduce) snap() else tween(durationMillis, delayMillis, FastOutSlowInEasing)
}

/** [Motion.spec] for the current [LocalReduceMotion]. */
@Composable
fun <T> motionSpec(durationMillis: Int = Motion.MEDIUM, delayMillis: Int = 0): AnimationSpec<T> =
    Motion.spec(LocalReduceMotion.current, durationMillis, delayMillis)
