package com.cortinadev.dogmatix.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.MetaCounts
import com.cortinadev.dogmatix.data.service.MetaRunState
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.EmptyState
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.MeterBar
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.screens.settings.ThemedSwitch
import com.cortinadev.dogmatix.ui.theme.tabular
import java.text.NumberFormat

/** What the focused row does, for the gamepad legend. */
private enum class Focused { TOGGLE, WRITE }

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/**
 * Descriptions for your launcher (7.0): writes name, description, genre, release year, developer and
 * rating into ES-DE's `gamelist.xml` and Pegasus' `metadata.txt`, merged into what is already there
 * (only empty fields are filled). Explains what it does and that it changes the launcher's own files
 * (with the backup it keeps), switches ES-DE / Pegasus / "after every download" on or off, and runs for
 * one console or for all of them with the counts of what was written.
 *
 * @param onOpenSettings opens Settings (to set the ES-DE folder); the same route [FrontendCheckScreen] uses
 *   (`NavRoutes.Settings.route`).
 */
@Composable
fun FrontendMetadataScreen(
    onOpenSettings: () -> Unit = {},
    viewModel: FrontendMetadataViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsState()
    val run = ui.run
    val targets = ui.targets
    val canRun = !run.running && targets.any
    val firstFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf<Focused?>(null) }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }

    PublishLegend(
        when (focused) {
            Focused.TOGGLE -> listOf(
                LegendEntry("A", stringResource(R.string.pad_change)),
                LegendEntry("B", stringResource(R.string.pad_back)),
                LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
            )
            Focused.WRITE -> listOf(
                LegendEntry("A", stringResource(R.string.meta7_pad_write)),
                LegendEntry("B", stringResource(R.string.pad_back)),
                LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
            )
            null -> null
        }
    )

    fun track(kind: Focused) = Modifier.onFocusChanged {
        if (it.isFocused) focused = kind else if (focused == kind) focused = null
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.meta7_title), icon = R.drawable.ic_description, subtitle = stringResource(R.string.meta7_subtitle))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // What it does, and the warning: focusable cards so the D-pad can read down the page.
            item(key = "what") {
                InfoCard(
                    listOf(stringResource(R.string.meta7_what_title), stringResource(R.string.meta7_what_body)),
                    modifier = Modifier.focusRequester(firstFocus).focusableCard(),
                    icon = R.drawable.ic_info
                )
            }
            item(key = "warn") {
                InfoCard(
                    listOf(stringResource(R.string.meta7_warn_title), stringResource(R.string.meta7_warn_body)),
                    modifier = Modifier.focusableCard(),
                    icon = R.drawable.ic_warning,
                    danger = true
                )
            }

            item(key = "h-where") { SectionHeader(stringResource(R.string.meta7_section_where), icon = R.drawable.ic_frontends) }
            item(key = "esde") {
                val folderMissing = targets.esdeOn && !targets.esdeWritable
                ToolRow(
                    title = stringResource(R.string.meta7_esde_title),
                    lines = listOf(
                        stringResource(
                            when {
                                !targets.esdeFolderSet -> R.string.meta7_esde_no_folder
                                folderMissing -> R.string.meta7_esde_not_writable
                                else -> R.string.meta7_esde_hint
                            }
                        )
                    ),
                    onClick = { viewModel.setEsde(!targets.esdeOn) },
                    modifier = track(Focused.TOGGLE),
                    badge = if (folderMissing) ({ Pill(stringResource(R.string.meta7_needs_folder), tone = PillTone.Warning, icon = R.drawable.ic_warning) }) else null,
                    icon = R.drawable.ic_frontends,
                    trailing = {
                        if (folderMissing) ActionPill(stringResource(R.string.meta7_settings), onOpenSettings)
                        ThemedSwitch(targets.esdeOn) { viewModel.setEsde(it) }
                    }
                )
            }
            item(key = "pegasus") {
                ToolRow(
                    title = stringResource(R.string.meta7_pegasus_title),
                    lines = listOf(stringResource(R.string.meta7_pegasus_hint)),
                    onClick = { viewModel.setPegasus(!targets.pegasusOn) },
                    modifier = track(Focused.TOGGLE),
                    icon = R.drawable.ic_gamepad,
                    trailing = { ThemedSwitch(targets.pegasusOn) { viewModel.setPegasus(it) } }
                )
            }
            item(key = "auto") {
                ToolRow(
                    title = stringResource(R.string.meta7_auto_title),
                    lines = listOf(stringResource(R.string.meta7_auto_hint)),
                    onClick = { viewModel.setAuto(!ui.auto) },
                    modifier = track(Focused.TOGGLE),
                    icon = R.drawable.ic_download,
                    trailing = { ThemedSwitch(ui.auto) { viewModel.setAuto(it) } }
                )
            }

            item(key = "h-run") { SectionHeader(stringResource(R.string.meta7_section_run), icon = R.drawable.ic_play_arrow) }
            item(key = "run") {
                if (run.running) RunningCard(run, ui.consoles.firstOrNull { it.id == run.consoleId }?.name, viewModel::stop)
                else ToolsActions {
                    ActionPill(
                        stringResource(R.string.meta7_run_all),
                        onClick = viewModel::writeAll,
                        modifier = track(Focused.WRITE),
                        icon = R.drawable.ic_play_arrow,
                        tone = ActionTone.Accent,
                        enabled = canRun
                    )
                }
            }
            if (!run.running && (run.finished || run.problem != null || run.results.isNotEmpty())) {
                item(key = "result") { ResultCard(run) }
            }

            item(key = "h-consoles") { SectionHeader(stringResource(R.string.meta7_section_consoles), icon = R.drawable.ic_library) }
            when {
                ui.loading -> item(key = "loading") {
                    Text(
                        stringResource(R.string.meta7_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
                ui.consoles.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        title = stringResource(R.string.meta7_empty_title),
                        message = stringResource(R.string.meta7_empty_message),
                        illustration = R.drawable.milou,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> items(ui.consoles, key = { "c" + it.id }) { row ->
                    val counts = run.results[row.id]
                    val working = run.running && run.consoleId == row.id
                    ToolRow(
                        title = row.name,
                        lines = buildList {
                            add(stringResource(R.string.meta7_console_games, count(row.games)))
                            if (counts != null && !working) add(
                                stringResource(R.string.meta7_console_result, count(counts.updated), count(counts.complete), count(counts.noData))
                            )
                        },
                        onClick = { if (canRun) viewModel.writeConsole(row.id) },
                        modifier = track(Focused.WRITE),
                        badge = if (working) ({ Pill(stringResource(R.string.meta7_working_pill), tone = PillTone.Accent, icon = R.drawable.ic_sync) }) else null,
                        leading = { ConsoleTile(row.id) },
                        trailing = {
                            ActionPill(
                                stringResource(R.string.meta7_run_console),
                                onClick = { viewModel.writeConsole(row.id) },
                                icon = R.drawable.ic_play_arrow,
                                enabled = canRun
                            )
                        }
                    )
                }
            }
        }
    }
}

/** Progress of a run: the console, how far, and a way to stop (what was written stays). */
@Composable
private fun RunningCard(run: MetaRunState, consoleName: String?, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Panel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (run.total == 0) stringResource(R.string.meta7_preparing)
                    else stringResource(R.string.meta7_running, consoleName ?: "…", count(run.done), count(run.total)),
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = scheme.onSurface
                )
                MeterBar(if (run.total == 0) 0f else run.done.toFloat() / run.total)
            }
            ActionPill(stringResource(R.string.meta7_stop), onStop, icon = R.drawable.ic_close, tone = ActionTone.Danger)
        }
    }
}

/** The outcome of the last run: what was written where, and everything that was left alone and why. */
@Composable
private fun ResultCard(run: MetaRunState) {
    val problem = run.problem
    if (problem != null) {
        InfoCard(
            listOf(
                stringResource(
                    when (problem) {
                        MetaRunState.Problem.NO_TARGET -> R.string.meta7_problem_no_target
                        MetaRunState.Problem.NOTHING_ON_DEVICE -> R.string.meta7_problem_nothing
                        MetaRunState.Problem.FAILED -> R.string.meta7_problem_failed
                    }
                )
            ),
            modifier = Modifier.focusableCard(),
            icon = R.drawable.ic_warning,
            danger = problem == MetaRunState.Problem.FAILED
        )
        return
    }
    val sum: MetaCounts = run.sum
    val lines = buildList {
        add(stringResource(if (run.finished) R.string.meta7_result_title else R.string.meta7_result_stopped))
        add(stringResource(R.string.meta7_result_games, count(sum.games)))
        if (sum.esde.files > 0 || sum.esde.written > 0) add(stringResource(R.string.meta7_result_esde, count(sum.esde.added), count(sum.esde.filled), count(sum.esde.files)))
        if (sum.pegasus.files > 0 || sum.pegasus.written > 0) add(stringResource(R.string.meta7_result_pegasus, count(sum.pegasus.added), count(sum.pegasus.filled), count(sum.pegasus.files)))
        if (sum.filesWritten == 0) add(stringResource(R.string.meta7_result_none))
        if (sum.complete > 0) add(stringResource(R.string.meta7_result_complete, count(sum.complete)))
        if (sum.noData > 0) add(stringResource(R.string.meta7_result_nodata, count(sum.noData)))
        if (sum.deferred > 0) add(stringResource(R.string.meta7_result_deferred, count(sum.deferred)))
        val unreadable = sum.esde.unreadable + sum.pegasus.unreadable
        if (unreadable > 0) add(stringResource(R.string.meta7_result_unreadable, count(unreadable)))
        val failed = sum.esde.failed + sum.pegasus.failed
        if (failed > 0) add(stringResource(R.string.meta7_result_failed, count(failed)))
        if (sum.outside > 0) add(stringResource(R.string.meta7_result_outside, count(sum.outside)))
    }
    InfoCard(
        lines,
        modifier = Modifier.focusableCard(),
        accent = run.finished && sum.filesWritten > 0,
        icon = if (run.finished) R.drawable.ic_check_circle else R.drawable.ic_info,
        danger = sum.esde.failed + sum.pegasus.failed > 0
    )
}
