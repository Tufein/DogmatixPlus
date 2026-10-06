package com.cortinadev.dogmatix.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.QuickMenuEvent
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion
import com.cortinadev.dogmatix.ui.theme.accentInk
import com.cortinadev.dogmatix.util.QuickMenu
import com.cortinadev.dogmatix.util.QuickMenuItem
import kotlin.math.hypot
import kotlin.math.roundToInt

private val OuterRadius = 150.dp
private val InnerRadius = 46.dp
private val ItemRadius = 102.dp
private val ItemWidth = 84.dp

/** Label and icon of a quick menu entry. */
private fun QuickMenuItem.labelRes(): Int = when (this) {
    QuickMenuItem.SEARCH -> R.string.menu8_search
    QuickMenuItem.SEARCH_ALL -> R.string.menu8_search_all
    QuickMenuItem.SURPRISE -> R.string.menu8_surprise
    QuickMenuItem.DOWNLOADS -> R.string.menu8_downloads
    QuickMenuItem.PAUSE_ALL -> R.string.menu8_pause_all
    QuickMenuItem.RESUME_ALL -> R.string.menu8_resume_all
    QuickMenuItem.TOOLS -> R.string.menu8_tools
    QuickMenuItem.SETTINGS -> R.string.menu8_settings
}

private fun QuickMenuItem.iconRes(): Int = when (this) {
    QuickMenuItem.SEARCH -> R.drawable.ic_search
    QuickMenuItem.SEARCH_ALL -> R.drawable.ic_manage_search
    QuickMenuItem.SURPRISE -> R.drawable.ic_shuffle
    QuickMenuItem.DOWNLOADS -> R.drawable.ic_download
    QuickMenuItem.PAUSE_ALL -> R.drawable.ic_pause
    QuickMenuItem.RESUME_ALL -> R.drawable.ic_play_arrow
    QuickMenuItem.TOOLS -> R.drawable.ic_build
    QuickMenuItem.SETTINGS -> R.drawable.ic_settings
}

/** The legend entry for SELECT on screens where its short press does something: "<label> · hold: menu". */
@Composable
fun selectLegendEntry(labelRes: Int): LegendEntry =
    LegendEntry("SELECT", stringResource(R.string.menu8_pad_hold, stringResource(labelRes)))

/**
 * 8.0: the controller quick menu, a ring of shortcuts over the whole app. Opened by holding SELECT
 * ([Gamepad.quickMenuOpen]); the stick or D-pad picks a slice, A or letting go of SELECT activates
 * it, B or SELECT again closes. Touch: tap a slice, tap outside the ring to close. Host it as the
 * last child of the app's root box so it covers every screen.
 */
@Composable
fun QuickMenuOverlay(
    queueHeld: Boolean,
    onSearch: () -> Unit,
    onSearchAll: () -> Unit,
    onSurprise: () -> Unit,
    onDownloads: () -> Unit,
    onSetQueueHeld: (Boolean) -> Unit,
    onTools: () -> Unit,
    onSettings: () -> Unit
) {
    val open by Gamepad.quickMenuOpen.collectAsState()
    if (!open) return

    val items = remember(queueHeld) { QuickMenu.items(queueHeld) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val actions by rememberUpdatedState(
        mapOf(
            QuickMenuItem.SEARCH to onSearch,
            QuickMenuItem.SEARCH_ALL to onSearchAll,
            QuickMenuItem.SURPRISE to onSurprise,
            QuickMenuItem.DOWNLOADS to onDownloads,
            QuickMenuItem.PAUSE_ALL to { onSetQueueHeld(true) },
            QuickMenuItem.RESUME_ALL to { onSetQueueHeld(false) },
            QuickMenuItem.TOOLS to onTools,
            QuickMenuItem.SETTINGS to onSettings
        )
    )
    val activate: (Int) -> Unit = { index ->
        Gamepad.closeQuickMenu()
        items.getOrNull(index)?.let { actions[it]?.invoke() }
    }
    val currentActivate by rememberUpdatedState(activate)

    BackHandler { Gamepad.closeQuickMenu() }
    LaunchedEffect(Unit) {
        Gamepad.quickMenuEvents.collect { event ->
            when (event) {
                is QuickMenuEvent.Move -> selected = QuickMenu.step(selected, event.direction, items.size)
                // Letting go without a choice keeps the menu open for the D-pad and A.
                QuickMenuEvent.Activate, QuickMenuEvent.Release -> selected?.let { currentActivate(it) }
            }
        }
    }
    LaunchedEffect(Unit) {
        Gamepad.quickMenuStick.collect { stick ->
            stick?.let { QuickMenu.sliceAt(it.x, it.y, items.size) }?.let { selected = it }
        }
    }

    // Open: scrim fades in and the ring grows from 85 %. Read only in the draw phase.
    val reduce = LocalReduceMotion.current
    val appear = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.spec(reduce, Motion.MEDIUM)) }
    // The highlighted wedge turns towards the selection (the short way round).
    val wedge = remember { Animatable(0f) }
    val wedgePlaced = remember { booleanArrayOf(false) }
    LaunchedEffect(selected) {
        val index = selected ?: return@LaunchedEffect
        val target = 360f * index / items.size
        var delta = (target - wedge.value) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        // The first selection appears in place; later ones turn the wedge.
        if (reduce || !wedgePlaced[0]) wedge.snapTo(wedge.value + delta)
        else wedge.animateTo(wedge.value + delta, Motion.spec(false, Motion.FAST))
        wedgePlaced[0] = true
    }

    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val ink = accentInk()
    val ringFill = tokens.card.takeIf { it != Color.Transparent } ?: scheme.surfaceContainer
    val ringEdge = if (tokens.hairline != Color.Transparent) tokens.hairline else scheme.outlineVariant
    val wedgeFill = scheme.primary.copy(alpha = 0.22f)
    val wedgeEdge = scheme.primary
    val hasSelection = selected != null
    val padConnected by Gamepad.connected.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.55f * appear.value)) }
            .pointerInput(items) {
                detectTapGestures { pos ->
                    val dx = pos.x - size.width / 2f
                    val dy = pos.y - size.height / 2f
                    val distance = hypot(dx, dy)
                    if (distance in InnerRadius.toPx()..OuterRadius.toPx()) {
                        QuickMenu.sliceAt(dx, dy, items.size, deadZone = 0f)?.let { currentActivate(it) }
                    } else Gamepad.closeQuickMenu()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(OuterRadius * 2)
                .graphicsLayer {
                    val p = appear.value
                    alpha = p
                    scaleX = 0.85f + 0.15f * p
                    scaleY = 0.85f + 0.15f * p
                }
                .drawBehind {
                    val outer = OuterRadius.toPx()
                    val inner = InnerRadius.toPx()
                    drawCircle(ringFill, radius = outer)
                    drawCircle(ringEdge, radius = outer - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                    if (hasSelection) {
                        val sweep = 360f / items.size
                        val start = wedge.value - 90f - sweep / 2f
                        val o = Offset(center.x - outer, center.y - outer)
                        drawArc(wedgeFill, start, sweep, useCenter = true, topLeft = o, size = Size(outer * 2, outer * 2))
                        val stroke = 3.dp.toPx()
                        val r = outer - stroke / 2f
                        drawArc(
                            wedgeEdge, start + 2f, sweep - 4f, useCenter = false,
                            topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2), style = Stroke(stroke)
                        )
                    }
                    drawCircle(ringFill, radius = inner)
                    drawCircle(ringEdge, radius = inner, style = Stroke(1.dp.toPx()))
                },
            contentAlignment = Alignment.Center
        ) {
            items.forEachIndexed { index, item ->
                val label = stringResource(item.labelRes())
                val isSelected = index == selected
                val (ux, uy) = QuickMenu.positionOf(index, items.size)
                val tint = if (isSelected) ink else scheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .width(ItemWidth)
                        .offset { IntOffset((ux * ItemRadius.toPx()).roundToInt(), (uy * ItemRadius.toPx()).roundToInt()) }
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            onClick(label) { currentActivate(index); true }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(painterResource(item.iconRes()), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) ink else scheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Text(
                stringResource(R.string.menu8_title),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(InnerRadius * 2 - 8.dp)
            )
        }
        Text(
            stringResource(if (padConnected) R.string.menu8_hint_pad else R.string.menu8_hint_touch),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = OuterRadius + 22.dp)
                .padding(horizontal = 16.dp)
                .graphicsLayer { alpha = appear.value }
        )
    }
}
