package com.cortinadev.dogmatix.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.theme.DogmatixTokens
import androidx.compose.ui.unit.IntOffset
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.IconTile
import com.cortinadev.dogmatix.ui.components.SectionTitle
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.util.CardGrid

/*
 * The building blocks of the 5.0 settings screens (Settings, RomM, Save sync): the focusable row,
 * the switch, the pill button, a card header and the "card slice" that lets the cells of a lazy
 * grid read as one panel (see [CardGrid]).
 */

/** The accent tile every leading icon of a settings screen sits on: rows and card headers alike. */
internal val SettingsTileSize = 32.dp

/** Space between that tile and the text, so the title column lines up in every row and header. */
internal val SettingsTileGap = 12.dp

/**
 * The room a row (or header, or preview) keeps from its cell; the tiles line up on it. The same in
 * both orientations, so every screen of the kit has one tile line and one control line.
 */
@Composable
@ReadOnlyComposable
internal fun settingsInset(): Dp = 12.dp

/** Padding of a card on all four sides (and on both sides of the gutter between two columns). */
internal val SettingsCardPad = 8.dp

/** Space between two cards, and between the screen title and the first card. */
internal val SettingsCardGap = 16.dp

/** The value slot of every settings stepper, so their arrows line up from row to row. */
internal val SettingsStepperWidth = 112.dp

/** Left and right margin of a settings screen: the same on both sides. */
@Composable
@ReadOnlyComposable
internal fun settingsMargin(): Dp =
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) 16.dp else 12.dp

/**
 * Two columns of rows only where both stay roomy (an Odin or Thor top screen in landscape, a
 * tablet); a narrow landscape screen (Thor's bottom screen) gets one centred column.
 */
@Composable
@ReadOnlyComposable
internal fun settingsColumns(): Int {
    val config = LocalConfiguration.current
    return if (config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 800) 2 else 1
}

/** The widest a settings column of cards gets; wider screens centre it with equal margins. */
internal fun settingsMaxWidth(columns: Int): Dp = if (columns >= 2) 1180.dp else 720.dp

/** The ‹ value › stepper of a settings row, with the one value width of the kit (the value is centred). */
@Composable
internal fun SettingsStepper(value: String, onDecrement: () -> Unit, onIncrement: () -> Unit, valueWidth: Dp = SettingsStepperWidth) {
    Stepper(value, onDecrement = onDecrement, onIncrement = onIncrement, valueWidth = valueWidth)
}

/**
 * The ground of the settings screens: pure AMOLED black in the dark themes (dark and true black),
 * with black cards drawn as hairline outlines instead of grey lifted panels and no glow; the light
 * theme stays light, with white cards. Scoped: only what is inside sees the changed colours.
 */
@Composable
internal fun SettingsSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val (settingsScheme, settingsTokens) = remember(scheme, tokens) { settingsColors(scheme, tokens) }
    CompositionLocalProvider(LocalDogmatixTokens provides settingsTokens) {
        MaterialTheme(colorScheme = settingsScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(LocalContentColor provides settingsScheme.onBackground) {
                Box(modifier = modifier.fillMaxSize().background(settingsScheme.background)) { content() }
            }
        }
    }
}

/** Near-black for the few raised controls (stepper buttons, pill buttons) so they still read on #000. */
private val AmoledRaised = Color(0xFF1B1B1F)
/** The hairline of an AMOLED card: thin, neutral, just visible on black. */
private val AmoledHairline = Color(0xFF2C2C32)

internal fun settingsColors(scheme: ColorScheme, tokens: DogmatixTokens): Pair<ColorScheme, DogmatixTokens> {
    if (!tokens.isDark) {
        val light = scheme.copy(surfaceContainer = tokens.card, surfaceContainerLow = tokens.card)
        return light to tokens.copy(glowStrength = 0f)
    }
    val black = Color.Black
    val dark = scheme.copy(
        background = black,
        surface = black,
        surfaceVariant = black,
        surfaceDim = black,
        surfaceContainerLowest = black,
        surfaceContainerLow = black,
        surfaceContainer = black,
        surfaceContainerHigh = AmoledRaised,
        surfaceBright = AmoledRaised,
        primaryContainer = lerp(black, scheme.primary, 0.20f),
        tertiaryContainer = lerp(black, scheme.tertiary, 0.20f),
        errorContainer = lerp(black, scheme.error, 0.22f),
        secondaryContainer = AmoledRaised,
        outlineVariant = AmoledHairline,
        inverseOnSurface = black
    )
    return dark to tokens.copy(
        gradientTop = black,
        card = black,
        glowStrength = 0f,
        highlight = Color.Transparent,
        hairline = AmoledHairline
    )
}

/** True inside the accent-filled card (the way into Tools): its tiles turn solid so they stay visible on the fill. */
private val LocalOnAccentCard = compositionLocalOf { false }

/** The one look of a settings icon: the accent tile, solid on the accent card. */
@Composable
internal fun SettingsIconTile(icon: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    if (LocalOnAccentCard.current) {
        IconTile(icon, modifier, size = SettingsTileSize, container = scheme.primary, tint = scheme.onPrimary)
    } else {
        IconTile(icon, modifier, size = SettingsTileSize)
    }
}

/**
 * A focusable settings row. Click / A runs [onClick]; while focused, D-pad left/right
 * calls [onAdjust] with -1 / +1 so steppers, switches and swatches work from a gamepad.
 *
 * Every row has a leading [icon], always drawn the same way (the accent tile, [SettingsTileSize]),
 * so the title column of all rows lines up. A wide control at the end (a stepper, two buttons) that
 * would squeeze the title too much drops under the text instead.
 *
 * @param hintMaxLines cap on the lines of [hint]; 0 = no cap, the hint wraps in full.
 * @param below extra content under the hint (a progress bar, status pills).
 */
@Composable
internal fun SettingRow(
    title: String,
    hint: String?,
    onClick: () -> Unit,
    icon: Int,
    onAdjust: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    hintMaxLines: Int = 0,
    hintColor: Color? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    val source = rememberFocusSource()
    // Settings text is never cut off: the hint wraps in full unless a caller asks for a cap.
    val lines = if (hintMaxLines > 0) hintMaxLines else Int.MAX_VALUE
    SettingRowLayout(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            // The ring (and its halo) first, so the clip below does not cut the halo off.
            .focusRing(source)
            .clip(RoundedCornerShape(8.dp))
            .onPreviewKeyEvent { event ->
                if (onAdjust == null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onAdjust(-1); true }
                    Key.DirectionRight -> { onAdjust(1); true }
                    else -> false
                }
            }
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = settingsInset(), vertical = 8.dp),
        leading = { SettingsIconTile(icon) },
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                hint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = hintColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = lines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                below?.invoke(this)
            }
        },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(SettingsTileGap), verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    )
}

/** The least width the text of a row keeps beside its control before the control moves under it. */
private val MinTextWidth = 112.dp

/**
 * Tile, text and control on one line; when the control would leave the text less than [MinTextWidth]
 * (large controls on a narrow card, large font sizes), it sits under the text, at the end of the line.
 */
@Composable
private fun SettingRowLayout(
    leading: @Composable () -> Unit,
    text: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Layout(content = { leading(); text(); trailing() }, modifier = modifier, measurePolicy = SettingRowPolicy)
}

/**
 * Tile, text and control (see [SettingRowLayout]). It answers intrinsic height queries itself, from
 * the same inline-or-under rule, so two rows side by side can share the height of the taller one.
 */
private object SettingRowPolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gap = SettingsTileGap.roundToPx()
        val minText = MinTextWidth.roundToPx()
        val width = constraints.maxWidth
        val lead = measurables[0].measure(Constraints(maxWidth = width))
        val room = (width - lead.width - gap).coerceAtLeast(0)
        val trail = measurables[2].measure(Constraints(maxWidth = room))
        val inline = trail.width == 0 || room - trail.width - gap >= minText
        val textRoom = if (inline && trail.width > 0) room - trail.width - gap else room
        val body = measurables[1].measure(Constraints(maxWidth = textRoom))
        val textX = lead.width + gap
        return if (inline) {
            val content = maxOf(lead.height, body.height, trail.height)
            val height = maxOf(content, constraints.minHeight).coerceAtMost(constraints.maxHeight)
            layout(width, height) {
                lead.placeRelative(0, (height - lead.height) / 2)
                body.placeRelative(textX, (height - body.height) / 2)
                trail.placeRelative(width - trail.width, (height - trail.height) / 2)
            }
        } else {
            val head = maxOf(lead.height, body.height)
            val between = 6.dp.roundToPx()
            val height = maxOf(head + between + trail.height, constraints.minHeight).coerceAtMost(constraints.maxHeight)
            val top = (height - head - between - trail.height) / 2
            layout(width, height) {
                lead.placeRelative(0, top + (head - lead.height) / 2)
                body.placeRelative(textX, top + (head - body.height) / 2)
                trail.placeRelative(width - trail.width, top + head + between)
            }
        }
    }

    private fun IntrinsicMeasureScope.heightFor(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val gap = SettingsTileGap.roundToPx()
        val minText = MinTextWidth.roundToPx()
        val leadW = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val leadH = measurables[0].maxIntrinsicHeight(leadW)
        val room = (width - leadW - gap).coerceAtLeast(0)
        val trailW = minOf(measurables[2].maxIntrinsicWidth(Constraints.Infinity), room)
        val trailH = measurables[2].maxIntrinsicHeight(trailW)
        val inline = trailW == 0 || room - trailW - gap >= minText
        val textRoom = if (inline && trailW > 0) room - trailW - gap else room
        val bodyH = measurables[1].maxIntrinsicHeight(textRoom)
        return if (inline) maxOf(leadH, bodyH, trailH) else maxOf(leadH, bodyH) + 6.dp.roundToPx() + trailH
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        heightFor(measurables, width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        heightFor(measurables, width)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables[0].minIntrinsicWidth(height) + SettingsTileGap.roundToPx() + MinTextWidth.roundToPx()

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.sumOf { it.maxIntrinsicWidth(height) } + 2 * SettingsTileGap.roundToPx()
}

/** A compact action at the end of a row (kept for the screens that share it). */
@Composable
internal fun PillButton(label: String, onClick: () -> Unit) {
    ActionPill(label, onClick)
}

/** The app's switch: accent track, and a check / cross on the thumb so the state reads at a glance. */
@Composable
internal fun ThemedSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        thumbContent = {
            Icon(
                painterResource(if (checked) R.drawable.ic_check else R.drawable.ic_close),
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize)
            )
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = scheme.surface,
            checkedTrackColor = scheme.primary,
            checkedBorderColor = Color.Transparent,
            checkedIconColor = scheme.primary,
            uncheckedThumbColor = scheme.surface,
            uncheckedTrackColor = LocalDogmatixTokens.current.knobOff,
            uncheckedBorderColor = Color.Transparent,
            uncheckedIconColor = scheme.onSurfaceVariant
        )
    )
}

/** The top of a settings card: the group's icon on an accent tile, its name, and room for status pills. */
@Composable
internal fun SettingsCardHeader(
    title: String,
    icon: Int,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val inset = settingsInset()
    Row(
        modifier = modifier
            .fillMaxWidth()
            // With the card's own padding: 12 dp above the header and 12 dp between it and its first row.
            .padding(start = inset, end = inset, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsTileGap)
    ) {
        SettingsIconTile(icon)
        SectionTitle(title, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
}

/**
 * One grid cell of a settings card: draws its slice of the panel ([cardSlice]) and insets the
 * content a little from the card's edges. [gapAbove] separates a card from the one before it.
 */
@Composable
internal fun CardCell(
    cell: CardGrid.Cell,
    modifier: Modifier = Modifier,
    gapAbove: Dp = 0.dp,
    accent: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    CompositionLocalProvider(LocalOnAccentCard provides accent) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = if (cell.top) gapAbove else 0.dp)
                .cardSlice(cell, accent)
                // The same padding on every side of a card, and on both sides of the column gutter;
                // rows inside a card get half of it above and below, so every line is as tall.
                .padding(
                    start = SettingsCardPad,
                    end = SettingsCardPad,
                    top = if (cell.top) SettingsCardPad else SettingsCardPad / 2,
                    bottom = if (cell.bottom) SettingsCardPad else SettingsCardPad / 2
                ),
            propagateMinConstraints = true,
            content = content
        )
    }
}

/**
 * Draws this cell's part of a card: the panel fill, the hairline border on the card's outer edges
 * only (rounded where the cell sits on a corner), the faint top light of dark themes on the top
 * cells and a hairline above rows that follow another line. Cells side by side join seamlessly,
 * so a group spread over a grid reads as a single panel. Drawn once per size, no recomposition.
 */
@Composable
internal fun Modifier.cardSlice(cell: CardGrid.Cell, accent: Boolean = false): Modifier {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val fill = if (accent) scheme.primaryContainer else scheme.surfaceContainer
    val border = if (accent) scheme.primary.copy(alpha = 0.35f) else tokens.hairline
    val highlight = tokens.highlight
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val top = cell.top
    val bottom = cell.bottom
    // In a right-to-left layout the start edge is on the right.
    val left = if (rtl) cell.end else cell.start
    val right = if (rtl) cell.start else cell.end
    val divider = cell.divider
    val gutter = cell.kind == CardGrid.Kind.ITEM && !cell.start
    val rowInset = settingsInset()
    return this.drawWithCache {
        val radius = 12.dp.toPx()
        val stroke = 1.dp.toPx()
        val w = size.width
        val h = size.height
        fun corner(on: Boolean) = if (on) CornerRadius(radius) else CornerRadius.Zero
        val fillPath = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f, top = 0f, right = w, bottom = h,
                    topLeftCornerRadius = corner(top && left),
                    topRightCornerRadius = corner(top && right),
                    bottomRightCornerRadius = corner(bottom && right),
                    bottomLeftCornerRadius = corner(bottom && left)
                )
            )
        }
        // The border outline is pushed past the sides that join another cell, then clipped away.
        val out = stroke * 3
        val half = stroke / 2
        val edgePath = Path().apply {
            addRoundRect(
                RoundRect(
                    left = if (left) half else -out,
                    top = if (top) half else -out,
                    right = if (right) w - half else w + out,
                    bottom = if (bottom) h - half else h + out,
                    topLeftCornerRadius = corner(top && left),
                    topRightCornerRadius = corner(top && right),
                    bottomRightCornerRadius = corner(bottom && right),
                    bottomLeftCornerRadius = corner(bottom && left)
                )
            )
        }
        val shine = if (top && highlight.alpha > 0f) {
            Brush.verticalGradient(listOf(highlight, Color.Transparent), startY = 0f, endY = 48.dp.toPx())
        } else null
        // Row dividers run from the tile line to the control line (card padding + row inset).
        val inset = (SettingsCardPad + rowInset).toPx()
        // The column gutter: a hairline on the start side of a cell that has another one before it.
        val gutterX = if (rtl) w - half else half
        onDrawBehind {
            drawPath(fillPath, fill)
            if (shine != null) drawPath(fillPath, shine)
            if (divider && border.alpha > 0f) {
                drawLine(
                    border,
                    start = Offset(if (left) inset else 0f, half),
                    end = Offset(if (right) w - inset else w, half),
                    strokeWidth = stroke
                )
            }
            if (gutter && border.alpha > 0f) drawLine(border, Offset(gutterX, 0f), Offset(gutterX, h), strokeWidth = stroke)
            if (border.alpha > 0f) clipRect { drawPath(edgePath, border, style = Stroke(stroke)) }
        }
    }
}

/**
 * The cells of [cells] grouped into the lines of the grid: a header alone, then the rows of its card
 * [columns] at a time. Each line is one item of [SettingsCardsGrid]'s list.
 */
internal fun settingsLines(cells: List<CardGrid.Cell>): List<List<Int>> {
    val lines = ArrayList<List<Int>>()
    var current = ArrayList<Int>()
    cells.forEachIndexed { index, cell ->
        val newLine = current.isEmpty() || cell.kind == CardGrid.Kind.HEADER || cell.column == 0 ||
            cells[current.last()].kind == CardGrid.Kind.HEADER || cells[current.last()].section != cell.section
        if (newLine && current.isNotEmpty()) { lines += current; current = ArrayList() }
        current += index
    }
    if (current.isNotEmpty()) lines += current
    return lines
}

/** The list item that shows cell [cell] of [cells] (counting the title item when there is one). */
internal fun settingsItemOf(cells: List<CardGrid.Cell>, cell: Int, hasTitle: Boolean = false): Int {
    val line = settingsLines(cells).indexOfFirst { cell in it }.coerceAtLeast(0)
    return line + if (hasTitle) 1 else 0
}

/**
 * The card grid of a settings screen. Every line of [CardGrid] cells is one list item: a card's
 * header over the full width, or its rows side by side in [columns] equal columns. The cells of a
 * line always share one height, so a card has straight edges however much a row's text wraps.
 * The whole grid is a centred column (at most [settingsMaxWidth]) with equal margins left and
 * right, and the same gap between all cards. [title] (a screen title) sits in that column on top.
 * Rows slide to their new place and fade in / out when a setting shows or hides others (not with
 * animations off).
 */
@Composable
internal fun SettingsCardsGrid(
    cells: List<CardGrid.Cell>,
    columns: Int,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    key: (Int, CardGrid.Cell) -> Any = { index, _ -> index },
    accent: (CardGrid.Cell) -> Boolean = { false },
    title: (@Composable () -> Unit)? = null,
    content: @Composable (Int, CardGrid.Cell) -> Unit
) {
    val reduceMotion = LocalReduceMotion.current
    val fadeSpec: FiniteAnimationSpec<Float>? = if (reduceMotion) null else tween(Motion.MEDIUM)
    val moveSpec: FiniteAnimationSpec<IntOffset>? = if (reduceMotion) null else tween(Motion.MEDIUM, easing = FastOutSlowInEasing)
    val lines = remember(cells) { settingsLines(cells) }
    val margin = settingsMargin()
    val maxWidth = settingsMaxWidth(columns)
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(start = margin, end = margin, top = 12.dp, bottom = 24.dp)
    ) {
        if (title != null) {
            item(key = "settings-title") {
                Box(
                    Modifier
                        .widthIn(max = maxWidth)
                        .fillMaxWidth()
                        // The title's icon and text on the same lines as the cards' tiles and text.
                        .padding(start = SettingsCardPad + settingsInset(), end = SettingsCardPad + settingsInset(), bottom = SettingsCardGap)
                ) { title() }
            }
        }
        lines.forEachIndexed { lineIndex, line ->
            val first = cells[line.first()]
            item(key = key(line.first(), first)) {
                val gap = if (lineIndex > 0 && first.top) SettingsCardGap else 0.dp
                Row(
                    modifier = Modifier
                        .animateItem(fadeSpec, moveSpec, fadeSpec)
                        .widthIn(max = maxWidth)
                        .fillMaxWidth()
                        .padding(top = gap)
                        .then(if (line.size > 1) Modifier.height(IntrinsicSize.Max) else Modifier)
                ) {
                    line.forEach { index ->
                        val cell = cells[index]
                        CardCell(
                            cell,
                            modifier = Modifier
                                .weight(cell.span.toFloat())
                                .then(if (line.size > 1) Modifier.fillMaxHeight() else Modifier),
                            accent = cell.kind == CardGrid.Kind.ITEM && accent(cell)
                        ) { content(index, cell) }
                    }
                }
            }
        }
    }
}
