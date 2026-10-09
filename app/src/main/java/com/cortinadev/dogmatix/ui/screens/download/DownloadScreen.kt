package com.cortinadev.dogmatix.ui.screens.download

import com.cortinadev.dogmatix.util.DownloadSourcePolicy
import com.cortinadev.dogmatix.util.QueueActions
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.EmptyState
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.PanelTone
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.util.WaitReason
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.common.Legend
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus

@Composable
fun DownloadScreen(
    navController: NavController,
    viewModel: DownloadViewModel = hiltViewModel()
) {
    val downloads by viewModel.downloads.collectAsState()
    val queueView by viewModel.queueView.collectAsState()
    val filterCriteria by viewModel.queueFilters.collectAsState()
    val details by viewModel.downloadDetails.collectAsState()
    val uploads by viewModel.uploads.collectAsState()
    val waitingFiles by viewModel.waitingFiles.collectAsState()
    val waitingReasons by viewModel.waitingReasons.collectAsState()
    val queuePositions by viewModel.queuePositions.collectAsState()
    val itemWaits by viewModel.itemWaits.collectAsState()
    val itemConditions by viewModel.itemConditions.collectAsState()
    val pendingRetries by viewModel.pendingAutoRetries.collectAsState()
    val sourceChoice by viewModel.sourceChoice.collectAsState()
    // Names still in line, to offer "Wait for..." on the rows that have not started.
    val queuedSet = queuePositions.keys
    // Rows the "Download when..." dialog is open for (one row, or the ticked ones); null = closed.
    var whenTargets by remember { mutableStateOf<List<String>?>(null) }
    fun notStarted(row: DownloadItemModel) = row.status == DownloadStatus.DOWNLOADING &&
        (row.fileName in queuedSet || row.fileName in waitingFiles || row.fileName in itemWaits)
    val verification by viewModel.verification.collectAsState()
    val shortfall by viewModel.queueShortfall.collectAsState()
    val counts by viewModel.queueCounts.collectAsState()
    val eta by viewModel.queueEta.collectAsState()
    val held by viewModel.held.collectAsState()
    val progress by viewModel.queueProgress.collectAsState()
    val need by viewModel.queueNeed.collectAsState()
    val free by viewModel.freeBytes.collectAsState()
    val waitWifi = stringResource(R.string.wait_wifi)
    val waitCharger = stringResource(R.string.wait_charger)
    val waitNight = stringResource(R.string.wait_night)
    val waitStorage = stringResource(R.string.wait_storage)
    val waitHeld = stringResource(R.string.wait_held)
    val waitBattery = stringResource(R.string.power75_wait_battery)
    val waitHot = stringResource(R.string.power75_wait_hot)
    val waitingText = waitingReasons.joinToString(" · ") {
        when (it) {
            WaitReason.WIFI -> waitWifi
            WaitReason.CHARGER -> waitCharger
            WaitReason.NIGHT -> waitNight
            WaitReason.STORAGE -> waitStorage
            WaitReason.HELD -> waitHeld
            WaitReason.LOW_BATTERY -> waitBattery
            WaitReason.HOT -> waitHot
        }
    }
    // The same reasons in two or three words, for the pill on a waiting row.
    val shortWifi = stringResource(R.string.q5_wait_wifi)
    val shortCharger = stringResource(R.string.q5_wait_charger)
    val shortNight = stringResource(R.string.q5_wait_night)
    val shortStorage = stringResource(R.string.q5_wait_storage)
    val shortHeld = stringResource(R.string.q5_wait_held)
    val shortBattery = stringResource(R.string.power75_wait_battery_short)
    val shortHot = stringResource(R.string.power75_wait_hot_short)
    val waitingShort = waitingReasons.joinToString(" · ") {
        when (it) {
            WaitReason.WIFI -> shortWifi
            WaitReason.CHARGER -> shortCharger
            WaitReason.NIGHT -> shortNight
            WaitReason.STORAGE -> shortStorage
            WaitReason.HELD -> shortHeld
            WaitReason.LOW_BATTERY -> shortBattery
            WaitReason.HOT -> shortHot
        }
    }
    val selection by viewModel.selection.collectAsState()
    val showDeleteConfirmation by viewModel.showDeleteConfirmation.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val selectionMode = selection.isNotEmpty()
    val selected by viewModel.selectedDownloads.collectAsState()

    // Row under the D-pad cursor: SELECT ticks it, X deletes it (the in-row buttons are touch-only —
    // they sit inside the focused row's bounds, out of reach of directional focus search).
    var focusedRow by remember { mutableStateOf<DownloadItemModel?>(null) }
    LaunchedEffect(filterCriteria) { focusedRow = null }
    val barFocus = remember { FocusRequester() }
    var barFocused by remember { mutableStateOf(false) }
    // Clearing the selection takes the bar away under the cursor (B clears it from a row instead,
    // and that row keeps the focus). Park focus on the section tab once the new tree is laid out,
    // or Compose leaves it on the shell's invisible sink and the ring disappears.
    LaunchedEffect(selectionMode) {
        if (!selectionMode && focusedRow == null) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { Gamepad.sectionFocus.requestFocus() }
        }
    }
    LaunchedEffect(selectionMode) {
        Gamepad.presses.collect { button ->
            when (button) {
                // Select ticks the row under the cursor; that is what opens selection mode with a pad.
                GamepadButton.FAVOURITE -> focusedRow?.let { viewModel.toggleSelection(it.fileName) }
                // Y: select all while ticking; otherwise a waiting download jumps to the front of the queue.
                GamepadButton.Y -> if (selectionMode) viewModel.toggleSelectAll() else focusedRow
                    ?.takeIf { it.fileName in queueView.visibleNames }?.let { viewModel.moveToFront(it.fileName) }
                GamepadButton.X -> if (selectionMode) {
                    viewModel.deleteSelected()
                } else if (queueView.criteria == filterCriteria) {
                    // Re-read the live status: it may have changed since the row took focus.
                    queueView.rows.find { it.fileName == focusedRow?.fileName }?.let { row ->
                        if (row.status.canDelete) {
                            viewModel.deleteDownloadWithConfirmation(row.fileName, row.status == DownloadStatus.COMPLETED)
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    // B drops the selection before it hands focus back to the tabs.
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    val section = LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
    val selectionLegend = if (barFocused) listOf(
        LegendEntry("A", stringResource(R.string.pad_apply)),
        LegendEntry("◀ ▶", stringResource(R.string.pad_change)),
        LegendEntry("B", stringResource(R.string.pad_cancel)), section
    ) else listOf(
        LegendEntry("A", stringResource(R.string.pad_tick)),
        LegendEntry("Y", stringResource(R.string.pad_select_all)),
        LegendEntry("X", stringResource(R.string.pad_delete)),
        LegendEntry("B", stringResource(R.string.pad_cancel)), section
    )
    val published = remember(selectionMode, selectionLegend) { if (selectionMode) Legend(selectionLegend) else null }
    LaunchedEffect(published) { Gamepad.legendOverride.value = published }
    // Only clear our own legend: another screen may already have published its own during the transition.
    DisposableEffect(published) { onDispose { if (Gamepad.legendOverride.value === published) Gamepad.legendOverride.value = null } }

    // One animation for every busy bar of the list (a calm segment sliding along the track); it only
    // runs while something is actually busy and stands still when motion is reduced.
    val reduceMotion = LocalReduceMotion.current
    val anyBusy = downloads.any {
        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.COPYING || it.status == DownloadStatus.UNZIPPING
    }
    val sweep: State<Float> = if (anyBusy && !reduceMotion) {
        rememberInfiniteTransition(label = "queue-sweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart),
            label = "queue-sweep"
        )
    } else {
        remember { mutableStateOf(0.5f) }
    }
    val goToLibrary: () -> Unit = {
        navController.navigate(NavRoutes.Home.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp)
    ) {
        // The section name already lives in the tabs; the queue summary heads the list (it scrolls
        // away on a short screen) and gives way to the bulk actions while rows are ticked.
        if (selectionMode) {
            SelectionBar(
                focusRequester = barFocus,
                onFocusChanged = { barFocused = it },
                selected = selected,
                details = details,
                compact = isLandscape,
                onRetry = viewModel::retrySelected,
                onPause = viewModel::pauseSelected,
                onStop = viewModel::stopSelected,
                canStop = selected.any { it.status.canStop || it.fileName in pendingRetries },
                canPrioritize = selected.any { it.fileName in queuedSet },
                onPrioritize = viewModel::moveSelectedToFront,
                canWait = selected.any(::notStarted),
                canStartNow = selected.any { it.fileName in itemWaits },
                onWaitFor = { whenTargets = selected.filter(::notStarted).map { it.fileName } },
                onStartNow = { viewModel.setCondition(selected.filter { it.fileName in itemWaits }.map { it.fileName }, null) },
                onDelete = viewModel::deleteSelected,
                onClear = viewModel::clearSelection
            )
        }
        if (downloads.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                DownloadPlanActions(downloads, selection, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                EmptyState(
                    title = stringResource(R.string.q5_empty_title),
                    message = stringResource(R.string.q5_empty_message),
                    icon = R.drawable.ic_download,
                    actionLabel = stringResource(R.string.q5_go_library),
                    onAction = goToLibrary
                )
            }
        } else {
            val itemPlacement = if (reduceMotion) null else tween<IntOffset>(Motion.MEDIUM, easing = FastOutSlowInEasing)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "queue-plan") {
                    DownloadPlanActions(downloads, selection, Modifier.fillMaxWidth())
                }
                item(key = "queue-filters") {
                    QueueFilters(
                        view = queueView,
                        criteria = filterCriteria,
                        allShownSelected = queueView.visibleNames.isNotEmpty() && selection.containsAll(queueView.visibleNames),
                        onSearch = viewModel::setQueueSearch,
                        onFilter = viewModel::setQueueFilter,
                        onClear = viewModel::clearQueueFilters,
                        onSelectShown = viewModel::toggleSelectAll
                    )
                }
                if (!selectionMode) {
                    item(key = "queue-header") {
                        if (queueView.filtered) {
                            Text(
                                stringResource(R.string.queue23_whole_queue),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                        QueueHeader(
                            summary = progress,
                            eta = eta,
                            need = need,
                            free = free,
                            shortfall = shortfall,
                            waitingReasons = waitingReasons,
                            held = held,
                            counts = counts,
                            onHold = { viewModel.setHeld(!held) },
                            onStopAll = viewModel::stopAll,
                            onRetryFailed = viewModel::retryFailed,
                            onClearFinished = viewModel::clearFinished
                        )
                    }
                }
                if (waitingFiles.isNotEmpty() && waitingText.isNotEmpty()) {
                    item(key = "notice-waiting") {
                        // On hold by the user: the button lifts the hold; otherwise it starts the waiting ones anyway.
                        val onHold = WaitReason.HELD in waitingReasons
                        val release: () -> Unit = { if (onHold) viewModel.setHeld(false) else viewModel.startWaitingNow() }
                        NoticeRow(
                            text = pluralStringResource(R.plurals.downloads_waiting, waitingFiles.size, waitingFiles.size, waitingText),
                            icon = R.drawable.ic_hourglass,
                            action = stringResource(if (onHold) R.string.downloads_release else R.string.downloads_start_now),
                            onAction = release
                        )
                    }
                }
                if (shortfall > 0) {
                    item(key = "notice-space") {
                        NoticeRow(text = stringResource(R.string.downloads_low_space, formatBytes(shortfall)), icon = R.drawable.ic_warning, error = true)
                    }
                }
                if (queueView.rows.isEmpty()) {
                    item(key = "queue-no-matches") { QueueNoMatches(viewModel::clearQueueFilters) }
                }
                itemsIndexed(queueView.rows, key = { _, it -> it.fileName }) { index, item ->
                    DownloadItem(
                        item = item,
                        details = details[item.fileName],
                        upload = uploads[item.fileName],
                        pendingRetry = pendingRetries[item.fileName],
                        canChangeSource = DownloadSourcePolicy.canChange(item.status),
                        onChangeSource = { viewModel.chooseSource(item.fileName) },
                        condition = itemWaits[item.fileName],
                        canSchedule = notStarted(item),
                        onWaitFor = { whenTargets = listOf(item.fileName) },
                        waitingReason = waitingShort.takeIf { it.isNotEmpty() && item.fileName in waitingFiles },
                        queuePosition = queuePositions[item.fileName],
                        verify = verification[item.fileName],
                        compact = isLandscape,
                        viewModel = viewModel,
                        onOpenSettings = { navController.navigate(NavRoutes.Settings.route) { launchSingleTop = true } },
                        onOpenSources = { navController.navigate(NavRoutes.Sources.route) { launchSingleTop = true } },
                        onOpenStorage = { navController.navigate(NavRoutes.Storage.route) { launchSingleTop = true } },
                        selectionMode = selectionMode,
                        selected = item.fileName in selection,
                        onToggleSelection = { viewModel.toggleSelection(item.fileName) },
                        focusUp = barFocus.takeIf { selectionMode && index == 0 },
                        sweep = sweep,
                        // Rows glide to their new place when the queue is reordered or a row goes;
                        // no fades (they would need an alpha layer per row).
                        modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = itemPlacement, fadeOutSpec = null),
                        onRowFocused = { row, focused ->
                            if (focused) focusedRow = row else if (focusedRow?.fileName == row.fileName) focusedRow = null
                        }
                    )
                }
            }
        }
    }

    sourceChoice?.let { state ->
        DownloadSourceChoiceDialog(
            choices = state.choices,
            loading = state.loading,
            busy = state.busy,
            error = state.errorRes?.let { stringResource(it) },
            onDismiss = viewModel::dismissSourceChoice,
            onConfirm = viewModel::confirmSource
        )
    }

    whenTargets?.let { names ->
        DownloadWhenDialog(
            count = names.size,
            initial = names.firstNotNullOfOrNull { itemConditions[it] },
            onDismiss = { whenTargets = null },
            onConfirm = { condition ->
                whenTargets = null
                viewModel.setCondition(names, condition)
            }
        )
    }

    showDeleteConfirmation?.let { fileNames ->
        AlertDialog(
            modifier = Modifier.closeOnGamepadB { viewModel.cancelDeleteConfirmation() },
            onDismissRequest = { viewModel.cancelDeleteConfirmation() },
            title = {
                Text(
                    text = if (fileNames.size > 1) stringResource(R.string.delete_downloads_title)
                    else stringResource(R.string.delete_download_title)
                )
            },
            text = {
                Text(
                    text = if (fileNames.size > 1) stringResource(R.string.delete_downloads_message, fileNames.size)
                    else stringResource(R.string.delete_download_message)
                )
            },
            confirmButton = {
                val delete = if (fileNames.size > 1) R.string.delete_files else R.string.delete_file
                DialogButton(stringResource(delete), onClick = { viewModel.confirmDeleteRemoveFile(fileNames) })
            },
            dismissButton = {
                val keep = if (fileNames.size > 1) R.string.keep_files else R.string.keep_file
                DialogButton(stringResource(keep), onClick = { viewModel.confirmDeleteKeepFile(fileNames) }, initialFocus = rememberInitialFocus())
            }
        )
    }
}

/** A line of explanation in the list, with an optional button (e.g. "Start now"). */
@Composable
private fun NoticeRow(text: String, icon: Int, action: String? = null, onAction: () -> Unit = {}, error: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Panel(
        modifier = Modifier.fillMaxWidth(),
        tone = if (error) PanelTone.Danger else PanelTone.Normal,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (error) scheme.onErrorContainer else scheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (error) scheme.onErrorContainer else scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (action != null) ActionPill(action, onAction, tone = ActionTone.Accent)
        }
    }
}

/**
 * Bulk actions for the ticked rows, in the summary's place. Each button only shows up when
 * some row in the selection accepts it, so nothing focusable is ever a no-op.
 */
@Composable
private fun SelectionBar(
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    selected: List<DownloadItemModel>,
    details: Map<String, DownloadableFileWithTags>,
    compact: Boolean,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    canStop: Boolean,
    canPrioritize: Boolean,
    onPrioritize: () -> Unit,
    canWait: Boolean,
    canStartNow: Boolean,
    onWaitFor: () -> Unit,
    onStartNow: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val size = if (compact) 36.dp else 44.dp
    // Only the actions some ticked row accepts, so nothing focusable is ever a no-op.
    val actions = buildList {
        if (selected.any { it.status.canRetry }) {
            add(BulkAction(R.drawable.ic_retry, stringResource(R.string.download_retry), scheme.onSurface, onRetry))
        }
        if (selected.any { d -> details[d.fileName]?.let { QueueActions.canPause(d.status, it.file.isTorrent) } == true }) {
            add(BulkAction(R.drawable.ic_pause, stringResource(R.string.download_pause), scheme.onSurface, onPause))
        }
        if (canStop) {
            add(BulkAction(R.drawable.ic_stop, stringResource(R.string.download_cancel), scheme.onSurface, onStop))
        }
        if (canPrioritize) {
            add(BulkAction(R.drawable.ic_arrow_upward, stringResource(R.string.queue23_prioritize), scheme.primary, onPrioritize))
        }
        if (canStartNow) {
            add(BulkAction(R.drawable.ic_play_arrow, stringResource(R.string.plan6_start_now), scheme.primary, onStartNow))
        }
        if (canWait) {
            add(BulkAction(R.drawable.ic_schedule, stringResource(R.string.plan6_wait_for), scheme.onSurface, onWaitFor))
        }
        if (selected.any { it.status.canDelete }) {
            add(BulkAction(R.drawable.ic_trash, stringResource(R.string.download_delete), scheme.error, onDelete))
        }
        add(BulkAction(R.drawable.ic_close, stringResource(R.string.selection_clear), scheme.onSurfaceVariant, onClear))
    }
    Panel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) },
        tone = PanelTone.Accent,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = scheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                pluralStringResource(R.plurals.downloads_selected, selected.size, selected.size),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onPrimaryContainer,
                modifier = Modifier.widthIn(max = 100.dp).padding(start = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Controller focus scrolls its action into view instead of overflowing a small handheld.
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
            actions.forEachIndexed { index, action ->
                ActionButton(
                    action.icon, action.description, size, action.tint,
                    // ▲ from the list lands on the first action, never on "clear selection".
                    modifier = if (index == 0) Modifier.focusRequester(focusRequester) else Modifier,
                    onClick = action.onClick
                )
            }
            }
        }
    }
}

private class BulkAction(val icon: Int, val description: String, val tint: Color, val onClick: () -> Unit)
