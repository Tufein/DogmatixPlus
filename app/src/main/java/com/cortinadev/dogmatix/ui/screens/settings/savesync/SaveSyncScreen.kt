package com.cortinadev.dogmatix.ui.screens.settings.savesync

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.util.SaveConflict
import com.cortinadev.dogmatix.util.SaveSyncResult
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.cortinadev.dogmatix.ui.screens.settings.SettingRow
import com.cortinadev.dogmatix.ui.screens.settings.ThemedSwitch
import com.cortinadev.dogmatix.util.SaveKind
import com.cortinadev.dogmatix.util.SaveSyncPlanner

/**
 * Settings → Save sync: which emulator folders hold the saves and the save states, "Sync now",
 * automatic sync, the result of the last sync and the files changed on both sides, where the
 * user keeps one copy (◀ the device's, ▶ the server's).
 */
@Composable
fun SaveSyncScreen(viewModel: SaveSyncViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsState()
    val sync by viewModel.syncState.collectAsState()
    val context = LocalContext.current

    fun persist(uri: Uri) = context.contentResolver.takePersistableUriPermission(
        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
    val savesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { persist(it); viewModel.setSavesDir(context, it.toString()) }
    }
    val statesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { persist(it); viewModel.setStatesDir(context, it.toString()) }
    }

    val notSet = stringResource(R.string.settings_not_set)
    val ready = ui.rommUrl.isNotBlank() && (ui.savesDir.isNotBlank() || ui.statesDir.isNotBlank())
    val rows: List<@Composable () -> Unit> = buildList {
        add {
            SettingRow(
                title = stringResource(R.string.save_sync_saves_folder),
                hint = folderLabel(ui.savesDir) ?: stringResource(R.string.save_sync_saves_folder_hint),
                onClick = { savesPicker.launch(null) }
            ) { PillButton(stringResource(R.string.settings_change)) { savesPicker.launch(null) } }
        }
        add {
            SettingRow(
                title = stringResource(R.string.save_sync_states_folder),
                hint = folderLabel(ui.statesDir) ?: stringResource(R.string.save_sync_states_folder_hint),
                onClick = { statesPicker.launch(null) }
            ) { PillButton(stringResource(R.string.settings_change)) { statesPicker.launch(null) } }
        }
        add {
            SettingRow(
                title = stringResource(R.string.save_sync_auto),
                hint = stringResource(R.string.save_sync_auto_hint),
                onClick = { viewModel.setAuto(context, !ui.auto) },
                onAdjust = { viewModel.setAuto(context, it > 0) }
            ) { ThemedSwitch(ui.auto) { viewModel.setAuto(context, it) } }
        }
        add {
            val hint = when {
                ui.rommUrl.isBlank() -> stringResource(R.string.save_sync_needs_romm)
                !ready -> stringResource(R.string.save_sync_needs_folder)
                sync.running -> stringResource(R.string.save_sync_running, sync.progress ?: "")
                sync.error != null -> stringResource(R.string.save_sync_failed, sync.error ?: "")
                sync.last != null -> summary(sync.last!!)
                else -> stringResource(R.string.save_sync_never)
            }
            SettingRow(
                title = stringResource(R.string.save_sync_now),
                hint = hint,
                onClick = { if (ready) viewModel.syncNow() }
            ) {
                PillButton(stringResource(if (sync.running) R.string.save_sync_busy else R.string.save_sync_action)) {
                    if (ready) viewModel.syncNow()
                }
            }
        }
        sync.last?.errors?.forEach { error ->
            add { SettingRow(title = stringResource(R.string.save_sync_error_title), hint = error, onClick = {}) {} }
        }
        if (sync.conflicts.isNotEmpty()) {
            add {
                Text(
                    stringResource(R.string.save_sync_conflicts_header, sync.conflicts.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            sync.conflicts.forEach { conflict -> add { ConflictRow(conflict, notSet, viewModel) } }
        }
    }

    val rowFocus = remember(rows.size) { List(rows.size) { FocusRequester() } }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(rows.size) { if (focusedIndex < 0 && rows.isNotEmpty()) runCatching { rowFocus[0].requestFocus() } }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        Text(
            stringResource(R.string.settings_save_sync),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Text(
            stringResource(R.string.save_sync_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(rows.size) { index ->
                Box(
                    modifier = Modifier
                        .focusRequester(rowFocus[index])
                        .onFocusChanged { if (it.hasFocus) focusedIndex = index }
                ) { rows[index]() }
            }
        }
    }
}

@Composable
private fun ConflictRow(conflict: SaveConflict, notSet: String, viewModel: SaveSyncViewModel) {
    val device = stringResource(
        R.string.save_sync_side,
        formatBytes(conflict.local.size),
        relative(conflict.local.modified) ?: notSet
    )
    val server = stringResource(
        R.string.save_sync_side,
        formatBytes(conflict.remote.size),
        relative(SaveSyncPlanner.epochMillis(conflict.remote.updatedAt)) ?: notSet
    )
    val kind = stringResource(if (conflict.local.kind == SaveKind.STATE) R.string.save_sync_kind_state else R.string.save_sync_kind_save)
    SettingRow(
        title = "$kind · ${conflict.local.path}",
        hint = stringResource(R.string.save_sync_conflict_hint, device, server),
        onClick = {},
        onAdjust = { viewModel.resolve(conflict, keepDevice = it < 0) }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillButton(stringResource(R.string.save_sync_keep_device)) { viewModel.resolve(conflict, keepDevice = true) }
            PillButton(stringResource(R.string.save_sync_keep_server)) { viewModel.resolve(conflict, keepDevice = false) }
        }
    }
}

@Composable
private fun summary(result: SaveSyncResult): String {
    val parts = buildList {
        add(stringResource(R.string.save_sync_summary, result.uploaded, result.downloaded, result.unchanged))
        if (result.conflicts > 0) add(stringResource(R.string.save_sync_summary_conflicts, result.conflicts))
        if (result.notMatched > 0) add(stringResource(R.string.save_sync_summary_not_matched, result.notMatched))
        if (result.failed > 0) add(stringResource(R.string.save_sync_summary_failed, result.failed))
        relative(result.finishedAt)?.let { add(it) }
    }
    return parts.joinToString(" · ")
}

private fun relative(millis: Long?): String? =
    millis?.takeIf { it > 0 }?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() }

/** `content://…/tree/primary%3ARetroArch%2Fsaves` → `RetroArch/saves`; null when nothing is picked. */
private fun folderLabel(uri: String): String? {
    if (uri.isBlank()) return null
    return runCatching { DocumentsContract.getTreeDocumentId(uri.toUri()).substringAfter(':').ifBlank { "/" } }.getOrDefault(uri)
}
