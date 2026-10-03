package com.cortinadev.dogmatix.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
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
            message = stringResource(
                R.string.duplicates_delete_message,
                entry.baseName,
                entry.folder,
                pluralStringResource(R.plurals.tools_files, entry.files.size, entry.files.size),
                formatBytes(entry.size)
            ),
            confirmText = stringResource(R.string.duplicates_delete),
            onConfirm = { viewModel.delete(context, entry) },
            onDismiss = { pendingDelete = null }
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
        else -> stringResource(R.string.duplicates_summary, ui.groups.size, formatBytes(ui.reclaimable), ui.filesChecked)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_duplicates))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "summary") {
                ToolRow(
                    title = summary,
                    lines = emptyList(),
                    onClick = viewModel::rescan,
                    modifier = Modifier.focusRequester(firstFocus)
                ) {
                    PillButton(stringResource(R.string.tools_refresh), viewModel::rescan)
                }
            }
            if (!ui.scanning) {
                ui.groups.forEachIndexed { index, group ->
                    item(key = "group:$index:${group.scope}|${group.title}") { GroupHeader(group) }
                    items(group.entries, key = { it.files.first().uri }) { entry ->
                        val largest = entry === group.entries.first()
                        val details = formatBytes(entry.size) + " · " +
                            pluralStringResource(R.plurals.tools_files, entry.files.size, entry.files.size) +
                            if (largest) " · " + stringResource(R.string.duplicates_largest) else ""
                        ToolRow(
                            title = entry.baseName,
                            lines = listOf(entry.folder, details),
                            onClick = { pendingDelete = entry }
                        ) {
                            PillButton(stringResource(R.string.duplicates_delete)) { pendingDelete = entry }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: DuplicateGroup) {
    val where = group.consoleId?.let { ConsoleFormatter.getConsoleDisplayName(it) }
        ?: if (group.scope.isEmpty()) stringResource(R.string.tools_root_folder)
        else stringResource(R.string.tools_unknown_folder, group.entries.first().folder.substringAfterLast('/'))
    val kind = stringResource(
        if (group.kind == DuplicateGroup.Kind.IDENTICAL) R.string.duplicates_kind_identical else R.string.duplicates_kind_variant
    )
    SectionHeader(title = "${group.title} · $where", subtitle = "$kind · ${formatBytes(group.reclaimable)}")
}
