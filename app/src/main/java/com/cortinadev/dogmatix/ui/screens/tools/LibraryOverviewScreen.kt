package com.cortinadev.dogmatix.ui.screens.tools

import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.cortinadev.dogmatix.data.state.ScanProgress
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
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
import com.cortinadev.dogmatix.ui.components.MeterBar
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.ProgressRing
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.theme.consoleColor
import com.cortinadev.dogmatix.ui.theme.tabular
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
            title = stringResource(R.string.overview_full_rescan_title),
            message = stringResource(R.string.overview_full_rescan_message),
            confirmText = stringResource(R.string.pad_rescan),
            onConfirm = { sourcesViewModel.rescanAllSources(force = true) },
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
        ToolsTitle(stringResource(R.string.nav_overview), icon = NavRoutes.Overview.icon)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "totals") {
                if (overview == null) InfoCard(listOf(stringResource(R.string.tools_scanning)), icon = R.drawable.ic_hourglass)
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
                    modifier = Modifier.focusRequester(firstFocus),
                    icon = R.drawable.ic_retry
                ) {
                    ToolAction(stringResource(R.string.tools_refresh), onClick = viewModel::refresh)
                }
            }
            item(key = "rescanAll") {
                ToolRow(
                    title = stringResource(R.string.overview_full_rescan),
                    lines = listOf(stringResource(R.string.overview_full_rescan_hint)),
                    onClick = { if (!isRescanning) confirmRescanAll = true },
                    icon = R.drawable.ic_sync
                ) {
                    ToolAction(stringResource(R.string.pad_rescan)) { if (!isRescanning) confirmRescanAll = true }
                }
            }
            if (overview != null) {
                items(overview.consoles, key = { it.id }) { console ->
                    ConsoleRow(console) { if (!isRescanning) sourcesViewModel.refreshConsole(console.id) }
                }
                if (notes.isNotEmpty()) item(key = "notes") { InfoCard(notes, icon = R.drawable.ic_warning, danger = true) }
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
        InfoCard(lines, accent = true, icon = R.drawable.ic_sync)
        MeterBar(progress?.fraction ?: 0f, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    }
}

/** The ring of how much of the indexed library is owned, and the numbers behind it. */
@Composable
private fun TotalsCard(overview: LibraryOverview) {
    val percent = if (overview.indexed == 0) 0 else (overview.owned * 100L / overview.indexed).toInt()
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Panel(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProgressRing(
                    fraction = if (overview.indexed == 0) 0f else overview.owned.toFloat() / overview.indexed,
                    size = 84.dp,
                    stroke = 9.dp,
                    center = {
                        Text("$percent%", style = MaterialTheme.typography.titleLarge.tabular(), color = scheme.onSurface, maxLines = 1)
                    }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tools5_owned), style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                    Text(
                        stringResource(R.string.tools5_owned_of, overview.owned, overview.indexed),
                        style = MaterialTheme.typography.titleMedium.tabular(),
                        color = scheme.onSurface
                    )
                    overview.disk.rootDisplay.ifBlank { null }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
        StatGrid(
            listOfNotNull(
                ToolStat(stringResource(R.string.tools5_consoles), overview.consoles.size.toString(), R.drawable.ic_controller),
                ToolStat(stringResource(R.string.tools5_indexed), overview.indexed.toString(), R.drawable.ic_library),
                ToolStat(
                    stringResource(R.string.tools5_on_disk), formatBytes(overview.onDiskBytes), R.drawable.ic_storage,
                    pluralStringResource(R.plurals.storage_games, overview.onDisk, overview.onDisk)
                ),
                overview.disk.freeBytes?.let { ToolStat(stringResource(R.string.tools5_free), formatBytes(it), R.drawable.ic_check_circle) }
            )
        )
    }
}

@Composable
private fun ConsoleRow(console: ConsoleOverview, onRescan: () -> Unit) {
    val scanned = console.scannedAt?.let {
        stringResource(R.string.overview_scanned, DateUtils.getRelativeTimeSpanString(it).toString())
    } ?: stringResource(R.string.overview_never_scanned)
    val path = console.path?.displayPath?.ifBlank { null }
    val (labelRes, tone, icon) = when (console.status) {
        ConsoleOverview.Status.OK -> Triple(R.string.overview_status_ok, PillTone.Success, R.drawable.ic_check_circle)
        ConsoleOverview.Status.NO_SOURCES -> Triple(R.string.overview_status_no_sources, PillTone.Warning, R.drawable.ic_warning)
        ConsoleOverview.Status.NOTHING_FOUND -> Triple(R.string.overview_status_nothing_found, PillTone.Warning, R.drawable.ic_warning)
        ConsoleOverview.Status.NOT_SCANNED -> Triple(R.string.overview_status_not_scanned, PillTone.Info, R.drawable.ic_hourglass)
    }
    val label = stringResource(labelRes)
    ToolRow(
        title = console.name,
        lines = listOf(
            stringResource(R.string.overview_console_stats, console.indexed, console.owned, console.onDisk, formatBytes(console.onDiskBytes)),
            listOfNotNull(path, scanned).joinToString(" · ")
        ),
        onClick = onRescan,
        badge = { Badge(label, warning = false, tone = tone, icon = icon) },
        leading = { ConsoleTile(console.id) },
        below = if (console.indexed > 0) ({
            MeterBar(
                console.owned.toFloat() / console.indexed,
                modifier = Modifier.padding(top = 6.dp),
                height = 6.dp,
                color = consoleColor(console.id)
            )
        }) else null
    ) {
        ToolAction(stringResource(R.string.pad_rescan), onClick = onRescan)
    }
}
