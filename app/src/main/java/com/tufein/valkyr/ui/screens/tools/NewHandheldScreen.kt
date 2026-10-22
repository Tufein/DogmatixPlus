package com.tufein.valkyr.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.ProgressRing
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.pillColors
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.settings.SettingsJump
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.NewHandheldPlan
import com.tufein.valkyr.util.NewHandheldPlan.Action
import com.tufein.valkyr.util.NewHandheldPlan.Detail
import com.tufein.valkyr.util.NewHandheldPlan.Mark
import com.tufein.valkyr.util.NewHandheldPlan.State
import com.tufein.valkyr.util.NewHandheldPlan.Step
import com.tufein.valkyr.util.NewHandheldPlan.StepResult
import com.tufein.valkyr.util.SettingKeys

/** 3.5.0: "Set up a new handheld" (Tools, and the hint on an empty library). */
const val NEW_HANDHELD_ROUTE = "new_handheld"

private fun Step.titleRes(): Int = when (this) {
    Step.SOURCES -> R.string.wizard35_step_sources
    Step.LIBRARY -> R.string.wizard35_step_library
    Step.EMULATORS -> R.string.wizard35_step_emulators
    Step.GAMES -> R.string.wizard35_step_games
    Step.SAVES -> R.string.wizard35_step_saves
    Step.LAUNCHER -> R.string.wizard35_step_launcher
}

private fun Step.icon(): Int = when (this) {
    Step.SOURCES -> R.drawable.ic_restore
    Step.LIBRARY -> R.drawable.ic_folder_open
    Step.EMULATORS -> R.drawable.ic_controller
    Step.GAMES -> R.drawable.ic_library
    Step.SAVES -> R.drawable.ic_cloud_sync
    Step.LAUNCHER -> R.drawable.ic_frontends
}

private fun State.labelRes(): Int = when (this) {
    State.TODO -> R.string.wizard35_state_todo
    State.DONE -> R.string.wizard35_state_done
    State.SKIPPED -> R.string.wizard35_state_skipped
    State.NOT_NEEDED -> R.string.wizard35_state_not_needed
}

private fun State.tone(): PillTone = when (this) {
    State.TODO -> PillTone.Warning
    State.DONE -> PillTone.Success
    State.SKIPPED -> PillTone.Neutral
    State.NOT_NEEDED -> PillTone.Info
}

private fun State.icon(): Int = when (this) {
    State.TODO -> R.drawable.ic_radio_off
    State.DONE -> R.drawable.ic_check_circle
    State.SKIPPED -> R.drawable.ic_arrow_forward
    State.NOT_NEEDED -> R.drawable.ic_remove
}

private fun Action.icon(): Int = when (this) {
    Action.RESTORE_FILE -> R.drawable.ic_restore
    Action.CLOUD_BACKUP -> R.drawable.ic_cloud_download
    Action.OPEN_SOURCES -> R.drawable.ic_globe
    Action.PICK_FOLDER -> R.drawable.ic_folder_open
    Action.SCAN_LIBRARY -> R.drawable.ic_sync
    Action.LIBRARY_OVERVIEW -> R.drawable.ic_dashboard
    Action.EMULATOR_CHECK -> R.drawable.ic_controller
    Action.EMULATOR_SETTINGS -> R.drawable.ic_settings
    Action.DOWNLOAD_FAVOURITES -> R.drawable.ic_download
    Action.DOWNLOAD_COLLECTION -> R.drawable.ic_download
    Action.OPEN_COLLECTIONS -> R.drawable.ic_collections
    Action.OPEN_WISHLIST -> R.drawable.ic_wishlist
    Action.COPY_GAMES -> R.drawable.ic_file_export
    Action.OPEN_ROMM -> R.drawable.ic_server
    Action.OPEN_SAVE_SYNC -> R.drawable.ic_cloud_sync
    Action.SYNC_SAVES -> R.drawable.ic_sync
    Action.FRONTEND_CHECK -> R.drawable.ic_frontends
    Action.FRONTEND_COVERS -> R.drawable.ic_image
}

/** A button's label; existing screens keep the names they have everywhere else. */
@Composable
private fun actionLabel(action: Action, step: StepResult, collection: String?): String = when (action) {
    Action.RESTORE_FILE -> stringResource(R.string.wizard35_act_restore_file)
    Action.CLOUD_BACKUP -> stringResource(R.string.wizard35_act_cloud)
    Action.OPEN_SOURCES -> stringResource(R.string.nav_sources)
    Action.PICK_FOLDER -> stringResource(R.string.wizard35_act_pick_folder)
    Action.SCAN_LIBRARY -> stringResource(R.string.wizard35_act_scan)
    Action.LIBRARY_OVERVIEW -> stringResource(R.string.settings_overview)
    Action.EMULATOR_CHECK -> step.missingEmulators.firstOrNull()?.let { stringResource(R.string.wizard35_act_check_console, it.name) }
        ?: stringResource(R.string.ready25_title)
    Action.EMULATOR_SETTINGS -> stringResource(R.string.play24_settings_title)
    Action.DOWNLOAD_FAVOURITES -> stringResource(R.string.wizard35_act_download_favourites)
    Action.DOWNLOAD_COLLECTION -> collection?.let { stringResource(R.string.wizard35_act_download_collection, it) } ?: stringResource(R.string.nav_collections)
    Action.OPEN_COLLECTIONS -> stringResource(R.string.nav_collections)
    Action.OPEN_WISHLIST -> stringResource(R.string.nav_wishlist)
    Action.COPY_GAMES -> stringResource(R.string.transfer31_title)
    Action.OPEN_ROMM -> stringResource(R.string.wizard35_act_romm)
    Action.OPEN_SAVE_SYNC -> stringResource(R.string.nav_save_sync)
    Action.SYNC_SAVES -> stringResource(R.string.save_sync_now)
    Action.FRONTEND_CHECK -> stringResource(R.string.nav_frontends)
    Action.FRONTEND_COVERS -> stringResource(R.string.covers32_title)
}

/** The step's one-line explanation, in the user's language. */
@Composable
private fun detailText(r: StepResult): String = when (r.detail) {
    Detail.SOURCES_NONE -> stringResource(R.string.wizard35_d_sources_none)
    Detail.SOURCES_OK -> pluralStringResource(R.plurals.wizard35_d_sources_ok, r.a, r.a)
    Detail.SOURCES_CLOUD -> stringResource(R.string.wizard35_d_sources_cloud)
    Detail.LIBRARY_NO_FOLDER -> stringResource(R.string.wizard35_d_library_no_folder)
    Detail.LIBRARY_EMPTY -> stringResource(R.string.wizard35_d_library_empty)
    Detail.LIBRARY_UNMATCHED -> stringResource(R.string.wizard35_d_library_unmatched)
    Detail.LIBRARY_OK -> pluralStringResource(R.plurals.wizard35_d_library_ok, r.a, r.a)
    Detail.EMULATORS_WAITING -> stringResource(R.string.wizard35_d_emulators_waiting)
    Detail.EMULATORS_UNCHECKED -> stringResource(R.string.wizard35_d_emulators_unchecked)
    Detail.EMULATORS_MISSING -> stringResource(R.string.wizard35_d_emulators_missing, r.a, r.b)
    Detail.EMULATORS_OK -> stringResource(R.string.wizard35_d_emulators_ok)
    Detail.GAMES_WAITING -> stringResource(R.string.wizard35_d_games_waiting)
    Detail.GAMES_NOTHING -> stringResource(R.string.wizard35_d_games_nothing)
    Detail.GAMES_MISSING -> pluralStringResource(R.plurals.wizard35_d_games_missing, r.a, r.a)
    Detail.GAMES_OK -> stringResource(R.string.wizard35_d_games_ok)
    Detail.SAVES_NO_ROMM -> stringResource(R.string.wizard35_d_saves_no_romm)
    Detail.SAVES_NO_FOLDERS -> stringResource(R.string.wizard35_d_saves_no_folders)
    Detail.SAVES_READY -> stringResource(R.string.wizard35_d_saves_ready)
    Detail.SAVES_RUNNING -> stringResource(R.string.wizard35_d_saves_running)
    Detail.SAVES_CONFLICTS -> pluralStringResource(R.plurals.wizard35_d_saves_conflicts, r.a, r.a)
    Detail.SAVES_ERROR -> stringResource(R.string.wizard35_d_saves_error)
    Detail.SAVES_OK -> stringResource(R.string.wizard35_d_saves_ok)
    Detail.SAVES_AUTO -> stringResource(R.string.wizard35_d_saves_auto)
    Detail.LAUNCHER_TODO -> stringResource(R.string.wizard35_d_launcher_todo)
    Detail.LAUNCHER_OK -> stringResource(R.string.wizard35_d_launcher_ok)
}

@Composable
private fun countText(c: NewHandheldPlan.Count): String = when (c.kind) {
    NewHandheldPlan.CountKind.FAVOURITES -> stringResource(R.string.wizard35_count_favourites, c.missing, c.total)
    NewHandheldPlan.CountKind.COLLECTIONS -> stringResource(R.string.wizard35_count_collections, c.missing, c.total)
    NewHandheldPlan.CountKind.WISHLIST -> stringResource(R.string.wizard35_count_wishlist, c.missing, c.total)
    NewHandheldPlan.CountKind.ROMM -> stringResource(R.string.wizard35_count_romm, c.missing, c.total)
}

/**
 * "Set up a new handheld": one guided checklist from a fresh install to a ready handheld. Every
 * step says where it stands, explains itself in one line and opens the screen or flow that does
 * the work (restore, games folder, readiness check, "Download everything shown", save sync,
 * frontend check). A step can be skipped or ticked off by hand; that is remembered on this
 * handheld, and opening the checklist again puts the D-pad on the first step still to do.
 */
@Composable
fun NewHandheldScreen(navController: NavController, viewModel: NewHandheldViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var confirmStartOver by remember { mutableStateOf(false) }
    val requesters = remember { Step.entries.associateWith { FocusRequester() } }
    var focused by remember { mutableStateOf(false) }

    // Back from another screen or app (an emulator may have been installed): look again.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Continue where the user was: the first step still to do takes the D-pad.
    val loaded = ui != null
    LaunchedEffect(loaded) {
        val shown = ui ?: return@LaunchedEffect
        if (focused) return@LaunchedEffect
        focused = true
        withFrameNanos { }
        runCatching { requesters.getValue(shown.summary.next ?: Step.entries.first()).requestFocus() }
    }
    PublishLegend(
        listOf(LegendEntry("A", stringResource(R.string.pad_open)), LegendEntry("B", stringResource(R.string.pad_back)))
    )

    fun open(route: String) = navController.navigate(route) { launchSingleTop = true }
    fun openSetting(key: String) {
        SettingsJump.request(key)
        open(NavRoutes.Settings.route)
    }
    fun checkEmulator(consoleId: String) = viewModel.emulatorCheck(consoleId) { route ->
        if (route != null) open(route) else openSetting(SettingKeys.EMULATORS)
    }
    fun run(action: Action, step: StepResult) {
        when (action) {
            Action.RESTORE_FILE -> openSetting(SettingKeys.BACKUP_IMPORT)
            Action.CLOUD_BACKUP -> open(NavRoutes.CloudBackup.route)
            Action.OPEN_SOURCES -> open(NavRoutes.Sources.route)
            Action.PICK_FOLDER -> openSetting(SettingKeys.DOWNLOAD_DIR)
            Action.SCAN_LIBRARY -> viewModel.scanLibrary(context)
            Action.LIBRARY_OVERVIEW -> open(NavRoutes.Overview.route)
            Action.EMULATOR_CHECK -> step.missingEmulators.firstOrNull()?.let { checkEmulator(it.consoleId) } ?: openSetting(SettingKeys.EMULATORS)
            Action.EMULATOR_SETTINGS -> openSetting(SettingKeys.EMULATORS)
            // The shell switches to the library, which opens its "Download everything shown" preview.
            Action.DOWNLOAD_FAVOURITES -> viewModel.downloadFavourites()
            Action.DOWNLOAD_COLLECTION -> ui?.collection?.let { viewModel.downloadCollection(it.first) } ?: open(NavRoutes.Collections.route)
            Action.OPEN_COLLECTIONS -> open(NavRoutes.Collections.route)
            Action.OPEN_WISHLIST -> open(NavRoutes.Wishlist.route)
            Action.COPY_GAMES -> open(NavRoutes.GameTransfer.route)
            Action.OPEN_ROMM -> open(NavRoutes.Romm.route)
            Action.OPEN_SAVE_SYNC -> open(NavRoutes.SaveSync.route)
            Action.SYNC_SAVES -> viewModel.syncSaves(context)
            Action.FRONTEND_CHECK -> open(NavRoutes.Frontends.route)
            Action.FRONTEND_COVERS -> open(NavRoutes.FrontendCovers.route)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 12.dp)
    ) {
        // Title and intro wrap rather than cut off (long translations).
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconTile(R.drawable.ic_devices, size = 38.dp)
            Text(stringResource(R.string.wizard35_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            stringResource(R.string.wizard35_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        val shown = ui
        if (shown == null) {
            InfoCard(listOf(stringResource(R.string.wizard35_loading)), icon = R.drawable.ic_hourglass)
            return@Column
        }
        SummaryPanel(shown, onStartOver = { confirmStartOver = true })
        val total = shown.steps.size
        shown.steps.forEachIndexed { index, r ->
            StepCard(
                number = index + 1,
                total = total,
                r = r,
                current = r.step == shown.summary.next,
                lines = buildList {
                    add(detailText(r))
                    r.counts.forEach { add(countText(it)) }
                    if (r.step == Step.GAMES) add(stringResource(R.string.wizard35_copy_pointer))
                    if (r.markApplies) add(stringResource(if (r.mark == Mark.DONE) R.string.wizard35_marked_done else R.string.wizard35_marked_skipped))
                },
                label = { actionLabel(it, r, shown.collection?.second) },
                onAction = { run(it, r) },
                onMark = { viewModel.setMark(r.step, it) },
                focusRequester = requesters.getValue(r.step)
            ) {
                if (r.step == Step.EMULATORS && r.missingEmulators.isNotEmpty()) {
                    MissingEmulators(r.missingEmulators, onCheck = ::checkEmulator)
                }
            }
        }
    }

    if (confirmStartOver) {
        val cancelFocus = rememberInitialFocus()
        val dismiss = { confirmStartOver = false }
        AlertDialog(
            modifier = Modifier.closeOnGamepadB(dismiss),
            onDismissRequest = dismiss,
            icon = { Icon(painterResource(R.drawable.ic_restart_alt), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.wizard35_start_over_title)) },
            text = { Text(stringResource(R.string.wizard35_start_over_text)) },
            confirmButton = {
                DialogButton(stringResource(R.string.wizard35_start_over), onClick = {
                    confirmStartOver = false
                    viewModel.startOver()
                })
            },
            dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = dismiss, initialFocus = cancelFocus) }
        )
    }
}

@Composable
private fun SummaryPanel(ui: NewHandheldUi, onStartOver: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val summary = ui.summary
    val headline = if (summary.ready) stringResource(R.string.wizard35_summary_ready)
        else stringResource(R.string.wizard35_summary_steps, summary.settled, summary.total)
    val next = summary.next?.let { stringResource(it.titleRes()) }
    val sub = when {
        next == null -> stringResource(R.string.wizard35_summary_all_settled)
        summary.ready -> stringResource(R.string.wizard35_summary_optional_left, next)
        else -> stringResource(R.string.wizard35_summary_next, next)
    }
    Panel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProgressRing(fraction = summary.fraction, size = 76.dp, stroke = 8.dp, color = if (summary.ready) scheme.tertiary else scheme.primary) {
                Text("${summary.settled}/${summary.total}", style = MaterialTheme.typography.titleMedium.tabular(), color = scheme.onSurface)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        ToolsActions(horizontalPadding = 0.dp, modifier = Modifier.padding(top = 10.dp)) {
            ActionPill(stringResource(R.string.wizard35_start_over), onStartOver, icon = R.drawable.ic_restart_alt)
        }
    }
}

/**
 * One step: its number, title, status and explanation, then its actions (the main one first,
 * accented while the step is still to do) and, while the app sees it as to do, Skip and
 * "Mark as done" (or Undo once the user did one of them).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCard(
    number: Int,
    total: Int,
    r: StepResult,
    current: Boolean,
    lines: List<String>,
    label: @Composable (Action) -> String,
    onAction: (Action) -> Unit,
    onMark: (Mark?) -> Unit,
    focusRequester: FocusRequester,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val (tileBg, tileFg) = pillColors(r.state.tone())
    Panel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        tone = if (current) PanelTone.Raised else PanelTone.Normal,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(r.step.icon(), size = 36.dp, container = tileBg, tint = tileFg)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.wizard35_step_of, number, total),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (current) scheme.primary else scheme.onSurfaceVariant
                )
                // Title and status wrap onto a second line on a narrow screen instead of squeezing.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(r.step.titleRes()), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                    Pill(stringResource(r.state.labelRes()), tone = r.state.tone(), icon = r.state.icon())
                    if (r.step.optional) Pill(stringResource(R.string.wizard35_optional))
                }
                lines.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        extra()
        ToolsActions(horizontalPadding = 0.dp, modifier = Modifier.padding(top = 10.dp)) {
            ActionPill(
                label(r.primary), { onAction(r.primary) },
                modifier = Modifier.focusRequester(focusRequester),
                icon = r.primary.icon(),
                tone = if (r.state == State.TODO) ActionTone.Accent else ActionTone.Neutral
            )
            r.more.forEach { action -> ActionPill(label(action), { onAction(action) }, icon = action.icon()) }
            if (r.auto == State.TODO) {
                if (r.markApplies) {
                    ActionPill(stringResource(R.string.wizard35_undo), { onMark(null) }, icon = R.drawable.ic_retry)
                } else {
                    ActionPill(stringResource(R.string.wizard35_skip), { onMark(Mark.SKIPPED) }, icon = R.drawable.ic_arrow_forward)
                    ActionPill(stringResource(R.string.wizard35_mark_done), { onMark(Mark.DONE) }, icon = R.drawable.ic_check)
                }
            }
        }
    }
}

/** The consoles without an emulator, each with the readiness check of one of its games. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MissingEmulators(consoles: List<NewHandheldPlan.ConsoleEmulators>, onCheck: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(start = 48.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        consoles.forEach { c ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    Text(
                        listOfNotNull(
                            if (c.hasGames) null else stringResource(R.string.wizard35_emu_wanted),
                            c.suggestion?.let { stringResource(R.string.wizard35_emu_suggestion, it) } ?: stringResource(R.string.wizard35_emu_none)
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                ActionPill(stringResource(R.string.wizard35_emu_check), { onCheck(c.consoleId) }, icon = R.drawable.ic_controller)
            }
        }
    }
}
