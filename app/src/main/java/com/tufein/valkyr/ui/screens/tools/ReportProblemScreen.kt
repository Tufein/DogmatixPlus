package com.tufein.valkyr.ui.screens.tools

import android.content.Intent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.GamepadButton
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.ui.components.ScreenTitle
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.ProblemReport
import com.tufein.valkyr.util.ReportCategory
import com.tufein.valkyr.util.ReportSection
import com.tufein.valkyr.util.ToastUtil

/**
 * "Report a problem / Share feedback" (3.0.0): the player picks a category, writes what happened,
 * what they expected and the steps, and chooses which sections of device and app details go
 * along. "What will be shared" lists exactly those sections; the preview below is the finished
 * report, readable and editable, and Copy (X) / Share (Y) hand it over. The app itself never
 * sends it anywhere.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportProblemScreen(viewModel: ReportProblemViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val firstFocus = rememberInitialFocus()
    var editing by remember { mutableStateOf<ReportField?>(null) }
    val copiedMessage = stringResource(R.string.report30_copied)
    val cleanedMessage = stringResource(R.string.report30_cleaned)
    val shareTitle = stringResource(R.string.report30_share_title)
    val ready = ui.preview.isNotBlank()

    fun copy() = viewModel.prepare { text, cleaned ->
        clipboard.setText(AnnotatedString(text))
        ToastUtil.showSuccess(context, if (cleaned) cleanedMessage else copiedMessage)
    }
    fun share() = viewModel.prepare { text, cleaned ->
        if (cleaned) ToastUtil.showInfo(context, cleanedMessage)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "[${ui.category.label}] Valkyr problem report")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, shareTitle))
    }

    // X copies, Y shares, as long as no edit dialog is open.
    val canAct by rememberUpdatedState(ready && editing == null)
    val copyNow by rememberUpdatedState(::copy)
    val shareNow by rememberUpdatedState(::share)
    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            if (!canAct) return@collect
            when (button) {
                GamepadButton.X -> copyNow()
                GamepadButton.Y -> shareNow()
                else -> Unit
            }
        }
    }
    PublishLegend(
        if (editing != null) null else listOfNotNull(
            LegendEntry("A", stringResource(R.string.pad_select)),
            if (ready) LegendEntry("X", stringResource(R.string.report30_copy)) else null,
            if (ready) LegendEntry("Y", stringResource(R.string.report30_share)) else null,
            LegendEntry("B", stringResource(R.string.pad_back))
        )
    )

    editing?.let { field ->
        ReportTextDialog(
            title = stringResource(fieldTitle(field)),
            hint = fieldHint(field)?.let { stringResource(it) },
            initial = viewModel.valueOf(field),
            monospace = field == ReportField.PREVIEW,
            onDone = { viewModel.setField(field, it); editing = null },
            onDismiss = { editing = null }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { ScreenTitle(stringResource(R.string.report30_title), icon = R.drawable.ic_bug) }
        item { Text(stringResource(R.string.report30_intro), style = MaterialTheme.typography.bodyMedium) }

        item { SectionTitle(stringResource(R.string.report30_category), modifier = Modifier.padding(top = 6.dp)) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportCategory.entries.forEachIndexed { index, category ->
                    val selected = ui.category == category
                    ActionPill(
                        stringResource(categoryLabel(category)),
                        { viewModel.setCategory(category) },
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        icon = if (selected) R.drawable.ic_check else null,
                        tone = if (selected) ActionTone.Accent else ActionTone.Neutral
                    )
                }
            }
        }

        item { SectionTitle(stringResource(R.string.report30_your_words), modifier = Modifier.padding(top = 6.dp)) }
        items(listOf(ReportField.WHAT, ReportField.EXPECTED, ReportField.STEPS, ReportField.STORAGE), key = { "field:$it" }) { field ->
            val value = viewModel.valueOf(field)
            val lines = if (value.isBlank()) listOfNotNull(stringResource(R.string.report30_not_filled), fieldHint(field)?.let { stringResource(it) })
                else listOf(value.lines().take(3).joinToString("\n").let { if (value.lines().size > 3 || it.length > 240) it.take(240) + "…" else it })
            ToolRow(stringResource(fieldTitle(field)), lines, onClick = { editing = field }, icon = R.drawable.ic_edit)
        }

        item { SectionTitle(stringResource(R.string.report30_sections), modifier = Modifier.padding(top = 6.dp)) }
        items(ReportSection.entries, key = { "section:$it" }) { section ->
            val on = section in ui.sections
            ToolRow(
                stringResource(sectionTitle(section)),
                listOf(sectionDescription(section)),
                onClick = { viewModel.toggle(section) },
                trailing = { Switch(checked = on, onCheckedChange = null) }
            )
        }

        item { SharedSummary(if (ui.failed) emptySet() else ui.sections) }

        item { SectionTitle(stringResource(R.string.report30_preview), modifier = Modifier.padding(top = 6.dp)) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill(stringResource(R.string.report30_copy), ::copy, icon = R.drawable.ic_content_copy, tone = ActionTone.Accent, enabled = ready)
                ActionPill(stringResource(R.string.report30_share), ::share, icon = R.drawable.ic_share, tone = ActionTone.Accent, enabled = ready)
                ActionPill(stringResource(R.string.report30_edit_text), { editing = ReportField.PREVIEW }, icon = R.drawable.ic_edit, enabled = ready)
                ActionPill(stringResource(R.string.report30_collect_again), viewModel::collect, icon = R.drawable.ic_restore, enabled = !ui.collecting)
            }
        }
        item { Text(stringResource(R.string.report30_preview_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (ui.collecting) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.report30_collecting), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (ui.failed) item { Text(stringResource(R.string.report30_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        // One focusable card per paragraph, so the D-pad walks through a long report line by line.
        items(ui.preview.trimEnd().split("\n\n").filter { it.isNotBlank() }) { paragraph ->
            Panel(Modifier.fillMaxWidth().focusableCard(), contentPadding = PaddingValues(12.dp)) {
                Text(paragraph, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** "What will be shared": exactly the switched-on sections, what never goes along, and that nothing is sent. */
@Composable
private fun SharedSummary(sections: Set<ReportSection>) {
    Panel(Modifier.fillMaxWidth().focusableCard(), tone = PanelTone.Accent, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.report30_shared_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            stringResource(R.string.report30_shared_count, sections.size, ReportSection.entries.size),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        ReportSection.entries.filter { it in sections }.forEach {
            Text("• " + stringResource(sectionTitle(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(stringResource(R.string.report30_never), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(stringResource(R.string.report30_no_send), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(stringResource(R.string.report30_english), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * Edits one text. A multi-line field keeps the D-pad for the cursor, so Start also confirms and
 * B cancels; the field takes focus (and the keyboard) when the dialog opens.
 */
@Composable
private fun ReportTextDialog(title: String, hint: String?, initial: String, monospace: Boolean, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val fieldFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss).onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_START && event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_UP) {
                onDone(value); true
            } else false
        },
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp).focusRequester(fieldFocus),
                    textStyle = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.dialog_ok), { onDone(value) }) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onDismiss) }
    )
}

private fun categoryLabel(category: ReportCategory): Int = when (category) {
    ReportCategory.BUG -> R.string.report30_cat_bug
    ReportCategory.DATA_LOSS_RISK -> R.string.report30_cat_data_loss
    ReportCategory.COMPATIBILITY -> R.string.report30_cat_compat
    ReportCategory.IDEA -> R.string.report30_cat_idea
}

private fun fieldTitle(field: ReportField): Int = when (field) {
    ReportField.WHAT -> R.string.report30_what
    ReportField.EXPECTED -> R.string.report30_expected
    ReportField.STEPS -> R.string.report30_steps
    ReportField.STORAGE -> R.string.report30_storage
    ReportField.PREVIEW -> R.string.report30_edit_text
}

private fun fieldHint(field: ReportField): Int? = when (field) {
    ReportField.STEPS -> R.string.report30_steps_hint
    ReportField.STORAGE -> R.string.report30_storage_hint
    ReportField.PREVIEW -> R.string.report30_preview_note
    else -> null
}

private fun sectionTitle(section: ReportSection): Int = when (section) {
    ReportSection.APP -> R.string.report30_sec_app
    ReportSection.DEVICE -> R.string.report30_sec_device
    ReportSection.LANGUAGE -> R.string.report30_sec_language
    ReportSection.STORAGE -> R.string.report30_sec_storage
    ReportSection.SETTINGS -> R.string.report30_sec_settings
    ReportSection.INTEGRATIONS -> R.string.report30_sec_integrations
    ReportSection.COUNTS -> R.string.report30_sec_counts
    ReportSection.HEALTH -> R.string.report30_sec_health
    ReportSection.HISTORY -> R.string.report30_sec_history
    ReportSection.CRASH -> R.string.report30_sec_crash
}

@Composable
private fun sectionDescription(section: ReportSection): String = when (section) {
    ReportSection.APP -> stringResource(R.string.report30_sec_app_desc)
    ReportSection.DEVICE -> stringResource(R.string.report30_sec_device_desc)
    ReportSection.LANGUAGE -> stringResource(R.string.report30_sec_language_desc)
    ReportSection.STORAGE -> stringResource(R.string.report30_sec_storage_desc)
    ReportSection.SETTINGS -> stringResource(R.string.report30_sec_settings_desc)
    ReportSection.INTEGRATIONS -> stringResource(R.string.report30_sec_integrations_desc)
    ReportSection.COUNTS -> stringResource(R.string.report30_sec_counts_desc)
    ReportSection.HEALTH -> stringResource(R.string.report30_sec_health_desc)
    ReportSection.HISTORY -> pluralStringResource(R.plurals.report30_sec_history_desc, ProblemReport.HISTORY_LINES, ProblemReport.HISTORY_LINES)
    ReportSection.CRASH -> stringResource(R.string.report30_sec_crash_desc)
}
