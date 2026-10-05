package com.cortinadev.dogmatix.ui.screens.home.components

import com.cortinadev.dogmatix.ui.theme.accentInk
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.SectionTitle
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion
import com.cortinadev.dogmatix.ui.theme.tabular

/**
 * [label] is shown in the option list; [shortLabel] (defaults to [label]) in compact places like the
 * collapsed summary and chips. 5.0: [count] (games behind the option) and [color] (a console's
 * colour, shown as a dot) when known.
 */
data class FilterOption(
    val id: String,
    val label: String,
    val shortLabel: String = label,
    val count: Int? = null,
    val color: Color? = null
)

/**
 * One row of the filter panel. Multi-select unless [single]; the empty selection means "any".
 * ◀ ▶ (or the D-pad while the row is focused) cycles single choices; click / A opens the option list.
 */
data class FilterRowSpec(
    val label: String,
    val options: List<FilterOption>,
    val selected: Set<String>,
    val single: Boolean = false,
    /** When set, only these ids (plus the current selection) are listed until "Show more" is tapped. */
    val featured: Set<String>? = null,
    val onSelectionChange: (Set<String>) -> Unit
)

@Composable
fun FilterPanel(
    rows: List<FilterRowSpec>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    firstRowFocus: FocusRequester? = null,
    expandedRow: String? = null,
    onExpandedRowChange: (String?) -> Unit = {},
    footer: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle(stringResource(R.string.filters), icon = R.drawable.ic_filter, modifier = Modifier.weight(1f, fill = false))
            ActionPill(stringResource(R.string.clear), onClick = onClear, icon = R.drawable.ic_clear_filter)
        }
        rows.forEachIndexed { index, row ->
            FilterRow(
                spec = row,
                compact = compact,
                expanded = expandedRow == row.label,
                onExpandedChange = { onExpandedRowChange(if (it) row.label else null) },
                modifier = if (index == 0 && firstRowFocus != null) Modifier.focusRequester(firstRowFocus) else Modifier
            )
        }
        footer?.let {
            Spacer(Modifier.height(10.dp))
            it()
        }
    }
}

@Composable
private fun FilterRow(
    spec: FilterRowSpec,
    compact: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val rowHeight = if (compact) 40.dp else 48.dp
    val rowFocus = remember { FocusRequester() }
    val reduce = LocalReduceMotion.current

    // When the option list closes, the focused option disappears; put focus back on the row.
    var wasExpanded by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (wasExpanded && !expanded) runCatching { rowFocus.requestFocus() }
        wasExpanded = expanded
    }

    // The chevron turns in the draw phase (graphicsLayer lambda); snaps when motion is reduced.
    val turn = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded, reduce) {
        val target = if (expanded) 1f else 0f
        if (reduce) turn.snapTo(target) else turn.animateTo(target, Motion.spec(false, Motion.MEDIUM))
    }

    // Long lists (languages) start folded to the featured ids; "Show more" reveals the rest.
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) showAll = false }
    val featuredOptions = remember(spec.options, spec.featured, spec.selected) {
        spec.featured?.let { featured -> spec.options.filter { it.id in featured || it.id in spec.selected } }
    }
    val canFold = featuredOptions != null && featuredOptions.size < spec.options.size
    val visibleOptions = if (canFold && !showAll) featuredOptions!! else spec.options

    fun cycle(delta: Int) {
        // ◀ ▶ walks the short list when there is one; the full list is only a tap away.
        val options = if (canFold) featuredOptions!! else spec.options
        val n = options.size
        if (n == 0) return
        if (spec.single) {
            val current = options.indexOfFirst { it.id in spec.selected }.coerceAtLeast(0)
            spec.onSelectionChange(setOf(options[((current + delta) % n + n) % n].id))
        } else {
            // Slot 0 is "any"; slots 1..n are single picks.
            val current = if (spec.selected.size == 1) options.indexOfFirst { it.id == spec.selected.first() } + 1 else 0
            val next = ((current + delta) % (n + 1) + n + 1) % (n + 1)
            spec.onSelectionChange(if (next == 0) emptySet() else setOf(options[next - 1].id))
        }
    }

    val singlePick = spec.selected.singleOrNull()?.let { id -> spec.options.firstOrNull { it.id == id } }
    val valueText = when {
        spec.selected.isEmpty() -> stringResource(if (spec.single) R.string.filter_all else R.string.filter_any)
        spec.selected.size == 1 -> singlePick?.shortLabel ?: spec.selected.first()
        else -> stringResource(R.string.filter_selected_count, spec.selected.size)
    }
    // A row that narrows the list: a multi-select with a pick, or a single choice off its first (default) option.
    val active = if (spec.single) {
        spec.selected.isNotEmpty() && spec.options.isNotEmpty() && spec.selected.first() != spec.options.first().id
    } else spec.selected.isNotEmpty()
    val activeFill = scheme.primary.copy(alpha = if (LocalDogmatixTokens.current.isDark) 0.10f else 0.08f)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .focusRequester(rowFocus)
                .background(if (active) activeFill else Color.Transparent, RoundedCornerShape(8.dp))
                .focusRing(source)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { cycle(-1); true }
                        Key.DirectionRight -> { cycle(1); true }
                        else -> false
                    }
                }
                .clickable(interactionSource = source, indication = null) { onExpandedChange(!expanded) }
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A thin accent mark at the start of a row that is filtering.
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (active) scheme.primary else Color.Transparent)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                spec.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.width(6.dp))
            ArrowButton(R.drawable.ic_chevron_left) { cycle(-1) }
            Row(
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
            ) {
                singlePick?.color?.let { ColorDot(it) }
                Text(
                    valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) accentInk() else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            ArrowButton(R.drawable.ic_chevron_right) { cycle(1) }
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = if (expanded) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = 90f + 180f * turn.value }
            )
        }
        fun close() {
            runCatching { rowFocus.requestFocus() }
            onExpandedChange(false)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduce) EnterTransition.None
                else fadeIn(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) + expandVertically(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)),
            exit = if (reduce) ExitTransition.None
                else fadeOut(tween(Motion.FAST, easing = FastOutSlowInEasing)) + shrinkVertically(tween(Motion.MEDIUM, easing = FastOutSlowInEasing))
        ) {
            val shape = RoundedCornerShape(10.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .clip(shape)
                    .background(scheme.surfaceContainer)
                    .border(1.dp, LocalDogmatixTokens.current.hairline, shape)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.ButtonB || event.key == Key.Escape)) {
                            close(); true
                        } else false
                    }
                    .padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (!spec.single) {
                    OptionRow(
                        label = stringResource(R.string.filter_any),
                        checked = spec.selected.isEmpty(),
                        compact = compact,
                        mark = OptionMark.RADIO
                    ) { spec.onSelectionChange(emptySet()); close() }
                }
                visibleOptions.forEach { option ->
                    val checked = option.id in spec.selected
                    OptionRow(
                        label = option.label,
                        checked = checked,
                        compact = compact,
                        mark = if (spec.single) OptionMark.RADIO else OptionMark.CHECKBOX,
                        count = option.count,
                        dot = option.color
                    ) {
                        if (spec.single) {
                            spec.onSelectionChange(setOf(option.id)); close()
                        } else {
                            spec.onSelectionChange(if (checked) spec.selected - option.id else spec.selected + option.id)
                        }
                    }
                }
                if (canFold) {
                    OptionRow(
                        label = stringResource(if (showAll) R.string.filter_show_less else R.string.filter_show_more),
                        checked = false,
                        compact = compact,
                        mark = if (showAll) OptionMark.LESS else OptionMark.MORE,
                        accent = true
                    ) { showAll = !showAll }
                }
            }
        }
    }
}

/** The ‹ › of a filter row. Touch-only: the D-pad cycles the row itself, so these must not take focus. */
@Composable
private fun ArrowButton(icon: Int, onClick: () -> Unit) {
    val source = rememberFocusSource()
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .focusProperties { canFocus = false }
            .focusRing(source, 6.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ColorDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** What leads an option: a checkbox (multi-select), a radio (single choice) or a plus / minus ("Show more"). */
private enum class OptionMark { CHECKBOX, RADIO, MORE, LESS }

@Composable
private fun OptionRow(
    label: String,
    checked: Boolean,
    compact: Boolean,
    mark: OptionMark,
    accent: Boolean = false,
    count: Int? = null,
    dot: Color? = null,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val icon = when (mark) {
        OptionMark.CHECKBOX -> if (checked) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox_off
        OptionMark.RADIO -> if (checked) R.drawable.ic_radio_on else R.drawable.ic_radio_off
        OptionMark.MORE -> R.drawable.ic_plus
        OptionMark.LESS -> R.drawable.ic_remove
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 34.dp else 44.dp)
            .background(
                if (checked) scheme.primary.copy(alpha = if (LocalDogmatixTokens.current.isDark) 0.14f else 0.10f) else Color.Transparent,
                RoundedCornerShape(7.dp)
            )
            .focusRing(source, 7.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (checked || accent) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        dot?.let { ColorDot(it) }
        Text(
            label,
            style = if (checked || accent) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            color = if (accent) accentInk() else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        count?.let {
            Text(
                it.toString(),
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = scheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
