package com.cortinadev.dogmatix.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion

/** ‹ value › control; the whole row also answers D-pad left/right when focused (see SettingsScreen). */
@Composable
fun Stepper(
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    valueWidth: Dp = 48.dp,
    buttonSize: Dp = 36.dp
) {
    val reduce = LocalReduceMotion.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepButton(R.drawable.ic_chevron_left, buttonSize, onDecrement)
        // The new value ticks in from below: a small sign that the press did something.
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                if (reduce) EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(tween(Motion.FAST)) + slideInVertically(tween(Motion.MEDIUM)) { it / 3 }) togetherWith
                    (fadeOut(tween(90)) + slideOutVertically(tween(Motion.FAST)) { -it / 3 })
            },
            contentAlignment = Alignment.Center,
            label = "stepper",
            modifier = Modifier.width(valueWidth)
        ) { shown ->
            TruncatedText(
                shown,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(valueWidth)
            )
        }
        StepButton(R.drawable.ic_chevron_right, buttonSize, onIncrement)
    }
}

@Composable
private fun StepButton(icon: Int, size: Dp, onClick: () -> Unit) {
    val source = rememberFocusSource()
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .focusRing(source, cornerRadius = 10.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(size * 0.58f))
    }
}
