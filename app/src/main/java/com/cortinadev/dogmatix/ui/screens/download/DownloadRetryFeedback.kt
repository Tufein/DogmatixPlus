package com.cortinadev.dogmatix.ui.screens.download

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.PendingAutoRetry
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Only visible rows tick; the service exposes one deadline rather than a per-second queue update. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadRetryFeedback(
    pending: PendingAutoRetry,
    onRetryNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = true
) {
    var seconds by remember(pending) { mutableLongStateOf(pending.remainingSeconds(SystemClock.elapsedRealtime())) }
    LaunchedEffect(pending) {
        while (true) {
            seconds = pending.remainingSeconds(SystemClock.elapsedRealtime())
            if (seconds == 0L) break
            delay(1_000L)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (seconds > 0L) stringResource(R.string.retry26_countdown, seconds)
            else stringResource(R.string.retry26_ready),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            stringResource(R.string.retry26_attempt, pending.attempt, pending.maxAttempts),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionsEnabled) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionPill(stringResource(R.string.retry26_now), onRetryNow, icon = R.drawable.ic_retry, tone = ActionTone.Accent)
            ActionPill(stringResource(R.string.retry26_cancel), onCancel, icon = R.drawable.ic_stop)
        }
    }
}

@Composable
fun DownloadFailureTimestamp(failureAt: Long?, modifier: Modifier = Modifier) {
    if (failureAt == null || failureAt <= 0L) return
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(failureAt, locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(failureAt)) }
    Text(
        stringResource(R.string.retry26_failure_time, date),
        modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
