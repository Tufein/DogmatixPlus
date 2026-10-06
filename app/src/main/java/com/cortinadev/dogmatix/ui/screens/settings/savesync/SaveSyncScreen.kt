package com.cortinadev.dogmatix.ui.screens.settings.savesync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.SaveSyncState
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.MeterBar
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.PanelTone
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.ScreenTitle
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.components.TruncatedText
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.cloud.saves.StateShotPair
import com.cortinadev.dogmatix.ui.screens.settings.CardCell
import com.cortinadev.dogmatix.ui.screens.settings.SettingRow
import com.cortinadev.dogmatix.ui.screens.settings.SettingsCardHeader
import com.cortinadev.dogmatix.ui.screens.settings.SettingsIconTile
import com.cortinadev.dogmatix.ui.screens.settings.SettingsTileGap
import com.cortinadev.dogmatix.ui.screens.settings.settingsInset
import com.cortinadev.dogmatix.ui.screens.settings.ThemedSwitch
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion
import com.cortinadev.dogmatix.ui.theme.tabular
import com.cortinadev.dogmatix.util.BackgroundSyncPolicy
import com.cortinadev.dogmatix.util.CardGrid
import com.cortinadev.dogmatix.util.EmulatorSaveFolders
import com.cortinadev.dogmatix.util.NewerSide
import com.cortinadev.dogmatix.util.ProgressText
import com.cortinadev.dogmatix.util.SaveConflict
import com.cortinadev.dogmatix.util.SaveConflictInfo
import com.cortinadev.dogmatix.util.SaveKind
import com.cortinadev.dogmatix.util.SaveSyncResult

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

    val emulatorFolders by viewModel.emulatorFolders.collectAsState()
    // An emulator's folder is picked first, then which emulator it is.
    var pickedEmulatorFolder by remember { mutableStateOf<String?>(null) }
    val emulatorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { persist(it); pickedEmulatorFolder = it.toString() }
    }
    pickedEmulatorFolder?.let { uri ->
        EmulatorPresetDialog(
            onPick = { preset -> viewModel.addEmulatorFolder(context, preset, uri); pickedEmulatorFolder = null },
            onDismiss = { pickedEmulatorFolder = null }
        )
    }

    val notSet = stringResource(R.string.settings_not_set)
    val ready = ui.rommUrl.isNotBlank() && (ui.savesDir.isNotBlank() || ui.statesDir.isNotBlank() || emulatorFolders.isNotEmpty())
    // Four cards: the folders, the automatic sync, "Sync now" with its outcome, the conflicts.
    val folderRows: List<@Composable () -> Unit> = buildList {
        add {
            SettingRow(
                icon = R.drawable.ic_save,
                title = stringResource(R.string.save_sync_saves_folder),
                hint = folderLabel(ui.savesDir) ?: stringResource(R.string.save_sync_saves_folder_hint),
                onClick = { savesPicker.launch(null) },
            ) { ActionPill(stringResource(R.string.settings_change), { savesPicker.launch(null) }, icon = R.drawable.ic_folder_open) }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_history,
                title = stringResource(R.string.save_sync_states_folder),
                hint = folderLabel(ui.statesDir) ?: stringResource(R.string.save_sync_states_folder_hint),
                onClick = { statesPicker.launch(null) },
            ) { ActionPill(stringResource(R.string.settings_change), { statesPicker.launch(null) }, icon = R.drawable.ic_folder_open) }
        }
        emulatorFolders.forEach { folder ->
            add {
                SettingRow(
                    icon = R.drawable.ic_folder,
                    title = folder.label,
                    hint = folderLabel(folder.uri) ?: folder.uri,
                    onClick = { viewModel.removeEmulatorFolder(context, folder.label) },
                ) {
                    ActionPill(
                        stringResource(R.string.save_sync_emulator_remove),
                        { viewModel.removeEmulatorFolder(context, folder.label) },
                        icon = R.drawable.ic_trash,
                        tone = ActionTone.Danger
                    )
                }
            }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_add,
                title = stringResource(R.string.save_sync_emulator_add),
                hint = stringResource(R.string.save_sync_emulator_add_hint),
                onClick = { emulatorPicker.launch(null) },
            ) { ActionPill(stringResource(R.string.save_sync_emulator_add_action), { emulatorPicker.launch(null) }, icon = R.drawable.ic_folder_open) }
        }
    }
    val autoRows: List<@Composable () -> Unit> = buildList {
        add {
            SettingRow(
                icon = R.drawable.ic_sync,
                title = stringResource(R.string.save_sync_auto),
                hint = stringResource(R.string.save_sync_auto_hint),
                onClick = { viewModel.setAuto(context, !ui.auto) },
                onAdjust = { viewModel.setAuto(context, it > 0) },
            ) { ThemedSwitch(ui.auto) { viewModel.setAuto(context, it) } }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_trash,
                title = stringResource(R.string.save_sync_deletions),
                hint = stringResource(R.string.save_sync_deletions_hint),
                onClick = { viewModel.setDeletions(context, !ui.deletions) },
                onAdjust = { viewModel.setDeletions(context, it > 0) },
            ) { ThemedSwitch(ui.deletions) { viewModel.setDeletions(context, it) } }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_schedule,
                title = stringResource(R.string.save_sync_bg),
                hint = stringResource(R.string.save_sync_bg_hint),
                onClick = { setBackground(!ui.background) },
                onAdjust = { setBackground(it > 0) },
            ) { ThemedSwitch(ui.background) { setBackground(it) } }
        }
        if (ui.background) {
            add {
                SettingRow(
                    icon = R.drawable.ic_timer,
                    title = stringResource(R.string.save_sync_bg_interval),
                    hint = null,
                    onClick = { adjustInterval(1) },
                    onAdjust = ::adjustInterval,
                ) {
                    Stepper(stringResource(R.string.save_sync_hours_short, ui.intervalHours), onDecrement = { adjustInterval(-1) }, onIncrement = { adjustInterval(1) }, valueWidth = 64.dp)
                }
            }
            add {
                SettingRow(
                    icon = R.drawable.ic_wifi,
                    title = stringResource(R.string.save_sync_bg_wifi), hint = null,
                    onClick = { viewModel.setWifiOnly(context, !ui.wifiOnly) }, onAdjust = { viewModel.setWifiOnly(context, it > 0) },
                ) { ThemedSwitch(ui.wifiOnly) { viewModel.setWifiOnly(context, it) } }
            }
            add {
                SettingRow(
                    icon = R.drawable.ic_charging,
                    title = stringResource(R.string.save_sync_bg_charging), hint = null,
                    onClick = { viewModel.setCharging(context, !ui.charging) }, onAdjust = { viewModel.setCharging(context, it > 0) },
                ) { ThemedSwitch(ui.charging) { viewModel.setCharging(context, it) } }
            }
        }
    }
    val syncRows: List<@Composable () -> Unit> = buildList {
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
                icon = R.drawable.ic_cloud_sync,
                title = stringResource(R.string.save_sync_now),
                hint = hint,
                onClick = { if (ready) viewModel.syncNow() },
                hintColor = if (sync.error != null && !sync.running) MaterialTheme.colorScheme.error else null,
                below = if (sync.running) {
                    { SyncMeter(ProgressText.fraction(sync.progress), modifier = Modifier.padding(top = 6.dp)) }
                } else null
            ) {
                ActionPill(
                    stringResource(if (sync.running) R.string.save_sync_busy else R.string.save_sync_action),
                    { if (ready) viewModel.syncNow() },
                    icon = R.drawable.ic_sync,
                    tone = if (ready && !sync.running) ActionTone.Accent else ActionTone.Neutral
                )
            }
        }
        sync.last?.takeIf { it.deletionsHeld > 0 }?.let { held ->
            add {
                SettingRow(
                    icon = R.drawable.ic_warning,
                    title = pluralStringResource(R.plurals.save_sync_held_title, held.deletionsHeld, held.deletionsHeld),
                    hint = stringResource(R.string.save_sync_held_hint),
                    onClick = viewModel::applyHeldDeletions,
                ) { ActionPill(stringResource(R.string.save_sync_held_apply), viewModel::applyHeldDeletions, tone = ActionTone.Danger) }
            }
        }
        sync.last?.errors?.forEach { error ->
            add { SyncErrorRow(error) }
        }
    }
    val conflictRows: List<@Composable () -> Unit> = buildList {
        sync.conflicts.forEach { conflict -> add { ConflictRow(conflict, notSet, viewModel) } }
    }

    val cells = CardGrid.layout(
        listOf(
            CardGrid.Section(header = true, items = folderRows.size),
            CardGrid.Section(header = true, items = autoRows.size),
            CardGrid.Section(header = true, items = syncRows.size),
            CardGrid.Section(header = true, items = conflictRows.size)
        ),
        columns = 1
    )
    val rowLists = listOf(folderRows, autoRows, syncRows, conflictRows)

    val rowFocus = remember(cells.size) { List(cells.size) { FocusRequester() } }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(cells.size) {
        val first = cells.indexOfFirst { it.kind == CardGrid.Kind.ITEM }
        if (focusedIndex < 0 && first >= 0) runCatching { rowFocus[first].requestFocus() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Column(
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ScreenTitle(
                text = stringResource(R.string.settings_save_sync),
                icon = R.drawable.ic_cloud_sync,
                trailing = { SyncStatusPills(ui.rommUrl.isNotBlank(), sync) }
            )
            Text(
                stringResource(R.string.save_sync_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(cells.size) { index ->
                val cell = cells[index]
                CardCell(cell, gapAbove = if (index == 0) 0.dp else 14.dp) {
                    if (cell.kind == CardGrid.Kind.HEADER) {
                        SyncCardHeader(cell.section, sync.conflicts.size)
                    } else {
                        Box(
                            modifier = Modifier
                                .focusRequester(rowFocus[index])
                                .onFocusChanged { if (it.hasFocus) focusedIndex = index }
                        ) { rowLists[cell.section][cell.item]() }
                    }
                }
            }
        }
    }
}

/** The header of each Save sync card (0 folders, 1 automatic, 2 sync, 3 conflicts). */
@Composable
private fun SyncCardHeader(section: Int, conflicts: Int) {
    when (section) {
        0 -> SettingsCardHeader(stringResource(R.string.save_sync_v5_section_folders), R.drawable.ic_folder)
        1 -> SettingsCardHeader(stringResource(R.string.save_sync_v5_section_auto), R.drawable.ic_schedule)
        2 -> SettingsCardHeader(stringResource(R.string.save_sync_v5_section_sync), R.drawable.ic_cloud_sync)
        else -> Column {
            SettingsCardHeader(stringResource(R.string.save_sync_v5_section_conflicts), R.drawable.ic_warning) {
                Pill(conflicts.toString(), tone = PillTone.Warning)
            }
            Text(
                stringResource(R.string.save_sync_conflicts_header, conflicts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = settingsInset(), end = settingsInset(), bottom = 8.dp)
            )
        }
    }
}

/** Where the sync stands, next to the title: not set up, running, failed or when it last ran. */
@Composable
private fun SyncStatusPills(configured: Boolean, sync: SaveSyncState) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val last = sync.last
        when {
            !configured -> Pill(stringResource(R.string.settings_v5_pill_not_set_up), tone = PillTone.Neutral, icon = R.drawable.ic_cloud_off)
            sync.running -> Pill(sync.progress ?: stringResource(R.string.save_sync_busy), tone = PillTone.Info, icon = R.drawable.ic_sync)
            sync.error != null -> Pill(stringResource(R.string.save_sync_v5_pill_failed), tone = PillTone.Danger, icon = R.drawable.ic_error_circle)
            last != null -> Pill(relative(last.finishedAt) ?: stringResource(R.string.save_sync_v5_pill_synced), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
            else -> Pill(stringResource(R.string.save_sync_never), tone = PillTone.Neutral)
        }
        if (sync.conflicts.isNotEmpty()) {
            Pill(
                pluralStringResource(R.plurals.save_sync_v5_conflicts, sync.conflicts.size, sync.conflicts.size),
                tone = PillTone.Warning,
                icon = R.drawable.ic_warning
            )
        }
    }
}

/** The progress of a running sync: a bar when the file count is known, a gliding segment before. */
@Composable
private fun SyncMeter(fraction: Float?, modifier: Modifier = Modifier) {
    if (fraction != null) {
        MeterBar(fraction, modifier = modifier, height = 6.dp)
        return
    }
    val reduce = LocalReduceMotion.current
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    // Read only while drawing: the segment glides without recomposing anything.
    val phase: State<Float>? = if (reduce) null else rememberInfiniteTransition(label = "syncMeter").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "syncPhase"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .drawBehind {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = radius)
                val segment = size.width * 0.3f
                val p = phase?.value ?: 0.35f
                val x = (size.width + segment) * p - segment
                clipRect {
                    drawRoundRect(color, topLeft = Offset(x, 0f), size = Size(segment, size.height), cornerRadius = radius)
                }
            }
    )
}

/** A file that did not sync, with the whole reason. */
@Composable
private fun SyncErrorRow(error: String) {
    SettingRow(
        icon = R.drawable.ic_error_circle,
        title = stringResource(R.string.save_sync_error_title),
        hint = error,
        onClick = {}
    ) {}
}

/**
 * A save changed on both sides: the device's copy and RomM's side by side, each with its size,
 * time and a "newer" mark, and the button that keeps it. ◀ keeps the device's, ▶ the server's.
 */
@Composable
private fun ConflictRow(conflict: SaveConflict, notSet: String, viewModel: SaveSyncViewModel) {
    val info = SaveConflictInfo.of(conflict)
    val kind = stringResource(if (conflict.local.kind == SaveKind.STATE) R.string.save_sync_kind_state else R.string.save_sync_kind_save)
    val newer = when (info.newer) {
        NewerSide.DEVICE -> stringResource(R.string.save_sync_newer_device)
        NewerSide.SERVER -> stringResource(R.string.save_sync_newer_server)
        NewerSide.SAME -> stringResource(R.string.save_sync_newer_same)
        NewerSide.UNKNOWN -> null
    }
    val sizeNote = when {
        info.sameSize -> stringResource(R.string.save_sync_size_same)
        info.sizeDelta < 0 -> stringResource(R.string.save_sync_size_smaller, formatBytes(-info.sizeDelta))
        else -> stringResource(R.string.save_sync_size_bigger, formatBytes(info.sizeDelta))
    }
    val source = rememberFocusSource()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(source)
            .clip(RoundedCornerShape(8.dp))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { viewModel.resolve(conflict, keepDevice = true); true }
                    Key.DirectionRight -> { viewModel.resolve(conflict, keepDevice = false); true }
                    else -> false
                }
            }
            .clickable(interactionSource = source, indication = null, onClick = {})
            .padding(horizontal = settingsInset(), vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SettingsTileGap)) {
            SettingsIconTile(if (conflict.local.kind == SaveKind.STATE) R.drawable.ic_history else R.drawable.ic_save)
            Column(modifier = Modifier.weight(1f)) {
                Text(kind, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(conflict.local.path, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConflictSide(
                label = stringResource(R.string.save_sync_v5_side_device),
                icon = R.drawable.ic_controller,
                sizeText = formatBytes(info.deviceSize),
                time = relative(info.deviceModified) ?: notSet,
                newer = info.newer == NewerSide.DEVICE,
                keepLabel = stringResource(R.string.save_sync_keep_device),
                onKeep = { viewModel.resolve(conflict, keepDevice = true) },
                modifier = Modifier.weight(1f)
            )
            ConflictSide(
                label = stringResource(R.string.save_sync_v5_side_server),
                icon = R.drawable.ic_server,
                sizeText = formatBytes(info.serverSize),
                time = relative(info.serverModified) ?: notSet,
                newer = info.newer == NewerSide.SERVER,
                keepLabel = stringResource(R.string.save_sync_keep_server),
                onKeep = { viewModel.resolve(conflict, keepDevice = false) },
                modifier = Modifier.weight(1f)
            )
        }
        StateShotPair(conflict)
        Text(
            listOfNotNull(newer, sizeNote).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One side of a conflict: where the copy lives, its size and time, and the button that keeps it. */
@Composable
private fun ConflictSide(
    label: String,
    icon: Int,
    sizeText: String,
    time: String,
    newer: Boolean,
    keepLabel: String,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Panel(
        modifier = modifier,
        tone = if (newer) PanelTone.Accent else PanelTone.Raised,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = if (newer) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, modifier = Modifier.weight(1f, fill = false))
            if (newer) Pill(stringResource(R.string.save_sync_v5_newer), tone = PillTone.Success)
        }
        Text(
            "$sizeText · $time",
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = scheme.onSurfaceVariant
        )
        ActionPill(keepLabel, onKeep, tone = if (newer) ActionTone.Accent else ActionTone.Neutral)
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

/** Which emulator a picked folder belongs to: its saves are synced under that emulator's name. */
@Composable
private fun EmulatorPresetDialog(onPick: (EmulatorSaveFolders.Preset) -> Unit, onDismiss: () -> Unit) {
    val closeFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_sync_emulator_pick)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.save_sync_emulator_pick_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                EmulatorSaveFolders.presets.forEach { preset ->
                    val source = rememberFocusSource()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .focusRing(source)
                            .clickable(interactionSource = source, indication = null) { onPick(preset) }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(preset.label, style = MaterialTheme.typography.bodyLarge)
                        Text(preset.folderHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss, initialFocus = closeFocus) }
    )
}
