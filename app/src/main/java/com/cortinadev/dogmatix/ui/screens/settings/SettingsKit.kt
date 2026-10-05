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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.IconTile
import com.cortinadev.dogmatix.ui.components.SectionTitle
import com.cortinadev.dogmatix.ui.components.TruncatedText
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.util.CardGrid

/*
 * The building blocks of the 5.0 settings screens (Settings, RomM, Save sync): the focusable row,
 * the switch, the pill button, a card header and the "card slice" that lets the cells of a lazy
 * grid read as one panel (see [CardGrid]).
 */

/**
 * A focusable settings row. Click / A runs [onClick]; while focused, D-pad left/right
 * calls [onAdjust] with -1 / +1 so steppers, switches and swatches work from a gamepad.
 *
 * @param icon leading icon; with [iconTile] it sits on an accent tile (rows that open a screen).
 * @param hintMaxLines lines of [hint]; 0 = one in landscape, two in portrait.
 * @param below extra content under the hint (a progress bar, status pills).
 */
@Composable
internal fun SettingRow(
    title: String,
    hint: String?,
    onClick: () -> Unit,
    onAdjust: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    iconTile: Boolean = false,
    iconTint: Color? = null,
    hintMaxLines: Int = 0,
    hintColor: Color? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    val source = rememberFocusSource()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lines = when {
        hintMaxLines > 0 -> hintMaxLines
        landscape -> 1
        else -> 2
    }
    Row(
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
            .padding(horizontal = if (landscape) 14.dp else 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) {
            if (iconTile) {
                IconTile(icon, size = 32.dp, tint = iconTint)
            } else {
                Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            TruncatedText(title, style = MaterialTheme.typography.bodyLarge)
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
        trailing()
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconTile(icon, size = 30.dp)
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
