package com.tufein.valkyr.ui.screens.cloud.saves

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.pillColors
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.CloudActivity

/**
 * The small cloud in the top bar (5.0): muted when all is synced, an accent arc turning around it
 * while something syncs, a warning tint and a count when conflicts or errors wait. Renders nothing
 * while no cloud feature is set up. The arc turns in the draw phase only (no recomposition) and
 * stands still with animations off.
 *
 * Called next to `RescanIndicator` in `ShellHeader` (ui/components/AppShell.kt), with a click that
 * opens `NavRoutes.Cloud`.
 */
@Composable
fun CloudStatusIndicator(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val service = rememberCloudStatusService()
    val status by service.status.collectAsStateWithLifecycle()
    if (!status.visible) return

    val scheme = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val source = rememberFocusSource()
    val syncing = status.activity == CloudActivity.SYNCING
    val needsYou = status.attention > 0
    val (warnFill, warnText) = pillColors(PillTone.Warning)
    val tint = when {
        syncing -> scheme.primary
        status.activity == CloudActivity.ATTENTION -> warnText
        else -> scheme.onSurfaceVariant
    }
    val icon = when (status.activity) {
        CloudActivity.SYNCING -> R.drawable.ic_cloud_sync
        CloudActivity.ATTENTION -> R.drawable.ic_cloud
        else -> R.drawable.ic_cloud_done
    }
    val description = when {
        syncing -> stringResource(R.string.csave_status_syncing)
        needsYou -> pluralStringResource(R.plurals.csave_status_attention, status.attention, status.attention)
        else -> stringResource(R.string.csave_status_idle)
    }
    // Only while syncing (and moving is allowed): the transition leaves with the arc.
    val spin: State<Float>? = if (syncing && !reduce) {
        rememberInfiniteTransition(label = "cloudSync").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing)),
            label = "cloudSyncSpin"
        )
    } else null
    val progress = status.progress
    val arc = scheme.primary
    val track = scheme.primary.copy(alpha = 0.18f)

    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .focusRing(source, cornerRadius = 18.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .drawBehind {
                    if (!syncing) return@drawBehind
                    val stroke = 2.dp.toPx()
                    val inset = stroke / 2 + 1.dp.toPx()
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    val topLeft = Offset(inset, inset)
                    drawArc(track, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                    // Known progress: the arc grows with it; unknown: a fixed arc that turns.
                    val sweep = progress?.let { 24f + 336f * it } ?: 110f
                    val start = (spin?.value ?: 0f) - 90f
                    drawArc(
                        arc, startAngle = start, sweepAngle = sweep, useCenter = false,
                        topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        if (needsYou) {
            Text(
                status.attention.coerceAtMost(99).toString(),
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = warnText,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(warnFill)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}
