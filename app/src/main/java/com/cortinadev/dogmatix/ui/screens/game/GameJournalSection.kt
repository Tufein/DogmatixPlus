package com.cortinadev.dogmatix.ui.screens.game

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.SectionTitle
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.GameJournal
import com.cortinadev.dogmatix.util.JournalAttachment
import com.cortinadev.dogmatix.util.JournalAttachmentKind
import com.cortinadev.dogmatix.util.JournalKey

/** Only the Android document picker supplies attachments; restored metadata requires choosing again. */
@Composable
fun GameJournalSection(consoleId: String, fileName: String, viewModel: GameJournalViewModel = hiltViewModel()) {
    val profile by viewModel.activeProfile.collectAsState()
    val key = remember(profile, consoleId, fileName) { JournalKey(profile, consoleId, fileName) }
    LaunchedEffect(key) { viewModel.show(key) }
    val ui by viewModel.ui.collectAsState()
    val entry = ui.entry?.takeIf { it.key == key }
    val context = LocalContext.current
    var pick by remember { mutableStateOf<JournalPick?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pick
        pick = null
        if (uri != null && pending != null) viewModel.attach(pending, uri)
    }
    var editing by remember(key) { mutableStateOf(false) }
    var draft by remember(key) { mutableStateOf("") }
    var openingFailed by remember(key) { mutableStateOf(false) }
    var removing by remember(key) { mutableStateOf<JournalAttachment?>(null) }
    LaunchedEffect(entry?.modifiedAt, ui.busy) { if (editing && !ui.busy && !ui.failed && entry?.note == draft) editing = false }
    val choose: (JournalAttachmentKind, JournalAttachment?) -> Unit = { kind, previous ->
        viewModel.picker(kind, previous)?.let {
            pick = it
            chooser.launch(if (kind == JournalAttachmentKind.MANUAL) arrayOf("application/pdf") else GameJournal.screenshotMimes.toTypedArray())
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.journal_title), icon = R.drawable.ic_description)
        Panel(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.journal_hint), style = MaterialTheme.typography.bodySmall)
            if (entry == null && ui.busy) CircularProgressIndicator()
            entry?.let {
                Text(it.note.ifBlank { stringResource(R.string.journal_empty) }, style = MaterialTheme.typography.bodyMedium)
                ActionPill(stringResource(R.string.journal_edit), { draft = it.note; editing = true }, enabled = !ui.busy)
                it.recoveredNotes.forEach { alternative ->
                    Text(stringResource(R.string.journal_recovered), style = MaterialTheme.typography.labelMedium)
                    Text(alternative, style = MaterialTheme.typography.bodySmall)
                    ActionPill(stringResource(R.string.journal_use_note), { draft = alternative; editing = true }, enabled = !ui.busy)
                }
                it.attachments.forEach { attachment ->
                    Text(attachment.name, style = MaterialTheme.typography.bodyMedium)
                    if (!viewModel.accessible(attachment)) Text(stringResource(R.string.journal_choose_again), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (viewModel.accessible(attachment)) ActionPill(stringResource(R.string.journal_open), {
                            val uri = attachment.uri!!.toUri()
                            openingFailed = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, attachment.mime)
                                clipData = ClipData.newRawUri(attachment.name, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }) }.isFailure
                        }, enabled = !ui.busy)
                        else ActionPill(stringResource(R.string.journal_reselect), { choose(attachment.kind, attachment) }, enabled = !ui.busy)
                        ActionPill(stringResource(R.string.journal_remove), { removing = attachment }, enabled = !ui.busy)
                    }
                }
                if (it.attachments.size < GameJournal.MAX_ATTACHMENTS) {
                    ActionPill(stringResource(R.string.journal_add_image), { choose(JournalAttachmentKind.SCREENSHOT, null) }, enabled = !ui.busy)
                    ActionPill(stringResource(R.string.journal_add_manual), { choose(JournalAttachmentKind.MANUAL, null) }, enabled = !ui.busy)
                }
            }
            if (ui.failed || openingFailed) {
                Text(stringResource(R.string.journal_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                ActionPill(stringResource(R.string.journal_reload), { openingFailed = false; viewModel.reload() }, enabled = !ui.busy)
            }
        }
    }
    if (editing && entry != null) AlertDialog(
        modifier = Modifier.closeOnGamepadB { editing = false }, onDismissRequest = { editing = false },
        title = { Text(stringResource(R.string.journal_edit)) },
        text = { OutlinedTextField(draft, { if (it.length <= GameJournal.MAX_NOTE) draft = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            label = { Text(stringResource(R.string.journal_note)) }, supportingText = { Text("${draft.length} / ${GameJournal.MAX_NOTE}") }) },
        confirmButton = { DialogButton(stringResource(R.string.journal_save), { viewModel.save(draft) }, enabled = !ui.busy) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), { editing = false }, initialFocus = rememberInitialFocus()) }
    )
    removing?.let { attachment -> AlertDialog(
        modifier = Modifier.closeOnGamepadB { removing = null }, onDismissRequest = { removing = null },
        title = { Text(stringResource(R.string.journal_remove)) }, text = { Text(stringResource(R.string.journal_remove_hint, attachment.name)) },
        confirmButton = { DialogButton(stringResource(R.string.journal_remove), { removing = null; viewModel.remove(attachment.id) }) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), { removing = null }, initialFocus = rememberInitialFocus()) }
    ) }
}
