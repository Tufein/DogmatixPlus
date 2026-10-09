package com.cortinadev.dogmatix.ui.screens.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadSourceChoices
import com.cortinadev.dogmatix.data.model.DownloadSourceKind
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus

/** Selecting an option only previews it. The exact indexed source starts after confirmation. */
@Composable
fun DownloadSourceChoiceDialog(
    choices: DownloadSourceChoices?,
    loading: Boolean,
    busy: Boolean,
    error: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var picked by remember(choices?.token) { mutableStateOf<String?>(null) }
    val focus = rememberInitialFocus()
    val alternatives = choices?.options.orEmpty().filterNot { it.current }
    AlertDialog(
        modifier = Modifier.closeOnGamepadB { if (!busy) onDismiss() },
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.source26_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.source26_restart_hint), style = MaterialTheme.typography.bodySmall)
                if (loading) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.source26_loading))
                } else {
                    if (alternatives.isEmpty()) Text(stringResource(R.string.source26_no_alternatives))
                    choices?.options.orEmpty().forEach { option ->
                        val interaction = rememberFocusSource()
                        val isSelected = option.id == picked
                        val scheme = MaterialTheme.colorScheme
                        Row(
                            modifier = (if (option.id == alternatives.firstOrNull()?.id) Modifier.focusRequester(focus) else Modifier)
                                .fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) scheme.primaryContainer else scheme.surfaceContainerHigh)
                                .focusRing(interaction, cornerRadius = 12.dp)
                                .semantics { role = Role.RadioButton; selected = isSelected }
                                .clickable(enabled = !busy && !option.current, interactionSource = interaction, indication = null) { picked = option.id }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(selected = isSelected, onClick = null, enabled = !busy && !option.current)
                            Column(Modifier.weight(1f)) {
                                val type = stringResource(when (option.kind) {
                                    DownloadSourceKind.WEB -> R.string.source26_web
                                    DownloadSourceKind.TORRENT -> R.string.source26_torrent
                                    DownloadSourceKind.ROMM -> R.string.source26_romm
                                })
                                Text(option.label.ifEmpty { type }, style = MaterialTheme.typography.bodyMedium)
                                Text(if (option.current) stringResource(R.string.source26_current) else type, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.source26_confirm), enabled = !loading && !busy && picked != null,
                onClick = { picked?.let(onConfirm) })
        },
        dismissButton = {
            DialogButton(stringResource(R.string.dialog_cancel), enabled = !busy,
                initialFocus = if (alternatives.isEmpty()) focus else null,
                onClick = onDismiss)
        }
    )
}
