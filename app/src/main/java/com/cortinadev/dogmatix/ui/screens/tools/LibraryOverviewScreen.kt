package com.cortinadev.dogmatix.ui.screens.tools

import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import com.cortinadev.dogmatix.data.state.ScanProgress
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.ConsoleOverview
import com.cortinadev.dogmatix.data.service.LibraryOverview
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.cortinadev.dogmatix.ui.screens.sources.SourcesViewModel
import com.cortinadev.dogmatix.ui.screens.sources.components.ConfirmDialog

/**
 * What the source scan found per console next to what is on disk: indexed games, games the
 * library marks as owned, games on disk and their size, plus when the console was last scanned.
 * A on a console rescans its sources; consoles that need attention carry a badge.
 */
@Composable
fun LibraryOverviewScreen(viewModel: LibraryOverviewViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsState()
    val isRescanning by viewModel.isRescanning.collectAsState()
    val progressMessage by viewModel.progressMessage.collectAsState()
    val progress by viewModel.progress.collectAsState()
    // The shell's instance: a scan started here keeps running after leaving the screen.
    val activity = LocalActivity.current as ComponentActivity
    val sourcesViewModel: SourcesViewModel = hiltViewModel(activity)

    var confirmRescanAll by remember { mutableStateOf(false) }
    if (confirmRescanAll) {
        ConfirmDialog(
            title = stringResource(R.string.overview_rescan_all_title),
            message = stringResource(R.string.overview_rescan_all_message),
            confirmText = stringResource(R.string.pad_rescan),
            onConfirm = sourcesViewModel::rescanAllSources,
            onDismiss = { confirmRescanAll = false }
        )
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    val overview = ui.overview
    val notes = listOfNotNull(
        overview?.unassignedGames?.takeIf { it > 0 }?.let { stringResource(R.string.overview_unassigned, it) },
        overview?.disk?.unmatchedFolders?.takeIf { it.isNotEmpty() }
            ?.let { stringResource(R.string.overview_unmatched, it.joinToString(", ")) }
    )
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_overview))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "totals") {
                if (overview == null) InfoCard(listOf(stringResource(R.string.tools_scanning)))
                else TotalsCard(overview)
            }
            if (isRescanning) {
                item(key = "progress") { ScanProgressCard(progress, progressMessage) }
            }
            item(key = "refresh") {
                ToolRow(
                    title = stringResource(R.string.tools_refresh),
                    lines = listOfNotNull(if (ui.loading) stringResource(R.string.tools_scanning) else null),
                    onClick = viewModel::refresh,
                    modifier = Modifier.focusRequester(firstFocus)
                ) {
                    PillButton(stringResource(R.string.tools_refresh), viewModel::refresh)
                }
            }
            item(key = "rescanAll") {
                ToolRow(
                    title = stringResource(R.string.overview_rescan_all),
                    lines = emptyList(),
                    onClick = { if (!isRescanning) confirmRescanAll = true }
                ) {
                    PillButton(stringResource(R.string.pad_rescan)) { if (!isRescanning) confirmRescanAll = true }
                }
            }
            if (overview != null) {
                items(overview.consoles, key = { it.id }) { console ->
                    ConsoleRow(console) { if (!isRescanning) sourcesViewModel.refreshConsole(console.id) }
                }
                if (notes.isNotEmpty()) item(key = "notes") { InfoCard(notes) }
            }
        }
    }
}

/** Percentage, sources done, time left and a bar for the running source scan. */
@Composable
private fun ScanProgressCard(progress: ScanProgress?, message: String) {
    // Re-evaluated every few seconds so the time estimate keeps moving between steps.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            now = System.currentTimeMillis()
        }
    }
    val lines = buildList {
        add(
            if (progress == null) stringResource(R.string.overview_scan_running, message.ifBlank { "…" })
            else stringResource(R.string.overview_scan_percent, progress.percent)
        )
        if (progress != null) {
            val remaining = progress.remainingMillis(now)
            add(
                if (remaining == null) stringResource(R.string.overview_scan_sources, progress.done, progress.total)
                else stringResource(
                    R.string.overview_scan_sources_eta,
                    progress.done, progress.total,
                    DateUtils.formatElapsedTime((remaining / 1000).coerceAtLeast(1))
                )
            )
            if (message.isNotBlank()) add(message)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        InfoCard(lines, accent = true)
        LinearProgressIndicator(
            progress = { progress?.fraction ?: 0f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TotalsCard(overview: LibraryOverview) {
    val percent = if (overview.indexed == 0) 0 else (overview.owned * 100L / overview.indexed).toInt()
    val disk = overview.disk.freeBytes?.let {
        stringResource(R.string.overview_disk, overview.onDisk, formatBytes(overview.onDiskBytes), formatBytes(it))
    } ?: stringResource(R.string.overview_disk_no_free, overview.onDisk, formatBytes(overview.onDiskBytes))
    InfoCard(
        listOfNotNull(
            stringResource(R.string.overview_totals, overview.consoles.size, overview.indexed, overview.owned, percent),
            disk,
            overview.disk.rootDisplay.ifBlank { null }
        )
    )
}

@Composable
private fun ConsoleRow(console: ConsoleOverview, onRescan: () -> Unit) {
    val scanned = console.scannedAt?.let {
        stringResource(R.string.overview_scanned, DateUtils.getRelativeTimeSpanString(it).toString())
    } ?: stringResource(R.string.overview_never_scanned)
    val path = console.path?.displayPath?.ifBlank { null }
    val (badge, warning) = when (console.status) {
        ConsoleOverview.Status.OK -> R.string.overview_status_ok to false
        ConsoleOverview.Status.NO_SOURCES -> R.string.overview_status_no_sources to true
        ConsoleOverview.Status.NOTHING_FOUND -> R.string.overview_status_nothing_found to true
        ConsoleOverview.Status.NOT_SCANNED -> R.string.overview_status_not_scanned to true
    }
    ToolRow(
        title = if (console.shortName.isNotBlank() && !console.shortName.equals(console.name, ignoreCase = true))
            "${console.name} · ${console.shortName}" else console.name,
        lines = listOf(
            stringResource(R.string.overview_console_stats, console.indexed, console.owned, console.onDisk, formatBytes(console.onDiskBytes)),
            listOfNotNull(path, scanned).joinToString(" · ")
        ),
        onClick = onRescan,
        badge = { Badge(stringResource(badge), warning) }
    ) {
        PillButton(stringResource(R.string.pad_rescan), onRescan)
    }
}
