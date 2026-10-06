package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.SmartStorageSettings
import com.cortinadev.dogmatix.data.service.SmartPreview
import com.cortinadev.dogmatix.data.service.SmartProblem
import com.cortinadev.dogmatix.data.service.SmartRunState
import com.cortinadev.dogmatix.data.service.SmartStorageScheduler
import com.cortinadev.dogmatix.data.service.SmartStorageService
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.MeterBar
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.screens.settings.ThemedSwitch
import com.cortinadev.dogmatix.ui.screens.sources.components.ConfirmDialog
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.SmartStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * 8.0 smart storage, as a section of the Storage tool: the switch, the folder on the SD card, how
 * many days count as recent, the weekly run, a dry run ("would move …") and "Move now" (after a
 * confirmation that lists the consoles). Whole consoles move, see SmartStorage.
 */

/** The settings of smart storage as the section shows them. */
data class SmartSettingsUi(
    val enabled: Boolean = false,
    val sdUri: String = "",
    val days: Int = SmartStorage.DEFAULT_RECENT_DAYS,
    val weekly: Boolean = true,
    val last: SmartStorage.RunInfo? = null
)

@HiltViewModel
class SmartStorageViewModel @Inject constructor(
    private val settings: SmartStorageSettings,
    private val service: SmartStorageService,
    // Injected so the weekly job follows the settings even before the application wires it.
    @Suppress("unused") private val scheduler: SmartStorageScheduler
) : ViewModel() {

    val prefs: StateFlow<SmartSettingsUi> = combine(settings.enabled, settings.sdUri, settings.recentDays, settings.weekly, settings.lastRun) { on, sd, days, weekly, last ->
        SmartSettingsUi(on, sd, days, weekly, last)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SmartSettingsUi())

    val run: StateFlow<SmartRunState> = service.state

    private val _preview = MutableStateFlow<SmartPreview?>(null)
    /** The last dry run; null before one. */
    val preview: StateFlow<SmartPreview?> = _preview.asStateFlow()
    private val _previewing = MutableStateFlow(false)
    val previewing: StateFlow<Boolean> = _previewing.asStateFlow()
    private var previewJob: Job? = null

    init {
        viewModelScope.launch { if (settings.enabled.first() && settings.sdUri.first().isNotBlank()) refreshPreview() }
    }

    fun setEnabled(on: Boolean) = viewModelScope.launch { settings.setEnabled(on); if (on && _preview.value == null) refreshPreview() }
    fun setSdUri(uri: String) = viewModelScope.launch { settings.setSdUri(uri); refreshPreview() }
    fun setWeekly(on: Boolean) = viewModelScope.launch { settings.setWeekly(on) }
    fun setDays(days: Int) = viewModelScope.launch {
        settings.setRecentDays(days)
        if (_preview.value != null) refreshPreview()
    }

    fun refreshPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            _previewing.value = true
            _preview.value = try { service.preview() } catch (e: CancellationException) { throw e } catch (e: Exception) { SmartPreview(problem = SmartProblem.NO_LIBRARY) }
            _previewing.value = false
        }
    }

    /** Runs exactly the moves of the confirmed preview (those still valid; nothing is added). */
    fun moveNow(confirmed: SmartStorage.Plan) = service.start(confirmed.moves.associate { it.console.id to it.to })
    fun stop() = service.cancel()
    fun dismiss() { service.dismiss(); refreshPreview() }
}

/** What [rememberSmartStorage] hands to the Storage screen. */
@Stable
class SmartStorageUi internal constructor(
    internal val vm: SmartStorageViewModel,
    internal val prefs: SmartSettingsUi,
    internal val run: SmartRunState,
    internal val preview: SmartPreview?,
    internal val previewing: Boolean,
    internal val pickFolder: () -> Unit,
    internal val askMove: () -> Unit
)

/** Connects the Storage screen to smart storage and shows its folder picker and confirmation. Call once. */
@Composable
fun rememberSmartStorage(): SmartStorageUi {
    val vm: SmartStorageViewModel = hiltViewModel()
    val prefs by vm.prefs.collectAsState()
    val run by vm.run.collectAsState()
    val preview by vm.preview.collectAsState()
    val previewing by vm.previewing.collectAsState()
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.setSdUri(it.toString())
        }
    }
    val plan = preview?.plan
    if (confirm && plan != null && plan.moves.isNotEmpty()) {
        ConfirmDialog(
            title = stringResource(R.string.store8_confirm_title),
            message = stringResource(R.string.store8_confirm_message) + "\n\n" + plan.moves.joinToString("\n") { m ->
                "• " + ConsoleFormatter.getConsoleDisplayName(m.console.id) + " → " +
                    (if (m.to == SmartStorage.Place.SD) context.getString(R.string.store8_to_sd) else context.getString(R.string.store8_to_internal)) +
                    " (" + formatBytes(m.console.bytes) + ")"
            },
            confirmText = stringResource(R.string.store8_confirm_ok),
            onConfirm = { confirm = false; vm.moveNow(plan) },
            onDismiss = { confirm = false },
            icon = R.drawable.ic_drive_file_move
        )
    }
    return remember(vm, prefs, run, preview, previewing) {
        SmartStorageUi(vm, prefs, run, preview, previewing, pickFolder = { picker.launch(null) }, askMove = {
            if (vm.preview.value?.plan?.moves?.isNotEmpty() == true) confirm = true else vm.refreshPreview()
        })
    }
}

/** The smart storage block of the Storage tool. [onFocus] tells the screen a row of it has the focus (for the legend). */
fun LazyListScope.smartStorageItems(smart: SmartStorageUi, onFocus: () -> Unit) {
    val prefs = smart.prefs
    val focus = Modifier.onFocusChanged { if (it.isFocused) onFocus() }
    item(key = "smart-head") {
        SectionHeader(stringResource(R.string.store8_title), stringResource(R.string.store8_hint), icon = R.drawable.ic_drive_file_move)
    }
    item(key = "smart-switch") {
        ToolRow(
            title = stringResource(R.string.store8_switch_title),
            lines = listOf(lastLine(prefs)),
            onClick = { smart.vm.setEnabled(!prefs.enabled) },
            modifier = focus,
            icon = R.drawable.ic_storage
        ) { ThemedSwitch(prefs.enabled) { smart.vm.setEnabled(it) } }
    }
    if (!prefs.enabled) return
    item(key = "smart-explain") {
        InfoCard(listOf(stringResource(R.string.store8_explain_title), stringResource(R.string.store8_explain)), icon = R.drawable.ic_info)
    }
    item(key = "smart-sd") {
        ToolRow(
            title = stringResource(R.string.store8_sd_title),
            lines = listOf(prefs.sdUri.takeIf { it.isNotBlank() }?.let { FileParsingUtils.toUserReadablePath(it) } ?: stringResource(R.string.store8_sd_none)),
            onClick = smart.pickFolder,
            modifier = focus,
            icon = R.drawable.ic_folder
        ) { ToolAction(stringResource(R.string.store8_sd_choose), onClick = smart.pickFolder) }
    }
    item(key = "smart-days") {
        ToolRow(
            title = stringResource(R.string.store8_days_title),
            lines = listOf(stringResource(R.string.store8_days_hint, prefs.days)),
            onClick = { smart.vm.setDays(prefs.days + SmartStorage.RECENT_DAYS_STEP) },
            modifier = focus,
            icon = R.drawable.ic_history
        ) {
            Stepper(
                prefs.days.toString(),
                onDecrement = { smart.vm.setDays(prefs.days - SmartStorage.RECENT_DAYS_STEP) },
                onIncrement = { smart.vm.setDays(prefs.days + SmartStorage.RECENT_DAYS_STEP) }
            )
        }
    }
    item(key = "smart-weekly") {
        ToolRow(
            title = stringResource(R.string.store8_weekly_title),
            lines = listOf(stringResource(R.string.store8_weekly_hint, formatBytes(SmartStorage.AUTO_RUN_BUDGET_BYTES))),
            onClick = { smart.vm.setWeekly(!prefs.weekly) },
            modifier = focus,
            icon = R.drawable.ic_retry
        ) { ThemedSwitch(prefs.weekly) { smart.vm.setWeekly(it) } }
    }
    item(key = "smart-run") { RunRow(smart, focus) }
    val plan = smart.preview?.plan
    if (plan != null && !smart.run.running) {
        items(plan.moves, key = { "smart-m|" + it.console.id }) { move -> MoveRow(move, null, focus) }
        items(plan.waiting, key = { "smart-w|" + it.move.console.id }) { w -> MoveRow(w.move, w.wait, focus) }
    }
}

@Composable
private fun lastLine(prefs: SmartSettingsUi): String {
    if (!prefs.enabled) return stringResource(R.string.store8_switch_hint)
    val last = prefs.last ?: return stringResource(R.string.store8_last_none)
    val moved = pluralStringResource(R.plurals.store8_done, last.moved, last.moved)
    return stringResource(R.string.store8_last, DateUtils.getRelativeTimeSpanString(last.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(), moved)
}

/** The preview / progress / outcome row with its actions. */
@Composable
private fun RunRow(smart: SmartStorageUi, focus: Modifier) {
    val run = smart.run
    val preview = smart.preview
    val plan = preview?.plan
    val lines = when {
        run.running -> listOf(
            stringResource(
                R.string.store8_running, run.consoleId?.let { ConsoleFormatter.getConsoleDisplayName(it) }.orEmpty(),
                run.consoleIndex + 1, run.consoleCount, formatBytes(run.bytesDone), formatBytes(run.bytesTotal)
            )
        )
        run.finished && run.problem != null -> listOf(problemText(run.problem))
        run.finished -> listOfNotNull(
            pluralStringResource(R.plurals.store8_done, run.moved, run.moved),
            run.failed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.store8_failed, it, it) },
            run.esdeUnchanged.takeIf { it.isNotEmpty() }?.let { ids ->
                stringResource(R.string.store8_esde_unchanged, ids.joinToString(", ") { ConsoleFormatter.getConsoleDisplayName(it) })
            }
        )
        smart.previewing -> listOf(stringResource(R.string.tools_scanning))
        preview?.problem != null -> listOf(problemText(preview.problem))
        plan == null -> listOf(stringResource(R.string.store8_preview_hint))
        plan.moves.isEmpty() -> listOf(stringResource(R.string.store8_preview_nothing))
        else -> listOf(
            if (plan.freesInternal >= 0) pluralStringResource(R.plurals.store8_preview_frees, plan.moves.size, plan.moves.size, formatBytes(plan.freesInternal))
            else pluralStringResource(R.plurals.store8_preview_takes, plan.moves.size, plan.moves.size, formatBytes(-plan.freesInternal))
        )
    }
    val onClick: () -> Unit = when {
        run.running -> smart.vm::stop
        run.finished -> smart.vm::dismiss
        plan != null && plan.moves.isNotEmpty() -> smart.askMove
        else -> smart.vm::refreshPreview
    }
    ToolRow(
        title = stringResource(if (run.running) R.string.store8_moving_title else R.string.store8_preview_title),
        lines = lines,
        onClick = onClick,
        modifier = focus,
        icon = R.drawable.ic_drive_file_move,
        below = if (run.running && run.bytesTotal > 0) ({
            MeterBar(run.bytesDone.toFloat() / run.bytesTotal, modifier = Modifier.padding(top = 6.dp))
        }) else null
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                run.running -> ToolAction(stringResource(R.string.store8_stop), tone = ActionTone.Danger, onClick = smart.vm::stop)
                run.finished -> ToolAction(stringResource(R.string.store8_ok), onClick = smart.vm::dismiss)
                else -> {
                    ToolAction(stringResource(R.string.store8_preview_action), icon = R.drawable.ic_retry, onClick = smart.vm::refreshPreview)
                    if (plan != null && plan.moves.isNotEmpty()) ToolAction(stringResource(R.string.store8_move_now), tone = ActionTone.Accent, onClick = smart.askMove)
                }
            }
        }
    }
}

/** One console of the dry run: where it goes and why, or why it waits. */
@Composable
private fun MoveRow(move: SmartStorage.Move, wait: SmartStorage.Wait?, focus: Modifier) {
    val c = move.console
    val where = stringResource(if (move.to == SmartStorage.Place.SD) R.string.store8_to_sd else R.string.store8_to_internal)
    val why = stringResource(
        when (move.reason) {
            SmartStorage.Reason.COLD -> R.string.store8_reason_cold
            SmartStorage.Reason.PLAYED -> R.string.store8_reason_played
            SmartStorage.Reason.FAVOURITE -> R.string.store8_reason_favourite
        }
    )
    val lines = listOfNotNull(
        "$where · ${formatBytes(c.bytes)} · ${pluralStringResource(R.plurals.tools_files, c.files, c.files)}",
        why,
        if (wait == null && c.largestFile > SmartStorage.AUTO_MAX_FILE_BYTES) stringResource(R.string.store8_wait_big_file, formatBytes(SmartStorage.AUTO_MAX_FILE_BYTES)) else null,
        wait?.let {
            stringResource(
                when (it) {
                    SmartStorage.Wait.BUSY -> R.string.store8_wait_busy
                    SmartStorage.Wait.RESTING -> R.string.store8_wait_resting
                    SmartStorage.Wait.NO_ROOM -> R.string.store8_wait_no_room
                    SmartStorage.Wait.LATER -> R.string.store8_wait_later
                    SmartStorage.Wait.BIG_FILE -> R.string.store8_wait_big_file_short
                }
            )
        }
    )
    ToolRow(
        title = ConsoleFormatter.getConsoleDisplayName(c.id),
        lines = lines,
        onClick = {},
        modifier = focus.padding(start = 12.dp),
        leading = { ConsoleTile(c.id) },
        badge = if (wait != null) ({ Badge(stringResource(R.string.store8_waits), warning = false) }) else null
    )
}

@Composable
private fun problemText(problem: SmartProblem): String = stringResource(
    when (problem) {
        SmartProblem.NO_SD -> R.string.store8_problem_no_sd
        SmartProblem.NO_LIBRARY -> R.string.store8_problem_no_library
        SmartProblem.SAME_STORAGE -> R.string.store8_problem_same
        SmartProblem.BUSY -> R.string.store8_problem_busy
        SmartProblem.NO_PLAY_DATA -> R.string.store8_problem_no_play
    }
)
