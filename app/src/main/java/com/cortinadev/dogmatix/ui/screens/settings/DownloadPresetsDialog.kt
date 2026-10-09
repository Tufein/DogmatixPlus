package com.cortinadev.dogmatix.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.DownloadPolicy
import com.cortinadev.dogmatix.util.DownloadPreset
import com.cortinadev.dogmatix.util.DownloadPresetOptions
import com.cortinadev.dogmatix.util.DownloadPresets
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadPresetsDialog(onDismiss: () -> Unit, viewModel: DownloadPresetsViewModel = hiltViewModel()) {
    val snapshot by viewModel.snapshot.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    var editing by remember { mutableStateOf<DownloadPreset?>(null) }
    if (editing != null) {
        DownloadPresetEditor(editing!!, saving = busy, errorMessage = message?.takeIf { it == R.string.presets26_failed },
            onSave = { preset -> viewModel.save(preset) { editing = null } },
            onDismiss = { if (!busy) editing = null })
        return
    }
    val focus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss), onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.presets26_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.presets26_hint), style = MaterialTheme.typography.bodySmall)
                message?.let { Text(stringResource(it), color = if (it == R.string.presets26_failed)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                snapshot?.let { state ->
                    Text(stringResource(R.string.presets26_current), style = MaterialTheme.typography.titleSmall)
                    PresetSummary(state.current)
                    DialogButton(stringResource(R.string.presets26_save_current), {
                        editing = DownloadPreset(UUID.randomUUID().toString(), "", state.current)
                    }, enabled = !busy && state.presets.count { !it.builtIn } < DownloadPresets.MAX_CUSTOM)
                    state.presets.forEach { preset ->
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(presetName(preset), style = MaterialTheme.typography.titleSmall)
                            PresetSummary(preset.options)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                DialogButton(stringResource(R.string.pad_apply), { viewModel.apply(preset.id) }, enabled = !busy)
                                DialogButton(stringResource(R.string.presets26_edit), { editing = preset }, enabled = !busy)
                                if (!preset.builtIn) DialogButton(stringResource(R.string.presets26_delete),
                                    { viewModel.delete(preset.id) }, enabled = !busy)
                            }
                        }
                    }
                } ?: Text(stringResource(R.string.presets26_loading))
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.settings_done), onDismiss, initialFocus = focus) }
    )
}

@Composable
internal fun presetName(preset: DownloadPreset): String = when (preset.id) {
    DownloadPresets.DAYTIME_ID -> stringResource(R.string.presets26_daytime)
    DownloadPresets.NIGHT_ID -> stringResource(R.string.presets26_night)
    else -> preset.name
}

@Composable
private fun PresetSummary(options: DownloadPresetOptions) {
    val speed = if (options.limitSpeed == Float.POSITIVE_INFINITY) stringResource(R.string.settings_unrestricted)
        else stringResource(R.string.presets26_speed_value, options.limitSpeed.toInt())
    Text(stringResource(R.string.presets26_summary, speed, options.concurrentDownloads),
        style = MaterialTheme.typography.bodySmall)
    val conditions = buildList {
        if (options.wifiOnly) add(stringResource(R.string.presets26_wifi))
        if (options.chargingOnly) add(stringResource(R.string.presets26_charging))
        if (options.nightOnly) add(stringResource(R.string.presets26_window,
            DownloadPolicy.formatMinutes(options.nightStart), DownloadPolicy.formatMinutes(options.nightEnd)))
        if (options.speedLimitDayOnly && options.limitSpeed.isFinite()) add(stringResource(R.string.presets26_day_limit))
    }
    if (conditions.isNotEmpty()) Text(conditions.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Controller buttons remain focusable, and the content scrolls at large Android text sizes. */
@Composable
internal fun DownloadPresetEditor(
    preset: DownloadPreset, onSave: (DownloadPreset) -> Unit, onDismiss: () -> Unit, saving: Boolean = false,
    errorMessage: Int? = null
) {
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var options by remember(preset.id) { mutableStateOf(preset.options.bounded()) }
    val valid = preset.builtIn || name.trim().length in 1..DownloadPresets.MAX_NAME
    val focus = rememberInitialFocus()
    AlertDialog(modifier = Modifier.closeOnGamepadB(onDismiss), onDismissRequest = onDismiss,
        title = { Text(if (preset.builtIn) presetName(preset) else stringResource(R.string.presets26_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!preset.builtIn) OutlinedTextField(name, { name = it.take(DownloadPresets.MAX_NAME) },
                    label = { Text(stringResource(R.string.presets26_name)) }, singleLine = true,
                    enabled = !saving, isError = name.isNotEmpty() && !valid)
                Text(stringResource(R.string.presets26_edit_hint), style = MaterialTheme.typography.bodySmall)
                errorMessage?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                PresetStepper(stringResource(R.string.settings_limit_label),
                    if (options.limitSpeed == Float.POSITIVE_INFINITY) stringResource(R.string.settings_unrestricted)
                    else stringResource(R.string.presets26_speed_value, options.limitSpeed.toInt()),
                    { options = options.copy(limitSpeed = shiftSpeed(options.limitSpeed, -1)) },
                    { options = options.copy(limitSpeed = shiftSpeed(options.limitSpeed, 1)) })
                PresetStepper(stringResource(R.string.settings_concurrent_label), options.concurrentDownloads.toString(),
                    { options = options.copy(concurrentDownloads = (options.concurrentDownloads - 1).coerceAtLeast(1)) },
                    { options = options.copy(concurrentDownloads = (options.concurrentDownloads + 1).coerceAtMost(10)) })
                PresetStepper(stringResource(R.string.settings_per_server),
                    if (options.perServerLimit == 0) stringResource(R.string.settings_off) else options.perServerLimit.toString(),
                    { options = options.copy(perServerLimit = (options.perServerLimit - 1).coerceAtLeast(0)) },
                    { options = options.copy(perServerLimit = (options.perServerLimit + 1).coerceAtMost(10)) })
                PresetToggle(stringResource(R.string.presets26_wifi), options.wifiOnly) { options = options.copy(wifiOnly = it) }
                PresetToggle(stringResource(R.string.presets26_charging), options.chargingOnly) { options = options.copy(chargingOnly = it) }
                PresetToggle(stringResource(R.string.presets26_night_only), options.nightOnly) { options = options.copy(nightOnly = it) }
                PresetStepper(stringResource(R.string.presets26_start), DownloadPolicy.formatMinutes(options.nightStart),
                    { options = options.copy(nightStart = DownloadPolicy.shift(options.nightStart, -1)) },
                    { options = options.copy(nightStart = DownloadPolicy.shift(options.nightStart, 1)) })
                PresetStepper(stringResource(R.string.presets26_end), DownloadPolicy.formatMinutes(options.nightEnd),
                    { options = options.copy(nightEnd = DownloadPolicy.shift(options.nightEnd, -1)) },
                    { options = options.copy(nightEnd = DownloadPolicy.shift(options.nightEnd, 1)) })
                if (options.limitSpeed.isFinite()) PresetToggle(stringResource(R.string.presets26_day_limit),
                    options.speedLimitDayOnly) { options = options.copy(speedLimitDayOnly = it) }
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.dialog_save),
            { onSave(preset.copy(name = name.trim(), options = options.bounded())) }, enabled = valid && !saving) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onDismiss, enabled = !saving, initialFocus = focus) }
    )
}

@Composable
private fun PresetStepper(label: String, value: String, decrement: () -> Unit, increment: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Stepper(value, decrement, increment)
    }
}

@Composable
private fun PresetToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        ThemedSwitch(checked, onChange)
    }
}

private fun shiftSpeed(speed: Float, delta: Int): Float {
    val current = if (speed == Float.POSITIVE_INFINITY) 0 else speed.toInt()
    val next = (current + delta * 250).coerceIn(0, DownloadPresets.MAX_SPEED_KB.toInt())
    return if (next == 0) Float.POSITIVE_INFINITY else next.toFloat()
}
