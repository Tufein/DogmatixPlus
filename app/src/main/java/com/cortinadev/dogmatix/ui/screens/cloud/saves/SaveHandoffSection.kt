package com.cortinadev.dogmatix.ui.screens.cloud.saves

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.SectionTitle
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.JournalKey
import com.cortinadev.dogmatix.util.SaveHandoffDirection
import com.cortinadev.dogmatix.util.SaveHandoffPreview

@Composable
fun SaveHandoffSection(consoleId: String, fileName: String, viewModel: SaveHandoffViewModel = hiltViewModel()) {
    val profile by viewModel.activeProfile.collectAsState()
    val key = remember(profile, consoleId, fileName) { JournalKey(profile, consoleId, fileName) }
    LaunchedEffect(key) { viewModel.show(key) }
    val ui by viewModel.ui.collectAsState()
    val shown = ui.takeIf { it.key == key } ?: SaveHandoffUi(key)
    val preview = shown.preview
    var confirm by remember(key) { mutableStateOf<SaveHandoffPreview?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, key) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { confirm = null; viewModel.expireReady() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.handoff_title), icon = R.drawable.ic_cloud)
        Panel(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.handoff_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.handoff_states_excluded), style = MaterialTheme.typography.bodySmall)
            if (shown.busy) CircularProgressIndicator()
            if (shown.failed) Text(stringResource(if (shown.tooLarge) R.string.handoff_too_large else R.string.handoff_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            preview?.let { data ->
                if (data.files.isEmpty()) Text(stringResource(R.string.handoff_no_saves))
                data.files.forEach { row ->
                    val direction = when (row.direction) {
                        SaveHandoffDirection.UPLOAD -> R.string.handoff_upload
                        SaveHandoffDirection.DOWNLOAD -> R.string.handoff_download
                        SaveHandoffDirection.IDENTICAL -> R.string.handoff_identical
                        SaveHandoffDirection.CONFLICT -> R.string.handoff_conflict
                        SaveHandoffDirection.BLOCKED -> R.string.handoff_ambiguous
                    }
                    Text(row.path, style = MaterialTheme.typography.bodyMedium)
                    Text("${stringResource(direction)} · ${formatBytes(row.local?.size ?: row.remote?.size ?: 0L)}", style = MaterialTheme.typography.bodySmall)
                }
                when {
                    data.ready -> { Text(stringResource(R.string.handoff_ready), color = MaterialTheme.colorScheme.tertiary); Text(stringResource(R.string.handoff_next_device), style = MaterialTheme.typography.bodySmall) }
                    data.canTransfer -> ActionPill(stringResource(R.string.handoff_transfer), { confirm = data }, enabled = !shown.busy)
                    data.files.isNotEmpty() -> Text(stringResource(R.string.handoff_resolve_first), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            ActionPill(stringResource(R.string.handoff_check), viewModel::check, enabled = !shown.busy)
        }
    }
    confirm?.let { approved -> AlertDialog(
        modifier = Modifier.closeOnGamepadB { confirm = null }, onDismissRequest = { confirm = null },
        title = { Text(stringResource(R.string.handoff_transfer)) }, text = { Text(stringResource(R.string.handoff_confirm, approved.files.size)) },
        confirmButton = { DialogButton(stringResource(R.string.handoff_transfer), { confirm = null; viewModel.transfer(approved) }) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), { confirm = null }, initialFocus = rememberInitialFocus()) }
    ) }
}
