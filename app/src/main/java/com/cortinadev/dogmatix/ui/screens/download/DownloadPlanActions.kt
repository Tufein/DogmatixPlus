package com.cortinadev.dogmatix.ui.screens.download

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.service.DownloadPlanPreview
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.ConditionKind
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DownloadCondition
import com.cortinadev.dogmatix.util.DownloadPlanStatus
import com.cortinadev.dogmatix.util.ToastUtil
import java.text.DateFormat
import java.util.Date

/** Downloads integration: SAF file operations, Android sharing and an explicit import review. */
@Composable
fun DownloadPlanActions(
    downloads: List<DownloadItemModel>,
    selectedNames: Set<String>,
    modifier: Modifier = Modifier,
    viewModel: DownloadPlanViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val errorMessage = ui.error?.let { stringResource(it) }
    val importedMessage = ui.imported?.let { stringResource(R.string.plan26_imported, it) }
    val savedMessage = stringResource(R.string.plan26_saved)
    val shareTitle = stringResource(R.string.plan26_share)
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        viewModel.savePending(it)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::read)
    }
    LaunchedEffect(errorMessage, importedMessage, ui.saved) {
        errorMessage?.let { ToastUtil.showError(context, it) }
        importedMessage?.let { ToastUtil.showInfo(context, it) }
        if (ui.saved) ToastUtil.showSuccess(context, savedMessage)
        if (ui.error != null || ui.imported != null || ui.saved) viewModel.clearMessage()
    }
    Column(modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.plan26_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.plan26_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionPill(stringResource(R.string.plan26_export), enabled = !ui.busy && downloads.isNotEmpty(), onClick = {
                viewModel.exportFile(selectedNames) { exportPicker.launch("dogmatix-download-plan.json") }
            })
            ActionPill(shareTitle, icon = R.drawable.ic_share, enabled = !ui.busy && downloads.isNotEmpty(), onClick = {
                viewModel.share(selectedNames) { uri ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("dogmatix-download-plan.json", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, shareTitle))
                }
            })
            ActionPill(stringResource(R.string.plan26_import), enabled = !ui.busy, onClick = {
                importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            })
        }
        DownloadPlanExportFeedback(ui.exportSkipped)
        if (ui.busy) Text(stringResource(R.string.tools_scanning), style = MaterialTheme.typography.bodySmall)
    }
    ui.preview?.let { preview ->
        DownloadPlanPreviewDialog(preview, ui.busy, viewModel::confirm, viewModel::dismissPreview)
    }
}

/** Unlike the save toast, omissions remain visible after returning from the picker/share sheet. */
@Composable
fun DownloadPlanExportFeedback(skippedCount: Int) {
    if (skippedCount > 0) Text(
        pluralStringResource(R.plurals.plan26_export_skipped, skippedCount, skippedCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun DownloadPlanPreviewDialog(
    preview: DownloadPlanPreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB { if (!busy) onDismiss() },
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.plan26_review)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.plan26_review_hint))
                Text(stringResource(R.string.plan26_count, preview.readyCount, preview.rows.size))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    contentPadding = PaddingValues(bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(preview.rows, key = { _, row -> row.item.key }) { index, row ->
                        Column {
                            Text("${index + 1}. ${row.item.displayName}", style = MaterialTheme.typography.bodyMedium)
                            Text(ConsoleFormatter.getConsoleDisplayName(row.item.consoleId), style = MaterialTheme.typography.labelSmall)
                            Text(stringResource(planStatusString(row.status)), style = MaterialTheme.typography.bodySmall,
                                color = if (row.status == DownloadPlanStatus.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(planConditionLabel(row.item.condition), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.plan26_enqueue, preview.readyCount), onConfirm, !busy && preview.readyCount > 0) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onDismiss, !busy, cancelFocus) }
    )
}

private fun planStatusString(status: DownloadPlanStatus): Int = when (status) {
    DownloadPlanStatus.READY -> R.string.plan26_ready
    DownloadPlanStatus.MISSING -> R.string.plan26_missing
    DownloadPlanStatus.UNAVAILABLE -> R.string.plan26_unavailable
    DownloadPlanStatus.RESTRICTED -> R.string.plan26_restricted
    DownloadPlanStatus.ALREADY_QUEUED -> R.string.plan26_already_queued
    DownloadPlanStatus.OWNED -> R.string.plan26_owned
    DownloadPlanStatus.NAME_CONFLICT -> R.string.plan26_name_conflict
    DownloadPlanStatus.AMBIGUOUS -> R.string.plan26_ambiguous
}

@Composable
private fun planConditionLabel(condition: DownloadCondition?): String = when (condition?.kind) {
    null -> stringResource(R.string.plan6_when_now)
    ConditionKind.WIFI -> stringResource(R.string.plan6_when_wifi)
    ConditionKind.CHARGING -> stringResource(R.string.plan6_when_charging)
    ConditionKind.WIFI_AND_CHARGING -> stringResource(R.string.plan6_when_both)
    ConditionKind.TONIGHT -> stringResource(R.string.plan6_when_tonight)
    ConditionKind.AT_TIME -> stringResource(R.string.plan6_pill_time, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(condition.atMillis)))
}
