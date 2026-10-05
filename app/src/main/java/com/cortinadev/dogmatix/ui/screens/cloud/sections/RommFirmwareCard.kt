package com.cortinadev.dogmatix.ui.screens.cloud.sections

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.FirmwareFailure
import com.cortinadev.dogmatix.data.service.FirmwareFetchReport
import com.cortinadev.dogmatix.data.service.FirmwareFetchState
import com.cortinadev.dogmatix.data.service.FirmwareProblem
import com.cortinadev.dogmatix.data.service.RommFirmwareService
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.IconTile
import com.cortinadev.dogmatix.ui.components.MeterBar
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.PrimaryButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * Stage B: call once in BiosScreen (a first item of its LazyColumn, after the folder row) as
 * RommFirmwareCard(allSystems = ui.allSystems, onFetched = { viewModel.refresh() }, onPickFolder = { picker.launch(null) })
 */

@HiltViewModel
class RommFirmwareViewModel @Inject constructor(
    private val service: RommFirmwareService,
    settingsRepository: SettingsRepository
) : ViewModel() {

    val state: StateFlow<FirmwareFetchState> = service.state

    /** RomM URL and token are set; null until known. */
    val configured: StateFlow<Boolean?> = combine(settingsRepository.rommUrl, settingsRepository.rommToken) { url, token ->
        url.isNotBlank() && token.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _fetched = Channel<Unit>(Channel.CONFLATED)
    /** A run wrote at least one file: the BIOS check should look again. */
    val fetched: Flow<Unit> = _fetched.receiveAsFlow()

    fun fetch(allSystems: Boolean) {
        if (service.state.value.running) return
        viewModelScope.launch {
            val report = service.fetchMissing(allSystems)
            if (report.fetched.isNotEmpty()) _fetched.trySend(Unit)
        }
    }
}

/**
 * "BIOS from RomM" (5.0, BIOS tool): fetches the BIOS files the check finds missing from the
 * RomM server, keeps only files whose MD5 checks out, and lists what came, what RomM does not
 * have and what could not be kept. Draws nothing when RomM is not set up.
 *
 * @param allSystems the BIOS screen's "show all systems" state (which systems count)
 * @param onFetched called after a run wrote files, to check the folder again
 * @param onPickFolder opens the folder picker (offered when the folder is missing or read-only)
 */
@Composable
fun RommFirmwareCard(
    allSystems: Boolean,
    onFetched: () -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: RommFirmwareViewModel = hiltViewModel()
    val configured by viewModel.configured.collectAsState()
    val state by viewModel.state.collectAsState()
    val latestOnFetched by rememberUpdatedState(onFetched)
    LaunchedEffect(viewModel) { viewModel.fetched.collect { latestOnFetched() } }
    if (configured != true) return

    val scheme = MaterialTheme.colorScheme
    Panel(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(R.drawable.ic_cloud_download, size = 34.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.romm5_fw_title), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.romm5_fw_pitch), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        val report = state.report
        when {
            state.running -> RunningLine(state)
            report != null -> ReportBlock(report, onPickFolder)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                stringResource(R.string.romm5_fw_action),
                onClick = { viewModel.fetch(allSystems) },
                icon = R.drawable.ic_download,
                enabled = !state.running
            )
        }
    }
}

@Composable
private fun RunningLine(state: FirmwareFetchState) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                if (state.total > 0) stringResource(R.string.romm5_fw_running, state.done, state.total) else stringResource(R.string.romm5_fw_checking),
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant
            )
        }
        if (state.total > 0) MeterBar(fraction = state.done.toFloat() / state.total, height = 6.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportBlock(report: FirmwareFetchReport, onPickFolder: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val problem = report.problem
    if (problem != null) {
        val text = when (problem) {
            FirmwareProblem.NOT_SET_UP -> stringResource(R.string.romm5_err_not_set_up)
            FirmwareProblem.NO_FOLDER -> stringResource(R.string.romm5_fw_no_folder)
            FirmwareProblem.NO_WRITE_ACCESS -> stringResource(R.string.romm5_fw_no_write)
            FirmwareProblem.NOTHING_MISSING -> stringResource(R.string.romm5_fw_nothing_missing)
            FirmwareProblem.SERVER -> stringResource(R.string.romm5_fw_server_problem, stringResource(rommErrorText(report.problemKind)))
        }
        val good = problem == FirmwareProblem.NOTHING_MISSING
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                painterResource(if (good) R.drawable.ic_check_circle else R.drawable.ic_warning),
                contentDescription = null,
                tint = if (good) scheme.tertiary else scheme.error,
                modifier = Modifier.size(18.dp)
            )
            Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, modifier = Modifier.weight(1f))
        }
        if (problem == FirmwareProblem.NO_FOLDER || problem == FirmwareProblem.NO_WRITE_ACCESS) {
            ActionPill(stringResource(R.string.romm5_fw_pick_folder), onClick = onPickFolder, icon = R.drawable.ic_folder_open, tone = ActionTone.Accent)
        }
        return
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(pluralStringResource(R.plurals.romm5_fw_fetched_count, report.fetched.size, report.fetched.size), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
        if (report.notOnServer.isNotEmpty()) {
            Pill(pluralStringResource(R.plurals.romm5_fw_not_on_server_count, report.notOnServer.size, report.notOnServer.size), tone = PillTone.Warning, icon = R.drawable.ic_cloud_off)
        }
        if (report.failed.isNotEmpty()) {
            Pill(pluralStringResource(R.plurals.romm5_fw_failed_count, report.failed.size, report.failed.size), tone = PillTone.Danger, icon = R.drawable.ic_error_circle)
        }
        if (report.finishedAt > 0) {
            Pill(DateUtils.getRelativeTimeSpanString(report.finishedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(), icon = R.drawable.ic_history)
        }
    }
    if (report.fetched.isNotEmpty() && report.failed.isEmpty() && report.notOnServer.isEmpty()) {
        Text(stringResource(R.string.romm5_fw_all_done), style = MaterialTheme.typography.bodyMedium, color = scheme.tertiary)
    }
    FileList(
        title = stringResource(R.string.romm5_fw_fetched_title),
        lines = report.fetched.map { (system, path) -> "$system · $path" },
        color = scheme.onSurface
    )
    FileList(
        title = stringResource(R.string.romm5_fw_failed_title),
        lines = report.failed.map { (system, path, why) -> "$system · $path · ${stringResource(failureText(why))}" },
        color = scheme.error
    )
    // Required files first: those are the ones a game will not start without.
    FileList(
        title = stringResource(R.string.romm5_fw_not_on_server_title),
        lines = report.notOnServer.sortedByDescending { it.third }.map { (system, path, required) ->
            if (required) "$system · $path · ${stringResource(R.string.romm5_fw_required)}" else "$system · $path"
        },
        color = scheme.onSurfaceVariant
    )
}

/** A short titled list; long lists end with "+ n more". */
@Composable
private fun FileList(title: String, lines: List<String>, color: Color) {
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        lines.take(MAX_LINES).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (lines.size > MAX_LINES) {
            Text(stringResource(R.string.romm5_fw_more, lines.size - MAX_LINES), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val MAX_LINES = 6

private fun failureText(failure: FirmwareFailure): Int = when (failure) {
    FirmwareFailure.WRONG_DUMP -> R.string.romm5_fw_fail_wrong_dump
    FirmwareFailure.CORRUPT -> R.string.romm5_fw_fail_corrupt
    FirmwareFailure.TOO_LARGE -> R.string.romm5_fw_fail_too_large
    FirmwareFailure.DOWNLOAD -> R.string.romm5_fw_fail_download
    FirmwareFailure.WRITE -> R.string.romm5_fw_fail_write
}
