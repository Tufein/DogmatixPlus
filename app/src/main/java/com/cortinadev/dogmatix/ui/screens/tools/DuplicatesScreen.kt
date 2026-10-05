package com.cortinadev.dogmatix.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.GameCover
import com.cortinadev.dogmatix.ui.components.IconTile
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.screens.sources.components.ConfirmDialog
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DuplicateGroup
import com.cortinadev.dogmatix.util.GameEntry

/**
 * Games that are on disk more than once, grouped per console. Each copy is a row; A asks to
 * delete it. The largest copy leads every group, so it is the one most likely worth keeping.
 */
@Composable
fun DuplicatesScreen(viewModel: DuplicatesViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var pendingDelete by remember { mutableStateOf<GameEntry?>(null) }
    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.duplicates_delete_title),
            message = pluralStringResource(
                R.plurals.duplicates_delete_message,
                entry.files.size,
                entry.baseName,
                entry.folder,
                pluralStringResource(R.plurals.tools_files, entry.files.size, entry.files.size),
                formatBytes(entry.size)
            ) + deletedFileList(entry),
            confirmText = stringResource(R.string.duplicates_delete),
            onConfirm = { viewModel.delete(context, entry) },
            onDismiss = { pendingDelete = null }
        )
    }

    var confirmSuggested by remember { mutableStateOf(false) }
    if (confirmSuggested && ui.suggestions.isNotEmpty()) {
        val removing = ui.suggestions.flatMap { it.remove }
        val shown = removing.take(12).joinToString("\n") { "• " + it.baseName + "  (" + formatBytes(it.size) + ")" }
        ConfirmDialog(
            title = stringResource(R.string.duplicates_suggest_confirm_title),
            message = pluralStringResource(R.plurals.duplicates_suggest_confirm, removing.size, removing.size, formatBytes(ui.suggestedBytes)) +
                "\n\n" + shown + if (removing.size > 12) "\n• … (+${removing.size - 12})" else "",
            confirmText = stringResource(R.string.duplicates_delete),
            onConfirm = { viewModel.removeSuggested(context) },
            onDismiss = { confirmSuggested = false }
        )
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    val summary = when {
        ui.scanning -> stringResource(R.string.tools_scanning)
        !ui.folderSet -> stringResource(R.string.tools_no_folder)
        ui.groups.isEmpty() -> stringResource(R.string.duplicates_none, ui.filesChecked)
        else -> pluralStringResource(R.plurals.duplicates_summary, ui.groups.size, ui.groups.size, formatBytes(ui.reclaimable), ui.filesChecked)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_duplicates), icon = NavRoutes.Duplicates.icon)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "summary") {
                ToolRow(
                    title = summary,
                    lines = emptyList(),
                    onClick = viewModel::rescan,
                    modifier = Modifier.focusRequester(firstFocus),
                    icon = if (!ui.scanning && ui.groups.isEmpty() && ui.folderSet) R.drawable.ic_check_circle else R.drawable.ic_duplicates
                ) {
                    ToolAction(stringResource(R.string.tools_refresh), icon = R.drawable.ic_retry, onClick = viewModel::rescan)
                }
            }
            if (!ui.scanning && ui.suggestions.isNotEmpty()) {
                item(key = "suggest") {
                    ToolRow(
                        title = pluralStringResource(R.plurals.duplicates_suggest, ui.suggestedCopies, ui.suggestedCopies, formatBytes(ui.suggestedBytes)),
                        lines = listOf(stringResource(R.string.duplicates_suggest_hint)),
                        onClick = { confirmSuggested = true },
                        icon = R.drawable.ic_sparkle
                    ) { ToolAction(stringResource(R.string.duplicates_suggest_action), tone = ActionTone.Accent) { confirmSuggested = true } }
                }
            }
            if (!ui.scanning) {
                ui.groups.forEachIndexed { index, group ->
                    item(key = "group:$index:${group.scope}|${group.title}") { GroupHeader(group) }
                    // Keyed per group as well: a file can never be in two groups, but a duplicate
                    // key would crash the whole list, so the key does not rely on that.
                    items(group.entries, key = { "$index|${it.id}" }) { entry ->
                        val largest = entry === group.entries.first()
                        val details = formatBytes(entry.size) + " · " +
                            pluralStringResource(R.plurals.tools_files, entry.files.size, entry.files.size)
                        val keeper = ui.suggestions.any { it.group.entries.firstOrNull { e -> e.id == it.keep.id } != null && it.keep.id == entry.id }
                        ToolRow(
                            title = entry.baseName,
                            lines = listOf(entry.folder, details),
                            onClick = { pendingDelete = entry },
                            badge = if (keeper || largest) ({
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (largest) Pill(stringResource(R.string.duplicates_largest), tone = PillTone.Info)
                                    if (keeper) Pill(stringResource(R.string.duplicates_keep), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                                }
                            }) else null,
                            icon = R.drawable.ic_duplicates
                        ) {
                            ToolAction(stringResource(R.string.duplicates_delete), icon = R.drawable.ic_trash, tone = ActionTone.Danger) { pendingDelete = entry }
                        }
                    }
                }
            }
        }
    }
}

/** Names of the files a multi-file delete removes, so it is never a surprise. */
private fun deletedFileList(entry: GameEntry): String {
    val shown = entry.files.take(MAX_LISTED_FILES).joinToString("\n") { "• " + it.name }
    val more = entry.files.size - MAX_LISTED_FILES
    return "\n\n" + shown + if (more > 0) "\n• … (+$more)" else ""
}

private const val MAX_LISTED_FILES = 8

/** One group of copies: the game's cover (or its console), its title, and what the group costs. */
@Composable
private fun GroupHeader(group: DuplicateGroup) {
    val where = group.consoleId?.let { ConsoleFormatter.getConsoleDisplayName(it) }
        ?: if (group.scope.isEmpty()) stringResource(R.string.tools_root_folder)
        else stringResource(R.string.tools_unknown_folder, group.scope.removePrefix("folder:"))
    val kind = stringResource(
        if (group.kind == DuplicateGroup.Kind.IDENTICAL) R.string.duplicates_kind_identical else R.string.duplicates_kind_variant
    )
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val console = group.consoleId
        val first = group.entries.firstOrNull()
        if (console != null && first != null) GameCover(console, first.coverFileName(), group.title, Modifier.size(width = 36.dp, height = 48.dp), showLabel = false)
        else IconTile(R.drawable.ic_folder, size = 36.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(group.title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "$where · $kind",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Pill(formatBytes(group.reclaimable), tone = PillTone.Warning)
    }
}
