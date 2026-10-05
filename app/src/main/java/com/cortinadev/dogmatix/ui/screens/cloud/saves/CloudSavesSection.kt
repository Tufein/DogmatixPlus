package com.cortinadev.dogmatix.ui.screens.cloud.saves

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.CoverImage
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.IconTile
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.SectionTitle
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.CloudSaveVersion
import com.cortinadev.dogmatix.util.DeviceSave
import com.cortinadev.dogmatix.util.DeviceSaveState
import com.cortinadev.dogmatix.util.SafetyCopy

/** Server versions / safety copies shown before "Show all". */
private const val FOLDED_ROWS = 4

/** What "Restore" was pressed on, waiting for the user's yes. */
private sealed interface PendingRestore {
    data class Server(val version: CloudSaveVersion) : PendingRestore
    data class Copy(val copy: SafetyCopy) : PendingRestore
}

/**
 * "Cloud saves" of one game (5.0): the RomM server's saves and states of it (newest first, with
 * state screenshots when RomM has them), the device's own files with "Upload now" when the device
 * is ahead, and the safety copies kept before a sync or restore replaced a file — each with
 * "Restore this version" (the file it replaces is kept as a safety copy first). Renders nothing when
 * the game is not on RomM and has no safety copies.
 *
 * Call in GameDetailsDialog's extra-sections slot:
 * `CloudSavesSection(consoleId = state.item.file.consoleId, fileName = state.item.file.fileName)`.
 * Rows are focusable (A = their action); keep them reachable by D-pad (the dialog's Up/Down scroll
 * handler should let focus move first when the section is inside the scrolled area).
 */
@Composable
fun CloudSavesSection(
    consoleId: String,
    fileName: String,
    modifier: Modifier = Modifier,
    viewModel: CloudSavesViewModel = hiltViewModel()
) {
    LaunchedEffect(consoleId, fileName) { viewModel.show(consoleId, fileName) }
    val ui by viewModel.ui.collectAsState()
    val data = ui.data?.takeIf { it.consoleId == consoleId && it.fileName == fileName && it.visible } ?: return

    var pending by remember(consoleId, fileName) { mutableStateOf<PendingRestore?>(null) }
    var allServer by remember(consoleId, fileName) { mutableStateOf(false) }
    var allCopies by remember(consoleId, fileName) { mutableStateOf(false) }
    val busy = ui.working != null

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.csave_section_title), icon = R.drawable.ic_cloud, modifier = Modifier.weight(1f))
            if (data.loadingServer || data.loadingDevice) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Panel(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ---- On the server
            if (data.romId != null) {
                GroupLabel(stringResource(R.string.csave_group_server))
                when {
                    data.server.isEmpty() && data.loadingServer -> InfoLine(stringResource(R.string.csave_loading))
                    data.server.isEmpty() && data.serverError != null -> {
                        InfoLine(stringResource(R.string.csave_server_error, data.serverError))
                        ActionPill(
                            stringResource(R.string.csave_retry),
                            onClick = viewModel::reload,
                            icon = R.drawable.ic_retry,
                            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                        )
                    }
                    data.server.isEmpty() -> InfoLine(stringResource(R.string.csave_none_on_server))
                    else -> {
                        val shown = if (allServer) data.server else data.server.take(FOLDED_ROWS)
                        shown.forEach { version ->
                            ServerRow(
                                version = version,
                                consoleId = consoleId,
                                canRestore = data.canRestore(version.entry.kind) && !version.entry.missingFromFs,
                                working = ui.working == CloudSavesViewModel.rowId(version),
                                enabled = !busy,
                                onRestore = { pending = PendingRestore.Server(version) }
                            )
                        }
                        if (data.server.size > FOLDED_ROWS) {
                            ShowAll(allServer, data.server.size) { allServer = !allServer }
                        }
                        if (!data.canRestoreSaves && !data.canRestoreStates) InfoLine(stringResource(R.string.csave_no_folder_hint))
                    }
                }
            }
            // ---- On this device
            if (data.device.isNotEmpty()) {
                GroupLabel(stringResource(R.string.csave_group_device))
                data.device.forEach { save ->
                    DeviceRow(
                        save = save,
                        canUpload = data.romId != null && save.canUpload,
                        working = ui.working == CloudSavesViewModel.rowId(save),
                        enabled = !busy,
                        onUpload = { viewModel.upload(save) }
                    )
                }
            }
            data.deviceError?.let { InfoLine(stringResource(R.string.csave_device_error, it)) }
            // ---- Safety copies
            if (data.safetyCopies.isNotEmpty()) {
                GroupLabel(stringResource(R.string.csave_group_copies))
                InfoLine(stringResource(R.string.csave_copies_hint))
                val shown = if (allCopies) data.safetyCopies else data.safetyCopies.take(FOLDED_ROWS)
                shown.forEach { copy ->
                    CopyRow(
                        copy = copy,
                        canRestore = data.canRestore(copy.kind),
                        working = ui.working == CloudSavesViewModel.rowId(copy),
                        enabled = !busy,
                        onRestore = { pending = PendingRestore.Copy(copy) }
                    )
                }
                if (data.safetyCopies.size > FOLDED_ROWS) {
                    ShowAll(allCopies, data.safetyCopies.size) { allCopies = !allCopies }
                }
            }
        }
        ui.notice?.let { Notice(it) }
    }

    pending?.let { p ->
        // The folder the file goes to is named in the dialog (a same-named save of another emulator is never taken).
        var target by remember(p) { mutableStateOf<String?>((p as? PendingRestore.Copy)?.copy?.path) }
        LaunchedEffect(p) {
            if (p is PendingRestore.Server) target = viewModel.targetFor(p.version)
        }
        RestoreDialog(
            pending = p,
            target = target,
            onConfirm = {
                pending = null
                when (p) {
                    is PendingRestore.Server -> viewModel.restore(p.version)
                    is PendingRestore.Copy -> viewModel.restore(p.copy)
                }
            },
            onDismiss = { pending = null }
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * One row: a leading picture or icon, a title with pills, a detail line and, when the row has an
 * action, its label at the end. Only rows with an action take focus (A runs it).
 */
@Composable
private fun SaveRow(
    title: String,
    detail: String,
    actionLabel: String?,
    actionIcon: Int?,
    working: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    pills: @Composable RowScope.() -> Unit = {}
) {
    val source = rememberFocusSource()
    val actionable = actionLabel != null && enabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (actionable) Modifier
                    .focusRing(source)
                    .clickable(interactionSource = source, indication = null, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        leading()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                pills()
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when {
            working -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            actionLabel != null -> Pill(actionLabel, tone = if (enabled) PillTone.Accent else PillTone.Neutral, icon = actionIcon)
        }
    }
}

@Composable
private fun ServerRow(
    version: CloudSaveVersion,
    consoleId: String,
    canRestore: Boolean,
    working: Boolean,
    enabled: Boolean,
    onRestore: () -> Unit
) {
    val e = version.entry
    val detail = listOfNotNull(kindLabel(e.kind), e.via, relativeTime(e.updatedMillis), e.size.takeIf { it > 0 }?.let { formatBytes(it) })
        .joinToString(" · ")
    SaveRow(
        title = e.fileName,
        detail = detail,
        actionLabel = if (canRestore) stringResource(R.string.csave_restore) else null,
        actionIcon = R.drawable.ic_restore,
        working = working,
        enabled = enabled,
        onClick = onRestore,
        leading = {
            val shot = version.screenshotUrl
            if (shot != null) {
                CoverImage(shot, consoleId, modifier = Modifier.size(width = 56.dp, height = 42.dp), shape = RoundedCornerShape(6.dp), showLabel = false)
            } else IconTile(kindIcon(e.kind), size = 34.dp)
        },
        pills = {
            when {
                version.onDevice -> Pill(stringResource(R.string.csave_pill_on_device), tone = PillTone.Success, icon = R.drawable.ic_check)
                version.current -> Pill(stringResource(R.string.csave_pill_current), tone = PillTone.Accent)
            }
            e.slot?.let { Pill(stringResource(R.string.csave_pill_slot, it), tone = PillTone.Neutral) }
            if (e.missingFromFs) Pill(stringResource(R.string.csave_pill_missing), tone = PillTone.Danger)
        }
    )
}

@Composable
private fun DeviceRow(save: DeviceSave, canUpload: Boolean, working: Boolean, enabled: Boolean, onUpload: () -> Unit) {
    val local = save.local
    val folder = local.path.substringBeforeLast('/', "").ifBlank { null }
    val detail = listOfNotNull(kindLabel(local.kind), folder, relativeTime(local.modified), local.size.takeIf { it > 0 }?.let { formatBytes(it) })
        .joinToString(" · ")
    SaveRow(
        title = local.name,
        detail = detail,
        actionLabel = if (canUpload) stringResource(R.string.csave_upload_now) else null,
        actionIcon = R.drawable.ic_cloud_upload,
        working = working,
        enabled = enabled,
        onClick = onUpload,
        leading = { IconTile(kindIcon(local.kind), size = 34.dp, container = MaterialTheme.colorScheme.secondaryContainer, tint = MaterialTheme.colorScheme.onSecondaryContainer) },
        pills = {
            when (save.state) {
                DeviceSaveState.IN_SYNC -> Pill(stringResource(R.string.csave_state_in_sync), tone = PillTone.Success)
                DeviceSaveState.DEVICE_NEWER -> Pill(stringResource(R.string.csave_state_device_newer), tone = PillTone.Accent)
                DeviceSaveState.SERVER_NEWER -> Pill(stringResource(R.string.csave_state_server_newer), tone = PillTone.Info)
                DeviceSaveState.BOTH_CHANGED -> Pill(stringResource(R.string.csave_state_both_changed), tone = PillTone.Warning)
                DeviceSaveState.NOT_ON_SERVER -> Pill(stringResource(R.string.csave_state_not_on_server), tone = PillTone.Neutral)
                DeviceSaveState.UNKNOWN -> Unit
            }
        }
    )
}

@Composable
private fun CopyRow(copy: SafetyCopy, canRestore: Boolean, working: Boolean, enabled: Boolean, onRestore: () -> Unit) {
    val folder = copy.path.substringBeforeLast('/', "").ifBlank { null }
    val detail = listOfNotNull(kindLabel(copy.kind), folder, relativeTime(copy.takenAt), copy.size.takeIf { it > 0 }?.let { formatBytes(it) })
        .joinToString(" · ")
    SaveRow(
        title = copy.name,
        detail = detail,
        actionLabel = if (canRestore) stringResource(R.string.csave_restore) else null,
        actionIcon = R.drawable.ic_restore,
        working = working,
        enabled = enabled,
        onClick = onRestore,
        leading = { IconTile(R.drawable.ic_history, size = 34.dp, container = MaterialTheme.colorScheme.surfaceContainerHigh, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    )
}

@Composable
private fun ShowAll(expanded: Boolean, total: Int, onToggle: () -> Unit) {
    ActionPill(
        label = if (expanded) stringResource(R.string.csave_show_less) else stringResource(R.string.csave_show_all, total),
        onClick = onToggle,
        icon = if (expanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down,
        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp)
    )
}

@Composable
private fun Notice(notice: CloudSavesNotice) {
    val (text, tone) = when (notice) {
        is CloudSavesNotice.Restored -> stringResource(R.string.csave_restored, notice.path) to PillTone.Success
        is CloudSavesNotice.Uploaded -> stringResource(R.string.csave_uploaded, notice.name) to PillTone.Success
        is CloudSavesNotice.Failed -> stringResource(R.string.csave_failed, notice.name, notice.message) to PillTone.Danger
        CloudSavesNotice.Busy -> stringResource(R.string.csave_busy) to PillTone.Warning
    }
    val icon = when (tone) {
        PillTone.Success -> R.drawable.ic_check_circle
        PillTone.Danger -> R.drawable.ic_error_circle
        else -> R.drawable.ic_hourglass
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 6.dp)
    ) {
        val color = when (tone) {
            PillTone.Success -> MaterialTheme.colorScheme.tertiary
            PillTone.Danger -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RestoreDialog(pending: PendingRestore, target: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (name, at) = when (pending) {
        is PendingRestore.Server -> pending.version.entry.fileName to pending.version.entry.updatedMillis
        is PendingRestore.Copy -> pending.copy.name to pending.copy.takenAt
    }
    val whenText = relativeTime(at) ?: stringResource(R.string.csave_unknown_time)
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_restore), contentDescription = null) },
        title = { Text(stringResource(R.string.csave_restore_title)) },
        text = {
            Text(
                if (target != null) stringResource(R.string.sync6_csave_restore_message_target, name, whenText, target)
                else stringResource(R.string.csave_restore_message, name, whenText)
            )
        },
        confirmButton = { DialogButton(stringResource(R.string.csave_restore), onClick = onConfirm) },
        // Cancel has the first focus: two quick presses of A never replace a save by accident.
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = rememberInitialFocus()) }
    )
}
