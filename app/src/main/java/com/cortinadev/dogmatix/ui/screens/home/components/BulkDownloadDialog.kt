package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.BulkPlan
import com.cortinadev.dogmatix.util.BulkPlanner

/**
 * "Download all": what the games the filters show would add up to (count, size, free space), what
 * is skipped, and the choice to take only the best version of each game.
 */
@Composable
fun BulkDownloadDialog(
    plan: suspend (bestOnly: Boolean) -> BulkPlan,
    onConfirm: (BulkPlan) -> Unit,
    onDismiss: () -> Unit
) {
    var bestOnly by remember { mutableStateOf(true) }
    var current by remember { mutableStateOf<BulkPlan?>(null) }
    LaunchedEffect(bestOnly) {
        current = null
        current = plan(bestOnly)
    }
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bulk_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Checkbox(checked = bestOnly, onCheckedChange = { bestOnly = it })
                    Text(stringResource(R.string.bulk_best_only), style = MaterialTheme.typography.bodyMedium)
                }
                val p = current
                if (p == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.bulk_counting), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(
                        pluralStringResource(R.plurals.bulk_summary, p.chosen.size, p.chosen.size, formatBytes(p.totalBytes)),
                        style = MaterialTheme.typography.titleMedium
                    )
                    val skipped = buildList {
                        if (p.skippedOwned > 0) add(pluralStringResource(R.plurals.bulk_skipped_owned, p.skippedOwned, p.skippedOwned))
                        if (p.skippedActive > 0) add(pluralStringResource(R.plurals.bulk_skipped_active, p.skippedActive, p.skippedActive))
                        if (p.skippedVersions > 0) add(pluralStringResource(R.plurals.bulk_skipped_versions, p.skippedVersions, p.skippedVersions))
                    }
                    if (skipped.isNotEmpty()) Text(skipped.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    p.freeBytes?.let { Text(stringResource(R.string.bulk_free, formatBytes(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!p.fits) Text(stringResource(R.string.bulk_no_room), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    if (p.chosen.size >= BulkPlanner.MAX_FILES) Text(stringResource(R.string.bulk_capped, BulkPlanner.MAX_FILES), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            val p = current
            DialogButton(
                text = stringResource(R.string.bulk_start, p?.chosen?.size ?: 0),
                onClick = { if (p != null) onConfirm(p) },
                enabled = p != null && p.chosen.isNotEmpty() && p.fits
            )
        },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}

/** Name for the current filters, saved as a view. */
@Composable
fun SaveViewDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.view_save)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.view_save_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true)
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.dialog_save), onClick = { onSave(name) }, enabled = name.isNotBlank()) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}
