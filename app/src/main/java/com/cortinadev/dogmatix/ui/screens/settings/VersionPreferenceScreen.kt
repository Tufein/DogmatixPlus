package com.cortinadev.dogmatix.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.PanelTone
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.ui.screens.game.ReasonPills
import com.cortinadev.dogmatix.ui.screens.game.becauseLine
import com.cortinadev.dogmatix.ui.screens.game.whyNotLine
import com.cortinadev.dogmatix.ui.screens.tools.PublishLegend
import com.cortinadev.dogmatix.ui.screens.tools.SectionHeader
import com.cortinadev.dogmatix.ui.screens.tools.ToolRow
import com.cortinadev.dogmatix.ui.screens.tools.ToolsTitle
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.accentInk
import com.cortinadev.dogmatix.ui.theme.tabular
import com.cortinadev.dogmatix.util.SizeTieBreak
import com.cortinadev.dogmatix.util.VersionPicker
import com.cortinadev.dogmatix.util.VersionPreferences

/**
 * Version preference (2.4.0): which languages and regions the app prefers, in order, and the rules
 * for betas, revisions, dumps and hacks, with a live preview on a sample game. "All consoles" edits
 * the pinned preference; a console edits only its own order of regions and languages. Every change
 * works on the value, not on a list position, so a double press never hits the wrong entry.
 * Everything works with touch and with the D-pad (move up / down buttons, no dragging).
 */
@Composable
fun VersionPreferenceScreen(viewModel: VersionPreferenceViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(ui.loaded) {
        if (ui.loaded) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
    }
    PublishLegend(listOf(LegendEntry("A", stringResource(R.string.pad_change)), LegendEntry("B", stringResource(R.string.pad_back))))
    var pickConsole by remember { mutableStateOf(false) }
    var addKind by remember { mutableStateOf<AddKind?>(null) }
    val pref = ui.preference
    val locale = LocalConfiguration.current.locales[0]

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item(key = "title") { ToolsTitle(stringResource(R.string.compare24_title), icon = R.drawable.ic_compare, subtitle = stringResource(R.string.compare24_subtitle)) }

        // Which consoles this is for.
        item(key = "scope") {
            val consoleName = ui.consoles.firstOrNull { it.id == ui.consoleId }?.name ?: ui.consoleId
            ToolRow(
                title = consoleName ?: stringResource(R.string.compare24_scope_all),
                lines = listOf(
                    stringResource(
                        when {
                            ui.consoleId != null && ui.consoleOwn -> R.string.compare24_state_console_own
                            ui.consoleId != null -> R.string.compare24_state_console_global
                            ui.pinned -> R.string.compare24_state_pinned
                            else -> R.string.compare24_state_default
                        }
                    )
                ),
                onClick = { pickConsole = true },
                modifier = Modifier.focusRequester(firstFocus),
                icon = R.drawable.ic_tune,
                chevron = true
            )
        }
        if (ui.consoleId != null) {
            item(key = "scope-hint") {
                Text(
                    stringResource(R.string.compare24_scope_console_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        // Languages.
        item(key = "h-langs") { SectionHeader(stringResource(R.string.compare24_langs_title), subtitle = stringResource(R.string.compare24_langs_hint), icon = R.drawable.ic_language) }
        if (pref.languages.isEmpty()) item(key = "langs-empty") { EmptyNote(stringResource(R.string.compare24_langs_empty)) }
        items(pref.languages.size, key = { "l" + pref.languages[it] }) { i ->
            val code = pref.languages[i]
            val name = VersionPreferences.languageName(code, locale)
            OrderRow(
                position = i + 1, label = name, detail = code,
                canUp = i > 0, canDown = i < pref.languages.lastIndex,
                upLabel = stringResource(R.string.compare24_move_up, name), downLabel = stringResource(R.string.compare24_move_down, name), removeLabel = stringResource(R.string.compare24_remove_item, name),
                onUp = { viewModel.edit { p -> p.copy(languages = VersionPreferences.move(p.languages, code, -1)) } },
                onDown = { viewModel.edit { p -> p.copy(languages = VersionPreferences.move(p.languages, code, 1)) } },
                onRemove = { viewModel.edit { p -> p.copy(languages = VersionPreferences.remove(p.languages, code)) } }
            )
        }
        item(key = "add-lang") { AddRow(stringResource(R.string.compare24_add_language)) { addKind = AddKind.LANGUAGE } }

        // Regions.
        item(key = "h-regions") { SectionHeader(stringResource(R.string.compare24_regions_title), subtitle = stringResource(R.string.compare24_regions_hint), icon = R.drawable.ic_globe) }
        if (pref.regions.isEmpty()) item(key = "regions-empty") { EmptyNote(stringResource(R.string.compare24_regions_empty)) }
        items(pref.regions.size, key = { "r" + pref.regions[it] }) { i ->
            val region = pref.regions[i]
            OrderRow(
                position = i + 1, label = region, detail = null,
                canUp = i > 0, canDown = i < pref.regions.lastIndex,
                upLabel = stringResource(R.string.compare24_move_up, region), downLabel = stringResource(R.string.compare24_move_down, region), removeLabel = stringResource(R.string.compare24_remove_item, region),
                onUp = { viewModel.edit { p -> p.copy(regions = VersionPreferences.move(p.regions, region, -1)) } },
                onDown = { viewModel.edit { p -> p.copy(regions = VersionPreferences.move(p.regions, region, 1)) } },
                onRemove = { viewModel.edit { p -> p.copy(regions = VersionPreferences.remove(p.regions, region)) } }
            )
        }
        item(key = "add-region") { AddRow(stringResource(R.string.compare24_add_region)) { addKind = AddKind.REGION } }

        // Rules: the same for every console, so only shown for "All consoles".
        if (ui.consoleId == null) {
            item(key = "h-rules") { SectionHeader(stringResource(R.string.compare24_rules_title), icon = R.drawable.ic_tune) }
            item(key = "rules") {
                Panel(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RuleRow(R.drawable.ic_check_circle, R.string.compare24_rule_final, R.string.compare24_rule_final_hint, pref.preferFinal) { v -> viewModel.edit { it.copy(preferFinal = v) } }
                    RuleRow(R.drawable.ic_update, R.string.compare24_rule_revision, R.string.compare24_rule_revision_hint, pref.preferLatestRevision) { v -> viewModel.edit { it.copy(preferLatestRevision = v) } }
                    RuleRow(R.drawable.ic_verified, R.string.compare24_rule_dump, R.string.compare24_rule_dump_hint, pref.preferVerifiedDump) { v -> viewModel.edit { it.copy(preferVerifiedDump = v) } }
                    RuleRow(R.drawable.ic_block, R.string.compare24_rule_unofficial, R.string.compare24_rule_unofficial_hint, pref.avoidUnofficial) { v -> viewModel.edit { it.copy(avoidUnofficial = v) } }
                    val sizes = SizeTieBreak.entries
                    fun shift(delta: Int) = viewModel.edit { it.copy(sizeTieBreak = sizes[(sizes.indexOf(it.sizeTieBreak) + delta + sizes.size) % sizes.size]) }
                    SettingRow(
                        title = stringResource(R.string.compare24_rule_size), hint = null, icon = R.drawable.ic_sort,
                        onClick = { shift(1) }, onAdjust = { shift(it) }
                    ) {
                        SettingsStepper(
                            stringResource(
                                when (pref.sizeTieBreak) {
                                    SizeTieBreak.NONE -> R.string.compare24_size_none
                                    SizeTieBreak.SMALLER -> R.string.compare24_size_smaller
                                    SizeTieBreak.LARGER -> R.string.compare24_size_larger
                                }
                            ),
                            onDecrement = { shift(-1) }, onIncrement = { shift(1) }, valueWidth = 120.dp
                        )
                    }
                }
            }
        }

        // Live preview.
        item(key = "h-preview") { SectionHeader(stringResource(R.string.compare24_preview_title), subtitle = stringResource(R.string.compare24_preview_hint), icon = R.drawable.ic_science) }
        item(key = "preview") {
            Panel(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val why = ui.explanation?.whyNot.orEmpty().associateBy { it.version.version.id }
                ui.ranked.forEach { r ->
                    val best = r.rank == 1
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier.size(28.dp).background(if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(r.rank.toString(), style = MaterialTheme.typography.labelLarge.tabular(), color = if (best) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(stripExtension(VersionPicker.readable(r.version.name)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            if (best) Pill(stringResource(R.string.compare24_preview_wins), tone = PillTone.Strong, icon = R.drawable.ic_award)
                            Text(stringResource(R.string.compare24_score_points, r.score), style = MaterialTheme.typography.labelSmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ReasonPills(r.reasons)
                        val explanation = ui.explanation
                        if (best && explanation != null) {
                            Text(becauseLine(explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else why[r.version.id]?.let {
                            Text(whyNotLine(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        item(key = "reset") {
            val resetLabel = if (ui.consoleId == null) R.string.compare24_reset else R.string.compare24_reset_console
            val canReset = if (ui.consoleId == null) ui.pinned else ui.consoleOwn
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                ActionPill(stringResource(resetLabel), onClick = viewModel::reset, icon = R.drawable.ic_restart_alt, tone = ActionTone.Neutral, enabled = canReset)
            }
        }
    }

    if (pickConsole) {
        ConsoleDialog(
            consoles = ui.consoles, selected = ui.consoleId,
            onPick = { viewModel.selectScope(it); pickConsole = false },
            onDismiss = { pickConsole = false }
        )
    }
    addKind?.let { kind ->
        val options = when (kind) {
            AddKind.LANGUAGE -> VersionPreferences.KNOWN_LANGUAGES.filter { c -> pref.languages.none { it.equals(c, ignoreCase = true) } }
            AddKind.REGION -> VersionPreferences.KNOWN_REGIONS.filter { c -> pref.regions.none { it.equals(c, ignoreCase = true) } }
        }
        AddDialog(
            title = stringResource(if (kind == AddKind.LANGUAGE) R.string.compare24_add_language else R.string.compare24_add_region),
            options = options.map { it to if (kind == AddKind.LANGUAGE) VersionPreferences.languageName(it, locale) else it },
            onPick = { value ->
                addKind = null
                viewModel.edit { p ->
                    if (kind == AddKind.LANGUAGE) p.copy(languages = VersionPreferences.add(p.languages, value))
                    else p.copy(regions = VersionPreferences.add(p.regions, value))
                }
            },
            onDismiss = { addKind = null }
        )
    }
}

private enum class AddKind { LANGUAGE, REGION }

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

/** One entry of an ordered list: its place, name, and the move up / move down / remove buttons. */
@Composable
private fun OrderRow(
    position: Int,
    label: String,
    detail: String?,
    canUp: Boolean,
    canDown: Boolean,
    upLabel: String,
    downLabel: String,
    removeLabel: String,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .defaultMinSize(minHeight = 52.dp)
            .background(scheme.surfaceContainer, shape)
            .border(1.dp, LocalDogmatixTokens.current.hairline, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(position.toString(), style = MaterialTheme.typography.titleSmall.tabular(), color = accentInk(), modifier = Modifier.defaultMinSize(minWidth = 20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
        }
        SquareButton(R.drawable.ic_arrow_upward, upLabel, canUp, onUp)
        SquareButton(R.drawable.ic_arrow_downward, downLabel, canDown, onDown)
        SquareButton(R.drawable.ic_close, removeLabel, true, onRemove)
    }
}

/** A 40 dp icon button with the focus ring; stays out of the way (not focusable) while it can do nothing. */
@Composable
private fun SquareButton(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Box(
        modifier = Modifier
            .size(40.dp)
            .focusRing(source, 10.dp)
            .background(if (enabled) scheme.surfaceContainerHigh else scheme.surfaceContainer, RoundedCornerShape(10.dp))
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(icon), contentDescription = description,
            tint = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        ActionPill(label, onClick = onClick, icon = R.drawable.ic_add, tone = ActionTone.Accent)
    }
}

@Composable
private fun RuleRow(icon: Int, title: Int, hint: Int, on: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(
        title = stringResource(title), hint = stringResource(hint), icon = icon,
        onClick = { onChange(!on) }, onAdjust = { onChange(it > 0) }
    ) { ThemedSwitch(checked = on, onChange = onChange) }
}

/** "All consoles" and every console, to choose whose order is edited. */
@Composable
private fun ConsoleDialog(consoles: List<ConsoleChoice>, selected: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val initial = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { initial.requestFocus() } }
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compare24_scope_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(key = "all") { PickRow(stringResource(R.string.compare24_scope_all), selected == null, Modifier.focusRequester(initial)) { onPick(null) } }
                items(consoles, key = { it.id }) { c -> PickRow(c.name, selected == c.id, Modifier) { onPick(c.id) } }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.compare24_picker_close), onClick = onDismiss) }
    )
}

@Composable
private fun PickRow(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRing(source, 10.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (selected) accentInk() else scheme.onSurface, modifier = Modifier.weight(1f))
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = accentInk(), modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddDialog(title: String, options: List<Pair<String, String>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val initial = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { initial.requestFocus() } }
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            FlowRow(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEachIndexed { i, (value, label) ->
                    ActionPill(label, onClick = { onPick(value) }, modifier = if (i == 0) Modifier.focusRequester(initial) else Modifier)
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.compare24_picker_close), onClick = onDismiss) }
    )
}
