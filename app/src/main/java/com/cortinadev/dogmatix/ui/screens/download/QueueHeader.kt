package com.cortinadev.dogmatix.ui.screens.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.BarSegment
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.ProgressRing
import com.cortinadev.dogmatix.ui.components.SectionTitle
import com.cortinadev.dogmatix.ui.components.SegmentedBar
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.theme.tabular
import com.cortinadev.dogmatix.util.QueueActions
import com.cortinadev.dogmatix.util.QueueEta
import com.cortinadev.dogmatix.util.QueueProgress
import com.cortinadev.dogmatix.util.StorageInsights
import com.cortinadev.dogmatix.util.WaitReason

/**
 * The top of the Downloads list: the queue as a whole. A ring with the bytes done of the bytes to
 * fetch, the current speed and the time left, the free space against what the queue needs, pills
 * for what is in each state and what is holding the queue back, and the whole-queue actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueueHeader(
    summary: QueueProgress.Summary,
    eta: QueueEta.Eta,
    need: StorageInsights.QueueNeed,
    free: Long?,
    shortfall: Long,
    waitingReasons: List<WaitReason>,
    held: Boolean,
    counts: QueueActions.Counts,
    onHold: () -> Unit,
    onStopAll: () -> Unit,
    onRetryFailed: () -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val busy = summary.busy
    val ringColor = if (busy) scheme.primary else scheme.tertiary
    Panel(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProgressRing(
                fraction = if (busy) summary.fraction else if (summary.completed > 0) 1f else 0f,
                size = 72.dp,
                stroke = 7.dp,
                color = ringColor
            ) {
                if (busy) {
                    Text(
                        "${(summary.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium.tabular(),
                        color = scheme.onSurface,
                        maxLines = 1
                    )
                } else {
                    Icon(
                        painterResource(if (summary.completed > 0) R.drawable.ic_check else R.drawable.ic_download),
                        contentDescription = null,
                        tint = if (summary.completed > 0) scheme.tertiary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionTitle(stringResource(R.string.q5_queue_title), icon = R.drawable.ic_download)
                if (busy) {
                    Text(
                        stringResource(R.string.q5_of, formatBytes(summary.doneBytes), formatBytes(summary.totalBytes)),
                        style = MaterialTheme.typography.titleLarge.tabular(),
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val line = progressLine(eta)
                    if (line.isNotEmpty()) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.q5_idle_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (busy && free != null && free > 0L) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_storage),
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        stringResource(R.string.q5_free_need, formatBytes(free), formatBytes(need.total)),
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (shortfall > 0L) {
                        Pill(stringResource(R.string.q5_short_by, formatBytes(shortfall)), tone = PillTone.Warning, icon = R.drawable.ic_warning)
                    }
                }
                SegmentedBar(
                    segments = listOf(
                        BarSegment(
                            fraction = (need.total.toDouble() / free.toDouble()).toFloat().coerceIn(0f, 1f),
                            color = if (shortfall > 0L) scheme.error else scheme.primary
                        )
                    ),
                    height = 8.dp
                )
            }
        }

        val hasPills = summary.active > 0 || summary.completed > 0 || summary.failed > 0 || waitingReasons.isNotEmpty()
        if (hasPills) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (summary.active > 0) Pill(stringResource(R.string.q5_pill_active, summary.active), tone = PillTone.Accent, icon = R.drawable.ic_arrow_down)
                if (summary.completed > 0) Pill(stringResource(R.string.q5_pill_done, summary.completed), tone = PillTone.Success, icon = R.drawable.ic_check)
                if (summary.failed > 0) Pill(stringResource(R.string.q5_pill_failed, summary.failed), tone = PillTone.Danger, icon = R.drawable.ic_error)
                waitingReasons.forEach { reason ->
                    val (label, icon) = when (reason) {
                        WaitReason.WIFI -> R.string.q5_wait_wifi to R.drawable.ic_wifi
                        WaitReason.CHARGER -> R.string.q5_wait_charger to R.drawable.ic_charging
                        WaitReason.NIGHT -> R.string.q5_wait_night to R.drawable.ic_night
                        WaitReason.STORAGE -> R.string.q5_wait_storage to R.drawable.ic_storage
                        WaitReason.HELD -> R.string.q5_wait_held to R.drawable.ic_pause
                        WaitReason.LOW_BATTERY -> R.string.power75_wait_battery_short to R.drawable.ic_battery
                        WaitReason.HOT -> R.string.power75_wait_hot_short to R.drawable.ic_thermostat
                    }
                    Pill(stringResource(label), tone = PillTone.Warning, icon = icon)
                }
            }
        }

        if (held || counts.any) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (held || counts.stoppable > 0) {
                    ActionPill(
                        stringResource(if (held) R.string.downloads_release else R.string.downloads_hold),
                        onHold,
                        icon = if (held) R.drawable.ic_play_arrow else R.drawable.ic_pause,
                        tone = if (held) ActionTone.Accent else ActionTone.Neutral
                    )
                }
                if (counts.stoppable > 0) ActionPill(stringResource(R.string.downloads_stop_all, counts.stoppable), onStopAll, icon = R.drawable.ic_stop)
                if (counts.retryable > 0) ActionPill(stringResource(R.string.downloads_retry_failed, counts.retryable), onRetryFailed, icon = R.drawable.ic_retry)
                if (counts.clearable > 0) ActionPill(stringResource(R.string.downloads_clear_finished, counts.clearable), onClearFinished, icon = R.drawable.ic_clear_all)
            }
        }
    }
}

/** "12.4 MB/s · 3.1 GB left · about 1 h 20 min": the speed, what is left and how long it takes. */
@Composable
private fun progressLine(eta: QueueEta.Eta): String {
    val parts = ArrayList<String>(3)
    if (eta.bytesPerSecond > 0L) parts += stringResource(R.string.q5_per_second, formatBytes(eta.bytesPerSecond))
    if (eta.remainingBytes > 0L) parts += stringResource(R.string.downloads_left, formatBytes(eta.remainingBytes))
    eta.seconds?.let { seconds ->
        val (hours, minutes) = QueueEta.hoursMinutes(seconds)
        parts += if (hours > 0) stringResource(R.string.downloads_eta_hours, hours, minutes) else stringResource(R.string.downloads_eta_minutes, minutes)
    }
    return parts.joinToString(" · ")
}
