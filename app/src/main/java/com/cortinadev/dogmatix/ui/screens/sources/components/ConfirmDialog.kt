package com.cortinadev.dogmatix.ui.screens.sources.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.IconTile
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus

/**
 * Yes / no confirmation for destructive actions. Focus starts on Cancel so a stray A press
 * on the gamepad does nothing; B cancels as well.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** A mark in front of the title (5.0); a [destructive] one is drawn in the danger colours. */
    icon: Int? = null,
    destructive: Boolean = false
) {
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = {
            if (icon == null) Text(title) else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val scheme = MaterialTheme.colorScheme
                IconTile(
                    icon,
                    size = 36.dp,
                    container = if (destructive) scheme.errorContainer else null,
                    tint = if (destructive) scheme.onErrorContainer else null
                )
                Text(title)
            }
        },
        text = { Text(message) },
        confirmButton = {
            DialogButton(text = confirmText, onClick = { onConfirm(); onDismiss() })
        },
        dismissButton = {
            DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus)
        }
    )
}
