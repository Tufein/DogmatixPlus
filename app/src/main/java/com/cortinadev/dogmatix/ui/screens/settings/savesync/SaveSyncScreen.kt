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
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.core.content.ContextCompat
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.screens.tools.ToolRow
import com.cortinadev.dogmatix.util.BackgroundSyncPolicy
import com.cortinadev.dogmatix.util.NewerSide
import com.cortinadev.dogmatix.util.SaveConflictInfo
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

    // Android 13+ asks before an app may show notifications; the background sync needs them for conflicts.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun setBackground(on: Boolean) {
        viewModel.setBackground(context, on)
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun adjustInterval(delta: Int) = viewModel.setInterval(context, BackgroundSyncPolicy.cycle(ui.intervalHours, delta))

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
            SettingRow(
                title = stringResource(R.string.save_sync_deletions),
                hint = stringResource(R.string.save_sync_deletions_hint),
                onClick = { viewModel.setDeletions(context, !ui.deletions) },
                onAdjust = { viewModel.setDeletions(context, it > 0) }
            ) { ThemedSwitch(ui.deletions) { viewModel.setDeletions(context, it) } }
        }
        add {
            SettingRow(
                title = stringResource(R.string.save_sync_bg),
                hint = stringResource(R.string.save_sync_bg_hint),
                onClick = { setBackground(!ui.background) },
                onAdjust = { setBackground(it > 0) }
            ) { ThemedSwitch(ui.background) { setBackground(it) } }
        }
        if (ui.background) {
            add {
                SettingRow(
                    title = stringResource(R.string.save_sync_bg_interval),
                    hint = null,
                    onClick = { adjustInterval(1) },
                    onAdjust = ::adjustInterval
                ) {
                    Stepper(stringResource(R.string.save_sync_hours_short, ui.intervalHours), onDecrement = { adjustInterval(-1) }, onIncrement = { adjustInterval(1) }, valueWidth = 64.dp)
                }
            }
            add {
                SettingRow(
                    title = stringResource(R.string.save_sync_bg_wifi), hint = null,
                    onClick = { viewModel.setWifiOnly(context, !ui.wifiOnly) }, onAdjust = { viewModel.setWifiOnly(context, it > 0) }
                ) { ThemedSwitch(ui.wifiOnly) { viewModel.setWifiOnly(context, it) } }
            }
            add {
                SettingRow(
                    title = stringResource(R.string.save_sync_bg_charging), hint = null,
                    onClick = { viewModel.setCharging(context, !ui.charging) }, onAdjust = { viewModel.setCharging(context, it > 0) }
                ) { ThemedSwitch(ui.charging) { viewModel.setCharging(context, it) } }
            }
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
        sync.last?.takeIf { it.deletionsHeld > 0 }?.let { held ->
            add {
                SettingRow(
                    title = androidx.compose.ui.res.pluralStringResource(R.plurals.save_sync_held_title, held.deletionsHeld, held.deletionsHeld),
                    hint = stringResource(R.string.save_sync_held_hint),
                    onClick = viewModel::applyHeldDeletions
                ) { PillButton(stringResource(R.string.save_sync_held_apply), viewModel::applyHeldDeletions) }
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
    val info = SaveConflictInfo.of(conflict)
    val device = stringResource(R.string.save_sync_side, formatBytes(info.deviceSize), relative(info.deviceModified) ?: notSet)
    val server = stringResource(R.string.save_sync_side, formatBytes(info.serverSize), relative(info.serverModified) ?: notSet)
    val kind = stringResource(if (conflict.local.kind == SaveKind.STATE) R.string.save_sync_kind_state else R.string.save_sync_kind_save)
    val newer = when (info.newer) {
        NewerSide.DEVICE -> stringResource(R.string.save_sync_newer_device)
        NewerSide.SERVER -> stringResource(R.string.save_sync_newer_server)
        NewerSide.SAME -> stringResource(R.string.save_sync_newer_same)
        NewerSide.UNKNOWN -> null
    }
    val size = when {
        info.sameSize -> stringResource(R.string.save_sync_size_same)
        info.sizeDelta < 0 -> stringResource(R.string.save_sync_size_smaller, formatBytes(-info.sizeDelta))
        else -> stringResource(R.string.save_sync_size_bigger, formatBytes(info.sizeDelta))
    }
    ToolRow(
        title = "$kind · ${conflict.local.path}",
        lines = listOf(stringResource(R.string.save_sync_conflict_hint, device, server), listOfNotNull(newer, size).joinToString(" · ")),
        onClick = {},
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { viewModel.resolve(conflict, keepDevice = true); true }
                Key.DirectionRight -> { viewModel.resolve(conflict, keepDevice = false); true }
                else -> false
            }
        }
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
        if (result.deletedOnDevice + result.deletedOnServer > 0) add(stringResource(R.string.save_sync_summary_deleted, result.deletedOnDevice, result.deletedOnServer))
        relative(result.finishedAt)?.let { add(it) }
    }
    return parts.joinToString(" · ")
}

internal fun relative(millis: Long?): String? =
    millis?.takeIf { it > 0 }?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() }

/** `content://…/tree/primary%3ARetroArch%2Fsaves` → `RetroArch/saves`; null when nothing is picked. */
private fun folderLabel(uri: String): String? {
    if (uri.isBlank()) return null
    return runCatching { DocumentsContract.getTreeDocumentId(uri.toUri()).substringAfter(':').ifBlank { "/" } }.getOrDefault(uri)
}
