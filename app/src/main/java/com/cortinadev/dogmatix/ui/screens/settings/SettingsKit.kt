package com.cortinadev.dogmatix.ui.screens.settings

import android.content.res.Configuration
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
import androidx.compose.ui.layout.Layout
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

/** The room a row (or header, or preview) keeps from the edge of its card; the tiles line up on it. */
@Composable
@ReadOnlyComposable
internal fun settingsInset(): Dp =
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) 14.dp else 12.dp

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
                Text(title, style = MaterialTheme.typography.bodyLarge)
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
    Layout(content = { leading(); text(); trailing() }, modifier = modifier) { measurables, constraints ->
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
        if (inline) {
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
            layout(width, height) {
                lead.placeRelative(0, (head - lead.height) / 2)
                body.placeRelative(textX, (head - body.height) / 2)
                trail.placeRelative(width - trail.width, head + between)
            }
        }
    }
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
            .padding(start = inset, end = inset, top = 12.dp, bottom = 10.dp),
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
                .padding(
                    start = if (cell.start) 6.dp else 3.dp,
                    end = if (cell.end) 6.dp else 3.dp,
                    top = if (cell.top && cell.kind == CardGrid.Kind.ITEM) 6.dp else 3.dp,
                    bottom = if (cell.bottom) 6.dp else 3.dp
                ),
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
        val inset = 16.dp.toPx()
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
            if (border.alpha > 0f) clipRect { drawPath(edgePath, border, style = Stroke(stroke)) }
        }
    }
}
