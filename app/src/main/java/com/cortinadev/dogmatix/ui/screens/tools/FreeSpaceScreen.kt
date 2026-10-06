package com.cortinadev.dogmatix.ui.screens.tools

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.EmptyState
import com.cortinadev.dogmatix.ui.components.GameCover
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.theme.accentInk
import com.cortinadev.dogmatix.ui.theme.inkOf
import com.cortinadev.dogmatix.ui.theme.tabular
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.SpaceCandidate
import com.cortinadev.dogmatix.util.SpacePlay
import com.cortinadev.dogmatix.util.SpaceProtection

/** What the confirmation dialog is about to do. */
private enum class RemoveMode { REMOVE, REMOVE_AND_WISH }

/**
 * Free up space: the games on the device that were never played (by ES-DE's play records; without
 * them by size and age alone), biggest first, with the room they would free, a filter per console
 * and multi-select. A ticks a game, Y selects the biggest games that free N GB, X removes the
 * selection (always after a confirmation that lists the games and their files); favourites, games
 * in a collection and games with a RomM save or an achievement are marked protected and are left
 * out of the quick actions. "Remove and add to wishlist" keeps a wish for each game so it can be
 * fetched again later.
 *
 * @param onOpenWishlist opens the wishlist (offered once games were put on it); the route is
 *   `NavRoutes.Wishlist`.
 */
@Composable
fun FreeSpaceScreen(
    onOpenWishlist: () -> Unit = {},
    viewModel: FreeSpaceViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsState()
    val latest by rememberUpdatedState(ui)
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }

    var confirm by remember { mutableStateOf<RemoveMode?>(null) }
    // Nothing left ticked (all removed, or cleared): there is nothing to confirm any more.
    LaunchedEffect(ui.selected.isEmpty()) { if (ui.selected.isEmpty()) confirm = null }
    confirm?.let { mode ->
        if (ui.selectedGames.isNotEmpty()) RemoveDialog(
            games = ui.selectedGames,
            wish = mode == RemoveMode.REMOVE_AND_WISH,
            onConfirm = { viewModel.remove(context, mode == RemoveMode.REMOVE_AND_WISH) },
            onDismiss = { confirm = null }
        )
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    // The row under the cursor leaves the list when its game is removed: park the focus on the top row.
    LaunchedEffect(ui.removing) {
        if (!ui.removing) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
    }
    // Y picks the biggest games for the chosen amount; X removes what is ticked (A ticks, as a click).
    LaunchedEffect(confirm) {
        Gamepad.presses.collect { button ->
            if (confirm != null) return@collect
            when (button) {
                GamepadButton.Y -> latest.let { if (!it.loading && !it.removing) viewModel.selectBiggest(context) }
                GamepadButton.X -> if (latest.selected.isNotEmpty() && !latest.removing) confirm = RemoveMode.REMOVE
                else -> Unit
            }
        }
    }
    PublishLegend(
        listOf(
            LegendEntry("A", stringResource(R.string.pad_tick)),
            LegendEntry("Y", stringResource(R.string.space7_pad_biggest)),
            LegendEntry("X", stringResource(R.string.space7_remove)),
            LegendEntry("B", stringResource(R.string.pad_back)),
            LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
        )
    )

    val summary = when {
        ui.loading -> stringResource(R.string.tools_scanning)
        !ui.folderSet -> stringResource(R.string.tools_no_folder)
        ui.candidates.isEmpty() -> stringResource(if (ui.playedCount > 0) R.string.space7_none_played else R.string.space7_none_games)
        ui.hasPlayData -> pluralStringResource(R.plurals.space7_summary, ui.candidates.size, ui.candidates.size, formatBytes(ui.candidates.sumOf { it.bytes }))
        else -> pluralStringResource(R.plurals.space7_summary_nodata, ui.candidates.size, ui.candidates.size, formatBytes(ui.candidates.sumOf { it.bytes }))
    }
    val ready = !ui.loading && ui.folderSet
    val notes = buildList {
        if (ready && ui.candidates.isNotEmpty()) {
            ui.freeBytes?.let { add(stringResource(R.string.space7_free_now, formatBytes(it))) }
            if (!ui.hasPlayData) add(stringResource(R.string.space7_note_nodata))
            else if (ui.unknownCount > 0) add(pluralStringResource(R.plurals.space7_note_unknown, ui.unknownCount, ui.unknownCount))
            if (ui.playedCount > 0) add(pluralStringResource(R.plurals.space7_note_played, ui.playedCount, ui.playedCount, formatBytes(ui.playedBytes)))
            add(stringResource(R.string.space7_note_protected))
            if (!ui.savesChecked) add(stringResource(R.string.space7_note_no_saves))
            if (!ui.achievementsChecked) add(stringResource(R.string.space7_note_no_ach))
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.space7_title), icon = R.drawable.ic_free_space, subtitle = stringResource(R.string.space7_subtitle))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.weight(1f)
        ) {
            item(key = "summary") {
                ToolRow(
                    title = summary,
                    lines = notes,
                    onClick = { viewModel.load() },
                    modifier = Modifier.focusRequester(firstFocus),
                    icon = if (ready && ui.candidates.isEmpty()) R.drawable.ic_check_circle else R.drawable.ic_free_space
                ) {
                    ToolAction(stringResource(R.string.tools_refresh), icon = R.drawable.ic_retry) { viewModel.load() }
                }
            }
            ui.done?.let { done ->
                item(key = "done") {
                    ToolRow(
                        title = if (done.removed > 0) pluralStringResource(R.plurals.space7_done, done.removed, done.removed, formatBytes(done.bytes))
                        else pluralStringResource(R.plurals.space7_failed, done.failed, done.failed),
                        lines = listOfNotNull(
                            if (done.wished > 0) pluralStringResource(R.plurals.space7_done_wish, done.wished, done.wished) else null,
                            if (done.removed > 0 && done.failed > 0) pluralStringResource(R.plurals.space7_failed, done.failed, done.failed) else null
                        ),
                        onClick = { if (done.wished > 0) onOpenWishlist() },
                        icon = if (done.removed > 0) R.drawable.ic_check_circle else R.drawable.ic_warning
                    ) {
                        if (done.wished > 0) ToolAction(stringResource(R.string.space7_open_wishlist), icon = R.drawable.ic_wishlist, tone = ActionTone.Accent, onClick = onOpenWishlist)
                    }
                }
            }
            if (ready && ui.candidates.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = summary,
                        message = stringResource(R.string.space7_subtitle),
                        illustration = R.drawable.milou,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (ready && ui.candidates.isNotEmpty()) {
                if (ui.consoles.size > 1) item(key = "consoles") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        item(key = "all") { FilterChip(stringResource(R.string.space7_filter_all), ui.console == null) { viewModel.setConsole(null) } }
                        items(ui.consoles, key = { it.consoleId }) { c ->
                            val name = ConsoleFormatter.getConsoleDisplayName(c.consoleId).replace('\n', ' ')
                            FilterChip(stringResource(R.string.space7_filter_chip, name, c.games), ui.console == c.consoleId) { viewModel.setConsole(c.consoleId) }
                        }
                    }
                }
                item(key = "quick") {
                    ToolsActions {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.space7_free_prefix), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Stepper(
                                value = stringResource(R.string.space7_free_value, ui.freeGb),
                                onDecrement = { viewModel.stepFree(-1) },
                                onIncrement = { viewModel.stepFree(1) },
                                valueWidth = 64.dp
                            )
                        }
                        ToolAction(stringResource(R.string.space7_select_biggest), icon = R.drawable.ic_sparkle, tone = ActionTone.Accent) { viewModel.selectBiggest(context) }
                        ToolAction(stringResource(R.string.space7_select_all), icon = R.drawable.ic_checkbox_on) { viewModel.selectAll() }
                        if (ui.selected.isNotEmpty()) ToolAction(stringResource(R.string.space7_clear), icon = R.drawable.ic_clear_all) { viewModel.clear() }
                    }
                }
                items(ui.visible, key = { it.id }) { game ->
                    SpaceRow(game, selected = game.id in ui.selected, now = now) { viewModel.toggle(game.id) }
                }
            }
        }
        if (ui.selected.isNotEmpty()) {
            SelectionBar(
                count = ui.selected.size,
                bytes = ui.selectedBytes,
                protectedCount = ui.selectedProtected,
                busy = ui.removing,
                onRemove = { confirm = RemoveMode.REMOVE },
                onRemoveAndWish = { confirm = RemoveMode.REMOVE_AND_WISH }
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    ActionPill(
        label,
        onClick = onClick,
        icon = if (selected) R.drawable.ic_check else null,
        tone = if (selected) ActionTone.Accent else ActionTone.Neutral
    )
}

@Composable
private fun protectionLabel(protection: SpaceProtection): String = stringResource(
    when (protection) {
        SpaceProtection.FAVOURITE -> R.string.space7_why_favourite
        SpaceProtection.COLLECTION -> R.string.space7_why_collection
        SpaceProtection.SAVE -> R.string.space7_why_save
        SpaceProtection.ACHIEVEMENT -> R.string.space7_why_achievement
    }
)

/** One game: a tick box, its cover, size, age and what protects it. A (or a tap) ticks it. */
@Composable
private fun SpaceRow(game: SpaceCandidate, selected: Boolean, now: Long, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val console = ConsoleFormatter.getConsoleDisplayName(game.consoleId).replace('\n', ' ')
    val details = formatBytes(game.bytes) + " · " + pluralStringResource(R.plurals.tools_files, game.fileCount, game.fileCount)
    val added = if (game.addedAt > 0L) {
        val relative = DateUtils.getRelativeTimeSpanString(minOf(game.addedAt, now), now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        stringResource(R.string.space7_added, relative.toString())
    } else null
    val reasons = game.protections.sorted().map { protectionLabel(it) }
    ToolRow(
        title = game.title,
        lines = listOfNotNull(
            "$console · ${game.entry.folder}",
            if (added != null) "$details · $added" else details,
            if (reasons.isNotEmpty()) stringResource(R.string.space7_protected_note, reasons.joinToString(" · ")) else null
        ),
        onClick = onToggle,
        modifier = Modifier.semantics {
            role = Role.Checkbox
            toggleableState = ToggleableState(selected)
        },
        badge = if (game.isProtected || game.play == SpacePlay.UNKNOWN) ({
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (game.isProtected) Pill(stringResource(R.string.space7_protected), tone = PillTone.Warning, icon = R.drawable.ic_lock)
                if (game.play == SpacePlay.UNKNOWN) Pill(stringResource(R.string.space7_no_data), tone = PillTone.Info)
            }
        }) else null,
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    painterResource(if (selected) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox_off),
                    contentDescription = null,
                    tint = if (selected) accentInk() else scheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                GameCover(game.consoleId, game.entry.coverFileName(), game.title, Modifier.size(width = 36.dp, height = 48.dp), showLabel = false)
            }
        }
    )
}

/** What is ticked and the two ways to remove it; shown under the list while something is ticked. */
@Composable
private fun SelectionBar(
    count: Int,
    bytes: Long,
    protectedCount: Int,
    busy: Boolean,
    onRemove: () -> Unit,
    onRemoveAndWish: () -> Unit
) {
    Column {
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        InfoCard(
            lines = listOfNotNull(
                if (busy) stringResource(R.string.space7_removing) else pluralStringResource(R.plurals.space7_selected, count, count, formatBytes(bytes)),
                if (protectedCount > 0) pluralStringResource(R.plurals.space7_selected_protected, protectedCount, protectedCount) else null
            ),
            accent = true,
            icon = R.drawable.ic_trash
        )
        ToolsActions {
            ActionPill(stringResource(R.string.space7_remove), onClick = onRemove, icon = R.drawable.ic_trash, tone = ActionTone.Danger, enabled = !busy)
            ActionPill(stringResource(R.string.space7_remove_wish), onClick = onRemoveAndWish, icon = R.drawable.ic_wishlist, tone = ActionTone.Danger, enabled = !busy)
        }
    }
}

private const val MAX_LISTED_FILES = 4

/**
 * The last word before anything is deleted: how many games and how much room, every game with its
 * files (each row takes the D-pad focus, so a long list can be walked with a pad), which ones are
 * protected, and whether they go on the wishlist. Focus starts on Cancel.
 */
@Composable
private fun RemoveDialog(games: List<SpaceCandidate>, wish: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val cancelFocus = rememberInitialFocus()
    val protectedCount = games.count { it.isProtected }
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.space7_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pluralStringResource(R.plurals.space7_confirm_message, games.size, games.size, formatBytes(games.sumOf { it.bytes })))
                if (wish) Text(pluralStringResource(R.plurals.space7_confirm_wish, games.size), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                if (protectedCount > 0) Text(
                    pluralStringResource(R.plurals.space7_confirm_protected, protectedCount, protectedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = inkOf(scheme.error)
                )
                Spacer(Modifier.height(2.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(games, key = { it.id }) { game ->
                        Column(modifier = Modifier.fillMaxWidth().focusableCard(cornerRadius = 8.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(game.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (game.isProtected) Pill(stringResource(R.string.space7_protected), tone = PillTone.Warning, icon = R.drawable.ic_lock)
                                Text(formatBytes(game.bytes), style = MaterialTheme.typography.labelMedium.tabular(), color = scheme.onSurfaceVariant)
                            }
                            game.entry.files.take(MAX_LISTED_FILES).forEach {
                                Text("• " + it.name, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val more = game.fileCount - MAX_LISTED_FILES
                            if (more > 0) Text(stringResource(R.string.space7_more_files, more), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(if (wish) R.string.space7_remove_wish else R.string.space7_remove),
                onClick = { onConfirm(); onDismiss() }
            )
        },
        dismissButton = {
            DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus)
        }
    )
}
