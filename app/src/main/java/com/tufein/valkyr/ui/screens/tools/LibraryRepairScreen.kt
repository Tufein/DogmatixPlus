package com.tufein.valkyr.ui.screens.tools

import com.tufein.valkyr.ui.components.requestFocusWhenReady
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.RepairOutcome
import com.tufein.valkyr.data.service.RepairProgress
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.LibraryRepair
import com.tufein.valkyr.util.RepairFinding
import com.tufein.valkyr.util.RepairFix
import com.tufein.valkyr.util.RepairFixKind
import com.tufein.valkyr.util.RepairKind
import com.tufein.valkyr.util.RepairReport
import com.tufein.valkyr.util.RepairSeverity
import com.tufein.valkyr.util.ToastUtil

/**
 * Tools -> Library repair (3.0.0): one guided screen that checks the game folders for what stops
 * games from working (empty or cut-off files, disc sets with missing tracks or discs), what only
 * takes space (leftovers of interrupted downloads, duplicate playlists) and what confuses the
 * library (stale "on this device" marks, folders it does not know). Each finding opens a preview of
 * its fix; a fix that removes, replaces or downloads something lists exactly what and asks first.
 *
 * @param onNavigate opens another screen by route (Trash and recovery, Settings).
 */
@Composable
fun LibraryRepairScreen(onNavigate: (String) -> Unit, viewModel: LibraryRepairViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val report = ui.report
    var details by remember { mutableStateOf<RepairFinding?>(null) }
    var confirm by remember { mutableStateOf<RepairConfirm?>(null) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    val mainFocus = remember { FocusRequester() }

    val doneTemplate = stringResource(R.string.repair30_done)
    val staleMessage = stringResource(R.string.repair30_stale)
    val failedTemplate = stringResource(R.string.repair30_fix_failed)
    val shareTitle = stringResource(R.string.repair30_share)
    val stateKey = when {
        ui.running -> "running"
        report != null -> "report"
        else -> "idle"
    }
    // Something focusable is always focused: Start, Stop, or Check again.
    LaunchedEffect(stateKey) { mainFocus.requestFocusWhenReady() }
    // A finding that went (fixed, profile switched) closes its dialogs.
    LaunchedEffect(report) {
        val ids = report?.findings?.map { it.id }?.toSet().orEmpty()
        if (details?.id !in ids) details = null
        if ((confirm as? RepairConfirm.One)?.finding?.id?.let { it !in ids } == true) confirm = null
        if (report == null) confirm = null
    }

    val focused = focusedId?.let { id -> report?.findings?.firstOrNull { it.id == id } }
    PublishLegend(
        listOfNotNull(
            focused?.let { LegendEntry("A", stringResource(R.string.pad_open)) },
            LegendEntry("B", stringResource(R.string.pad_back))
        )
    )

    fun run(finding: RepairFinding, fix: RepairFix) {
        viewModel.fix(finding, fix) { outcome ->
            when (outcome) {
                RepairOutcome.DONE -> ToastUtil.showSuccess(context, String.format(doneTemplate, finding.subject.ifBlank { finding.files.firstOrNull()?.name.orEmpty() }))
                RepairOutcome.STALE -> ToastUtil.showInfo(context, staleMessage)
                RepairOutcome.FAILED -> ToastUtil.showError(context, String.format(failedTemplate, finding.subject.ifBlank { finding.files.firstOrNull()?.name.orEmpty() }))
            }
        }
    }

    details?.let { finding ->
        FindingDialog(
            finding = finding,
            busy = ui.fixing != null,
            onFix = { fix ->
                details = null
                // A safe fix only adds a playlist or refreshes the marks: the dialog was its preview.
                if (fix.safe) run(finding, fix) else confirm = RepairConfirm.One(finding, fix)
            },
            onDismiss = { details = null }
        )
    }
    confirm?.let { pending ->
        ConfirmDialog(pending, onConfirm = {
            confirm = null
            when (pending) {
                is RepairConfirm.One -> run(pending.finding, pending.fix)
                is RepairConfirm.SafeAll -> viewModel.fixSafe(pending.items) { fixed, failed ->
                    if (failed > 0) ToastUtil.showError(context, context.resources.getQuantityString(R.plurals.repair30_fixed_some, fixed, fixed, failed))
                    else ToastUtil.showSuccess(context, context.resources.getQuantityString(R.plurals.repair30_fixed, fixed, fixed))
                }
            }
        }, onDismiss = { confirm = null })
    }

    val severityLabels = RepairSeverity.entries.associateWith { severityLabel(it) }
    val reportTitle = stringResource(R.string.repair30_report_title)
    val rowTitles = report?.findings?.associate { it.id to findingTitle(it) }.orEmpty()
    val summary = report?.let { summaryLines(it.findings, it.filesChecked) }.orEmpty()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(key = "title") { ToolsTitle(stringResource(R.string.repair30_title), icon = R.drawable.ic_build, subtitle = stringResource(R.string.repair30_subtitle)) }
        when {
            ui.running -> {
                item(key = "progress") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(progressText(ui.progress), style = MaterialTheme.typography.bodyLarge)
                        val p = ui.progress
                        if (p != null && p.total > 0) LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                item(key = "stop") {
                    ToolsActions { ActionPill(stringResource(R.string.dialog_cancel), viewModel::cancel, modifier = Modifier.focusRequester(mainFocus), icon = R.drawable.ic_close) }
                }
            }
            report == null -> {
                item(key = "intro") { InfoCard(listOf(stringResource(R.string.repair30_intro_title), stringResource(R.string.repair30_intro)), icon = R.drawable.ic_build, accent = true) }
                if (ui.failed) item(key = "failed") { InfoCard(listOf(stringResource(R.string.repair30_failed)), icon = R.drawable.ic_error, danger = true) }
                if (ui.cancelled) item(key = "cancelled") { InfoCard(listOf(stringResource(R.string.repair30_cancelled))) }
                item(key = "start") {
                    ToolsActions { ActionPill(stringResource(R.string.repair30_start), viewModel::start, modifier = Modifier.focusRequester(mainFocus), icon = R.drawable.ic_build, tone = ActionTone.Accent) }
                }
            }
            !report.folderSet -> {
                item(key = "nofolder") {
                    EmptyState(
                        title = stringResource(R.string.repair30_no_folder_title),
                        message = stringResource(R.string.repair30_no_folder),
                        icon = R.drawable.ic_folder,
                        actionLabel = stringResource(R.string.nav_settings),
                        onAction = { onNavigate(NavRoutes.Settings.route) },
                        actionFocus = mainFocus,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            else -> {
                val high = report.findings.any { it.severity == RepairSeverity.HIGH }
                item(key = "summary") { InfoCard(summary, icon = if (report.findings.isEmpty()) R.drawable.ic_check_circle else R.drawable.ic_build, danger = high, accent = !high) }
                if (report.incomplete) item(key = "incomplete") { InfoCard(listOf(stringResource(R.string.repair30_incomplete)), icon = R.drawable.ic_warning) }
                if (report.truncated) item(key = "truncated") { InfoCard(listOf(stringResource(R.string.repair30_truncated, LibraryRepair.MAX_FINDINGS))) }
                if (report.restrictions.active) item(key = "profile") { InfoCard(listOf(stringResource(R.string.repair30_profile_note))) }
                item(key = "actions") {
                    ToolsActions {
                        ActionPill(stringResource(R.string.repair30_again), viewModel::start, modifier = Modifier.focusRequester(mainFocus), icon = R.drawable.ic_sync, enabled = ui.fixing == null)
                        if (ui.safe.isNotEmpty()) ActionPill(
                            stringResource(R.string.repair30_fix_all, ui.safe.size), { confirm = RepairConfirm.SafeAll(ui.safe) },
                            icon = R.drawable.ic_check, tone = ActionTone.Accent, enabled = ui.fixing == null
                        )
                        if (report.findings.isNotEmpty()) ActionPill(stringResource(R.string.repair30_share), {
                            val text = RepairReport.text(reportTitle, summary.joinToString(" · "), report.findings.map {
                                RepairReport.Row(severityLabels.getValue(it.severity), rowTitles[it.id].orEmpty(), it.files.map { f -> f.name })
                            })
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), shareTitle))
                        }, icon = R.drawable.ic_share)
                        ActionPill(stringResource(R.string.recovery_title), { onNavigate(NavRoutes.Recovery.route) }, icon = R.drawable.ic_history)
                    }
                }
                RepairSeverity.entries.forEach { severity ->
                    val group = report.findings.filter { it.severity == severity }
                    if (group.isEmpty()) return@forEach
                    item(key = "h:$severity") { SectionHeader(severityLabels.getValue(severity), subtitle = severityHint(severity)) }
                    items(group, key = { it.id }) { finding ->
                        DisposableEffect(finding.id) { onDispose { if (focusedId == finding.id) focusedId = null } }
                        ToolRow(
                            title = rowTitles[finding.id].orEmpty(),
                            lines = rowLines(finding),
                            onClick = { if (ui.fixing == null) details = finding },
                            modifier = Modifier.onFocusChanged { state ->
                                if (state.isFocused) focusedId = finding.id else if (focusedId == finding.id) focusedId = null
                            },
                            icon = kindIcon(finding.kind),
                            badge = { Pill(severityLabels.getValue(severity), tone = severityTone(severity)) },
                            chevron = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FindingDialog(finding: RepairFinding, busy: Boolean, onFix: (RepairFix) -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = rememberInitialFocus()
    AlertDialog(modifier = Modifier.closeOnGamepadB(onDismiss), onDismissRequest = onDismiss,
        title = { Text(findingTitle(finding)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(findingBody(finding))
                if (finding.files.isNotEmpty()) {
                    Text(stringResource(R.string.repair30_files), style = MaterialTheme.typography.titleSmall)
                    FileLines(finding.files.map { "${it.name} · ${formatBytes(it.size)}" })
                }
                if (finding.missing.isNotEmpty()) {
                    Text(stringResource(if (finding.kind == RepairKind.INDEX_STALE) R.string.repair30_examples else R.string.repair30_missing), style = MaterialTheme.typography.titleSmall)
                    FileLines(finding.missing)
                }
                if (finding.fixes.isEmpty()) Text(stringResource(R.string.repair30_no_fix), style = MaterialTheme.typography.bodySmall)
                finding.fixes.forEach { fix ->
                    Text(fixPreview(fix), style = MaterialTheme.typography.bodySmall)
                    fix.playlist?.let { plan -> FileLines(plan.discs) }
                    ActionPill(fixLabel(fix.kind), { onFix(fix) }, icon = fixIcon(fix.kind), tone = if (fix.safe) ActionTone.Accent else ActionTone.Neutral, enabled = !busy)
                }
            }
        },
        confirmButton = {},
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onDismiss, initialFocus = cancelFocus) })
}

@Composable
private fun ConfirmDialog(pending: RepairConfirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = rememberInitialFocus()
    AlertDialog(modifier = Modifier.closeOnGamepadB(onDismiss), onDismissRequest = onDismiss,
        title = {
            Text(when (pending) {
                is RepairConfirm.One -> findingTitle(pending.finding)
                is RepairConfirm.SafeAll -> stringResource(R.string.repair30_fix_all_title)
            })
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (pending) {
                    is RepairConfirm.One -> {
                        val fix = pending.fix
                        if (fix.trash.isNotEmpty()) {
                            Text(stringResource(R.string.repair30_confirm_trash))
                            // Exactly what goes, with where it is now (the trash keeps it on the same storage).
                            fix.trash.forEach { file ->
                                Text("${file.name} · ${formatBytes(file.size)}")
                                Text(file.folder, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        fix.playlist?.let { plan ->
                            Text(stringResource(R.string.repair30_preview_write, plan.fileName))
                            FileLines(plan.discs)
                        }
                        fix.download?.let { Text(stringResource(R.string.repair30_preview_download, it.fileName.substringAfterLast('/'))) }
                    }
                    is RepairConfirm.SafeAll -> {
                        Text(pluralStringResource(R.plurals.repair30_fix_all_body, pending.items.size, pending.items.size))
                        FileLines(pending.items.map { (finding, _) -> findingTitle(finding) })
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(
                when (pending) {
                    is RepairConfirm.One -> fixLabel(pending.fix.kind)
                    is RepairConfirm.SafeAll -> stringResource(R.string.repair30_fix_all_go)
                },
                onConfirm
            )
        },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onDismiss, initialFocus = cancelFocus) })
}

/** At most 30 lines, then "and N more". */
@Composable
private fun FileLines(lines: List<String>) {
    lines.take(MAX_LINES).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (lines.size > MAX_LINES) Text(pluralStringResource(R.plurals.repair30_more, lines.size - MAX_LINES, lines.size - MAX_LINES), style = MaterialTheme.typography.bodySmall)
}

private const val MAX_LINES = 30

@Composable
private fun summaryLines(findings: List<RepairFinding>, files: Int): List<String> {
    if (findings.isEmpty()) return listOf(pluralStringResource(R.plurals.repair30_clean, files, files))
    val counts = LibraryRepair.counts(findings)
    return listOfNotNull(
        counts.getValue(RepairSeverity.HIGH).takeIf { it > 0 }?.let { pluralStringResource(R.plurals.repair30_count_high, it, it) },
        counts.getValue(RepairSeverity.MEDIUM).takeIf { it > 0 }?.let { pluralStringResource(R.plurals.repair30_count_medium, it, it) },
        counts.getValue(RepairSeverity.LOW).takeIf { it > 0 }?.let { pluralStringResource(R.plurals.repair30_count_low, it, it) }
    )
}

@Composable
private fun progressText(p: RepairProgress?): String = when (p?.phase) {
    null, RepairProgress.Phase.SCANNING -> stringResource(R.string.repair30_phase_scan)
    RepairProgress.Phase.LEFTOVERS -> stringResource(R.string.repair30_phase_leftovers, p.done)
    RepairProgress.Phase.SHEETS -> stringResource(R.string.repair30_phase_sheets, p.done, p.total)
    RepairProgress.Phase.LIBRARY -> stringResource(R.string.repair30_phase_library)
}

@Composable
private fun severityLabel(s: RepairSeverity): String = stringResource(
    when (s) {
        RepairSeverity.HIGH -> R.string.repair30_sev_high
        RepairSeverity.MEDIUM -> R.string.repair30_sev_medium
        RepairSeverity.LOW -> R.string.repair30_sev_low
    }
)

@Composable
private fun severityHint(s: RepairSeverity): String = stringResource(
    when (s) {
        RepairSeverity.HIGH -> R.string.repair30_sev_high_hint
        RepairSeverity.MEDIUM -> R.string.repair30_sev_medium_hint
        RepairSeverity.LOW -> R.string.repair30_sev_low_hint
    }
)

private fun severityTone(s: RepairSeverity): PillTone = when (s) {
    RepairSeverity.HIGH -> PillTone.Danger
    RepairSeverity.MEDIUM -> PillTone.Warning
    RepairSeverity.LOW -> PillTone.Neutral
}

private fun kindIcon(kind: RepairKind): Int = when (kind) {
    RepairKind.EMPTY_FILE, RepairKind.UNREADABLE_SHEET -> R.drawable.ic_error
    RepairKind.MISSING_TRACKS, RepairKind.MISSING_DISCS, RepairKind.ORPHAN_TRACKS -> R.drawable.ic_warning
    RepairKind.DEAD_PLAYLIST, RepairKind.DUPLICATE_PLAYLIST, RepairKind.NO_PLAYLIST -> R.drawable.ic_playlist_add
    RepairKind.TRUNCATED -> R.drawable.ic_download
    RepairKind.LEFTOVER_PART, RepairKind.FOREIGN_PART -> R.drawable.ic_trash
    RepairKind.WRONG_CONSOLE, RepairKind.UNMAPPED_FOLDER -> R.drawable.ic_folder
    RepairKind.INDEX_STALE -> R.drawable.ic_sync
}

private fun fixIcon(kind: RepairFixKind): Int = when (kind) {
    RepairFixKind.TRASH -> R.drawable.ic_trash
    RepairFixKind.CREATE_PLAYLIST, RepairFixKind.REWRITE_PLAYLIST -> R.drawable.ic_playlist_add
    RepairFixKind.DOWNLOAD_AGAIN -> R.drawable.ic_download
    RepairFixKind.RESCAN -> R.drawable.ic_sync
}

@Composable
private fun fixLabel(kind: RepairFixKind): String = stringResource(
    when (kind) {
        RepairFixKind.TRASH -> R.string.repair30_fix_trash
        RepairFixKind.CREATE_PLAYLIST -> R.string.repair30_fix_create
        RepairFixKind.REWRITE_PLAYLIST -> R.string.repair30_fix_rewrite
        RepairFixKind.DOWNLOAD_AGAIN -> R.string.repair30_fix_download
        RepairFixKind.RESCAN -> R.string.repair30_fix_rescan
    }
)

@Composable
private fun fixPreview(fix: RepairFix): String = when (fix.kind) {
    RepairFixKind.TRASH -> stringResource(R.string.repair30_preview_trash)
    RepairFixKind.CREATE_PLAYLIST -> stringResource(R.string.repair30_preview_write, fix.playlist?.fileName.orEmpty())
    RepairFixKind.REWRITE_PLAYLIST -> stringResource(R.string.repair30_preview_rewrite, fix.playlist?.fileName.orEmpty())
    RepairFixKind.DOWNLOAD_AGAIN -> stringResource(
        if (fix.trash.isEmpty()) R.string.repair30_preview_download else R.string.repair30_preview_replace,
        fix.download?.fileName?.substringAfterLast('/').orEmpty()
    )
    RepairFixKind.RESCAN -> stringResource(R.string.repair30_preview_rescan)
}

@Composable
private fun findingTitle(f: RepairFinding): String = when (f.kind) {
    RepairKind.EMPTY_FILE -> stringResource(R.string.repair30_k_empty, f.subject)
    RepairKind.MISSING_TRACKS -> stringResource(R.string.repair30_k_missing_tracks, f.subject)
    RepairKind.MISSING_DISCS -> stringResource(R.string.repair30_k_missing_discs, f.subject)
    RepairKind.DEAD_PLAYLIST -> stringResource(R.string.repair30_k_dead_playlist, f.subject)
    RepairKind.UNREADABLE_SHEET -> stringResource(R.string.repair30_k_unreadable, f.subject)
    RepairKind.TRUNCATED -> stringResource(R.string.repair30_k_truncated, f.subject)
    RepairKind.ORPHAN_TRACKS -> stringResource(R.string.repair30_k_orphan, f.subject)
    RepairKind.LEFTOVER_PART -> stringResource(R.string.repair30_k_leftover, f.subject)
    RepairKind.FOREIGN_PART -> stringResource(R.string.repair30_k_foreign, f.subject)
    RepairKind.DUPLICATE_PLAYLIST -> stringResource(R.string.repair30_k_dup_playlist, f.subject)
    RepairKind.NO_PLAYLIST -> stringResource(R.string.repair30_k_no_playlist, f.subject)
    RepairKind.WRONG_CONSOLE -> stringResource(R.string.repair30_k_wrong_console, f.subject)
    RepairKind.UNMAPPED_FOLDER -> stringResource(R.string.repair30_k_unmapped, f.subject)
    RepairKind.INDEX_STALE -> stringResource(R.string.repair30_k_index)
}

@Composable
private fun findingBody(f: RepairFinding): String = when (f.kind) {
    RepairKind.EMPTY_FILE -> stringResource(R.string.repair30_b_empty)
    RepairKind.MISSING_TRACKS -> stringResource(R.string.repair30_b_missing_tracks)
    RepairKind.MISSING_DISCS -> stringResource(R.string.repair30_b_missing_discs)
    RepairKind.DEAD_PLAYLIST -> stringResource(R.string.repair30_b_dead_playlist)
    RepairKind.UNREADABLE_SHEET -> stringResource(R.string.repair30_b_unreadable)
    RepairKind.TRUNCATED -> stringResource(R.string.repair30_b_truncated, formatBytes(f.a), formatBytes(f.b))
    RepairKind.ORPHAN_TRACKS -> stringResource(R.string.repair30_b_orphan)
    RepairKind.LEFTOVER_PART -> stringResource(R.string.repair30_b_leftover)
    RepairKind.FOREIGN_PART -> stringResource(R.string.repair30_b_foreign)
    RepairKind.DUPLICATE_PLAYLIST -> stringResource(R.string.repair30_b_dup_playlist)
    RepairKind.NO_PLAYLIST -> stringResource(R.string.repair30_b_no_playlist)
    RepairKind.WRONG_CONSOLE -> stringResource(
        R.string.repair30_b_wrong_console,
        ConsoleFormatter.getConsoleShortName(f.text),
        f.consoleId?.let { ConsoleFormatter.getConsoleShortName(it) }.orEmpty()
    )
    RepairKind.UNMAPPED_FOLDER -> pluralStringResource(R.plurals.repair30_b_unmapped, f.a.toInt(), f.a.toInt())
    RepairKind.INDEX_STALE -> stringResource(R.string.repair30_b_index, f.a.toInt(), f.b.toInt())
}

/** The lines under a row title: the files (a few names), the console. */
@Composable
private fun rowLines(f: RepairFinding): List<String> {
    val names = when (f.kind) {
        RepairKind.LEFTOVER_PART -> listOf(formatBytes(f.a))
        RepairKind.INDEX_STALE -> emptyList()
        else -> f.files.map { it.name }.filter { it != f.subject }
    }
    return listOfNotNull(
        names.takeIf { it.isNotEmpty() }?.let { list ->
            list.take(3).joinToString(", ") + if (list.size > 3) " " + pluralStringResource(R.plurals.repair30_more, list.size - 3, list.size - 3) else ""
        },
        f.missing.takeIf { it.isNotEmpty() && f.kind != RepairKind.INDEX_STALE }?.let { stringResource(R.string.repair30_missing_line, it.take(3).joinToString(", ")) },
        f.consoleId?.let { ConsoleFormatter.getConsoleDisplayName(it) }
    )
}
