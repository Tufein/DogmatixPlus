package com.cortinadev.dogmatix.ui.screens.tools

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.RemovalPlan
import com.cortinadev.dogmatix.data.service.StaleGame
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.ui.screens.settings.ThemedSwitch
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.OfflineCollections

/*
 * 7.0 "Keep a collection on this device", as pieces the collections screen shows: a switch row under
 * each collection, a "Fetch now" action, a block with the last run and the settings of the feature,
 * and the review of fetched games that left their collection (removed only after a confirmation that
 * lists the files).
 */

/** What [rememberOfflineCollections] hands to the collections screen. */
@Stable
class OfflineCollectionsUi internal constructor(
    internal val vm: OfflineCollectionsViewModel,
    internal val data: OfflineData
) {
    val anyKept: Boolean get() = data.kept.isNotEmpty()

    /** The switch row (with its status line) for one collection; put it under the collection's own row. */
    @Composable
    fun SwitchRow(collectionId: Long, modifier: Modifier = Modifier) {
        val on = collectionId in data.kept
        val state = data.state
        val tally = state.tallies[collectionId]
        val line = when {
            !on -> stringResource(R.string.offline7_switch_hint)
            state.running -> stringResource(R.string.offline7_status_running)
            tally == null -> stringResource(R.string.offline7_status_checking)
            else -> tallyLine(tally)
        }
        ToolRow(
            title = stringResource(R.string.offline7_switch_title),
            lines = listOf(line),
            onClick = { vm.setKept(collectionId, !on) },
            modifier = modifier.padding(start = 20.dp),
            icon = R.drawable.ic_download
        ) { ThemedSwitch(on) { vm.setKept(collectionId, it) } }
    }

    /** "Fetch now" for the actions strip; shown while at least one collection is on. */
    @Composable
    fun FetchNowAction() {
        if (!anyKept) return
        ToolAction(stringResource(R.string.offline7_fetch_now), icon = R.drawable.ic_download, tone = ActionTone.Accent) {
            if (!data.state.running) vm.fetchNow()
        }
    }
}

/**
 * Connects the collections screen to the feature and shows its confirmation dialog when one is up.
 * Call once at the top of the screen.
 */
@Composable
fun rememberOfflineCollections(): OfflineCollectionsUi {
    val vm: OfflineCollectionsViewModel = hiltViewModel()
    val data by vm.data.collectAsState()
    val removal by vm.removal.collectAsState()
    val context = LocalContext.current
    removal?.let { plan -> RemovalDialog(plan, onConfirm = { vm.confirmRemoval(context) }, onDismiss = vm::dismissRemoval) }
    return remember(vm, data) { OfflineCollectionsUi(vm, data) }
}

/** The block under the collections: last run, the settings, and the games that left a collection. */
fun LazyListScope.offlineCollectionsItems(offline: OfflineCollectionsUi) {
    val data = offline.data
    val review = data.state.review
    if (!offline.anyKept && review.isEmpty()) return
    if (offline.anyKept) {
        item(key = "offline-head") {
            SectionHeader(stringResource(R.string.offline7_section_title), stringResource(R.string.offline7_section_hint), icon = R.drawable.ic_download)
        }
        item(key = "offline-last") { LastRunCard(data) }
        item(key = "offline-cap") {
            ToolRow(
                title = stringResource(R.string.offline7_cap_title),
                lines = listOf(stringResource(R.string.offline7_cap_hint)),
                onClick = { offline.vm.setCap(data.cap + OfflineCollections.CAP_STEP) },
                icon = R.drawable.ic_collections
            ) {
                Stepper(
                    data.cap.toString(),
                    onDecrement = { offline.vm.setCap(data.cap - OfflineCollections.CAP_STEP) },
                    onIncrement = { offline.vm.setCap(data.cap + OfflineCollections.CAP_STEP) }
                )
            }
        }
        item(key = "offline-wifi") {
            ToolRow(
                title = stringResource(R.string.offline7_wifi_title),
                lines = listOf(stringResource(R.string.offline7_wifi_hint)),
                onClick = { offline.vm.setWifiOnly(!data.wifiOnly) },
                icon = R.drawable.ic_network_check
            ) { ThemedSwitch(data.wifiOnly) { offline.vm.setWifiOnly(it) } }
        }
    }
    if (review.isNotEmpty()) {
        item(key = "offline-review-head") {
            Column {
                SectionHeader(stringResource(R.string.offline7_review_title), stringResource(R.string.offline7_review_hint), icon = R.drawable.ic_trash)
                ToolsActions {
                    ToolAction(stringResource(R.string.offline7_review_remove_all, review.size), icon = R.drawable.ic_trash, tone = ActionTone.Danger) { offline.vm.askRemoval(review) }
                    ToolAction(stringResource(R.string.offline7_review_keep_all)) { offline.vm.keep(review) }
                }
            }
        }
        items(review, key = { "offline-r|" + it.game.consoleId + "|" + it.game.fileName }) { game ->
            ReviewRow(game, onRemove = { offline.vm.askRemoval(listOf(game)) }, onKeep = { offline.vm.keep(listOf(game)) })
        }
    }
}

@Composable
private fun tallyLine(t: OfflineCollections.Tally): String {
    val parts = listOfNotNull(
        stringResource(R.string.offline7_status_count, t.onDevice, t.total),
        t.queued.takeIf { it > 0 }?.let { stringResource(R.string.offline7_status_queued, it) },
        t.fetch.takeIf { it > 0 }?.let { stringResource(R.string.offline7_status_fetch, it) },
        t.noSpace.takeIf { it > 0 }?.let { stringResource(R.string.offline7_status_space, it) },
        t.overCap.takeIf { it > 0 }?.let { stringResource(R.string.offline7_status_cap, it) },
        t.notInLibrary.takeIf { it > 0 }?.let { stringResource(R.string.offline7_status_not_listed, it) }
    )
    return parts.joinToString(" · ")
}

@Composable
private fun LastRunCard(data: OfflineData) {
    val last = data.last
    val state = data.state
    val lines = buildList {
        add(
            when {
                state.running -> stringResource(R.string.offline7_status_running)
                last == null -> stringResource(R.string.offline7_last_none)
                else -> stringResource(
                    R.string.offline7_last_line,
                    DateUtils.getRelativeTimeSpanString(last.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    pluralStringResource(R.plurals.offline7_last_queued, last.queued, last.queued)
                )
            }
        )
        if (state.noFolder) add(stringResource(R.string.offline7_no_folder))
        if (last != null && last.noSpace > 0) add(pluralStringResource(R.plurals.offline7_notify_space, last.noSpace, last.noSpace, formatBytes(last.missingBytes)))
        if (last != null && last.overCap > 0) add(pluralStringResource(R.plurals.offline7_notify_more, last.overCap, last.overCap))
    }
    val warn = state.noFolder || (last?.noSpace ?: 0) > 0
    InfoCard(lines, icon = if (warn) R.drawable.ic_error else R.drawable.ic_check_circle, danger = warn)
}

@Composable
private fun ReviewRow(game: StaleGame, onRemove: () -> Unit, onKeep: () -> Unit) {
    val title = stripExtension(FileParsingUtils.decodeUrlEncodedFileName(game.game.fileName))
    val lines = listOfNotNull(
        ConsoleFormatter.getConsoleDisplayName(game.game.consoleId),
        game.collectionNames.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.offline7_review_from, it.joinToString(", ")) }
    )
    ToolRow(title = title, lines = lines, onClick = onRemove, icon = R.drawable.ic_collections) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolAction(stringResource(R.string.offline7_review_keep), onClick = onKeep)
            ToolAction(stringResource(R.string.offline7_review_remove), tone = ActionTone.Danger, onClick = onRemove)
        }
    }
}

/** The confirmation: every file that goes, by name (focus starts on Cancel; B cancels). */
@Composable
private fun RemovalDialog(plan: RemovalPlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = rememberInitialFocus()
    val files = plan.files
    val absent = plan.items.count { it.entries.isEmpty() }
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.offline7_confirm_title, plan.items.size, plan.items.size)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (files.isEmpty()) {
                    Text(stringResource(R.string.offline7_confirm_nothing))
                } else {
                    Text(pluralStringResource(R.plurals.offline7_confirm_message, files.size, files.size, formatBytes(plan.bytes)))
                    Spacer(Modifier.height(8.dp))
                    files.take(MAX_LISTED).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    if (files.size > MAX_LISTED) Text(pluralStringResource(R.plurals.offline7_confirm_more, files.size - MAX_LISTED, files.size - MAX_LISTED), style = MaterialTheme.typography.bodySmall)
                    if (absent > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(pluralStringResource(R.plurals.offline7_confirm_absent, absent, absent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(stringResource(if (files.isEmpty()) R.string.offline7_confirm_drop else R.string.offline7_confirm_delete), onClick = onConfirm)
        },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}

private const val MAX_LISTED = 30
