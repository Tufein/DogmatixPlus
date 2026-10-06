package com.cortinadev.dogmatix.ui.components

import com.cortinadev.dogmatix.ui.theme.accentInk
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion
import com.cortinadev.dogmatix.ui.theme.tabular
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import com.cortinadev.dogmatix.ui.common.Gamepad
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.screens.cloud.saves.CloudStatusIndicator

/** App title with the version tucked under it: tiny, faint, right-aligned to the wordmark. */
@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        val title = stringResource(R.string.topbar_title)
        val accent = MaterialTheme.colorScheme.primary
        // "Dogmatix+": the plus in the accent colour.
        Text(
            buildAnnotatedString {
                if (title.endsWith("+")) {
                    append(title.dropLast(1))
                    withStyle(SpanStyle(color = accent)) { append("+") }
                } else append(title)
            },
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)
        )
        // Zero layout height + unbounded measure: the wordmark keeps its original centering in
        // the row (as if this line did not exist) and the version overflows below it, pulled up
        // 5dp so it hugs the logo.
        Text(
            BuildConfig.VERSION_NAME,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .height(0.dp)
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .offset(y = (-5).dp)
        )
    }
}

/** The tab a route belongs to: the screens opened from Settings keep Settings lit. */
fun tabRouteFor(route: String): String =
    if (NavRoutes.tabs.any { it.route == route }) route else NavRoutes.Settings.route

/** Landscape header: title, numbered section tabs (ZL / ZR), rescan status. */
@Composable
fun TopTabs(currentRoute: String, onSelect: (NavRoutes) -> Unit, activeDownloads: Int = 0, onOpenCloud: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val litRoute = tabRouteFor(currentRoute)
    val tabHeight = tvSized(48.dp)   // 8.0 TV mode: bigger targets for the D-pad and the sofa
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = tabHeight)
            .padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Wordmark(modifier = Modifier.padding(end = 20.dp))
        NavRoutes.tabs.forEachIndexed { index, route ->
            val selected = route.route == litRoute
            val source = rememberFocusSource()
            val underline = rememberSelectionProgress(selected)
            Box(
                modifier = Modifier
                    .heightIn(min = tabHeight)
                    .focusRequester(Gamepad.tabFocus.getValue(route.route))
                    .clip(RoundedCornerShape(8.dp))
                    .focusRing(source, 8.dp)
                    .clickable(interactionSource = source, indication = null) { onSelect(route) }
                    .drawBehind {
                        val p = underline()
                        if (p > 0f) {
                            // Grows from the middle when the tab becomes the current one.
                            val h = 3.dp.toPx()
                            val w = size.width * 0.7f * p
                            drawRoundRect(
                                color = scheme.primary,
                                topLeft = Offset((size.width - w) / 2, size.height - h),
                                size = Size(w, h),
                                cornerRadius = CornerRadius(h / 2)
                            )
                        }
                    }
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "0${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) scheme.primary else scheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(route.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) scheme.onSurface else scheme.onSurfaceVariant
                    )
                    if (route == NavRoutes.Downloads && activeDownloads > 0) CountBadge(activeDownloads)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        CloudStatusIndicator(onClick = onOpenCloud, modifier = Modifier.padding(end = 8.dp))
        RescanIndicator(modifier = Modifier.widthIn(max = 260.dp))
    }
    HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
}

/** 0 → 1 when [selected] turns on (and back), animated and read only while drawing. */
@Composable
private fun rememberSelectionProgress(selected: Boolean): () -> Float {
    val reduce = LocalReduceMotion.current
    val progress = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected, reduce) {
        val target = if (selected) 1f else 0f
        if (reduce) progress.snapTo(target) else progress.animateTo(target, Motion.spec(false, Motion.MEDIUM))
    }
    return { progress.value }
}

/** The number of running downloads on the Downloads tab. */
@Composable
private fun CountBadge(count: Int) {
    val scheme = MaterialTheme.colorScheme
    Text(
        count.toString(),
        style = MaterialTheme.typography.labelSmall.tabular(),
        color = scheme.onPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(scheme.primary)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/** Portrait header: title plus rescan status. */
@Composable
fun PortraitHeader(trailing: @Composable (() -> Unit)? = null, onOpenCloud: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Wordmark()
        Spacer(Modifier.weight(1f))
        CloudStatusIndicator(onClick = onOpenCloud, modifier = Modifier.padding(end = 8.dp))
        RescanIndicator(modifier = Modifier.widthIn(max = 200.dp))
        trailing?.let { Spacer(Modifier.padding(start = 12.dp)); it() }
    }
}

/** Portrait bottom bar with the four sections. */
@Composable
fun BottomTabs(currentRoute: String, activeDownloads: Int, onSelect: (NavRoutes) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(scheme.background)
        ) {
            val litRoute = tabRouteFor(currentRoute)
            NavRoutes.tabs.forEach { route ->
                val selected = route.route == litRoute
                val source = rememberFocusSource()
                val pill = rememberSelectionProgress(selected)
                val pillColor = scheme.primaryContainer
                val tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRequester(Gamepad.tabFocus.getValue(route.route))
                        .clip(RoundedCornerShape(8.dp))
                        .focusRing(source)
                        .clickable(interactionSource = source, indication = null) { onSelect(route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.drawBehind {
                            val p = pill()
                            if (p > 0f) {
                                // A pill behind the icon that widens in when the tab becomes current.
                                val h = 30.dp.toPx()
                                val w = 56.dp.toPx() * (0.5f + 0.5f * p)
                                drawRoundRect(
                                    pillColor.copy(alpha = pillColor.alpha * p),
                                    topLeft = Offset((size.width - w) / 2, (size.height - h) / 2),
                                    size = Size(w, h),
                                    cornerRadius = CornerRadius(h / 2)
                                )
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(painterResource(route.icon), contentDescription = stringResource(route.labelRes), tint = tint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).size(22.dp))
                        if (route == NavRoutes.Downloads && activeDownloads > 0) {
                            Text(
                                activeDownloads.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onPrimary,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(start = 14.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(scheme.primary)
                                    .padding(horizontal = 5.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(route.labelRes), style = MaterialTheme.typography.labelSmall, color = if (selected) scheme.onSurface else scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun legendFor(route: String): List<LegendEntry> {
    val section = LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
    val back = LegendEntry("B", stringResource(R.string.pad_back))
    return when (route) {
        NavRoutes.Home.route -> listOf(
            LegendEntry("A", stringResource(R.string.pad_download)), back,
            LegendEntry("X", stringResource(R.string.pad_filters)),
            LegendEntry("Y", stringResource(R.string.pad_search)), section
        )
        // Downloads fits four entries on one line in 4:3; SELECT (multi-selection) earns B's slot.
        NavRoutes.Downloads.route -> listOf(
            LegendEntry("A", stringResource(R.string.pad_retry)),
            LegendEntry("X", stringResource(R.string.pad_delete)),
            LegendEntry("SELECT", stringResource(R.string.pad_tick)), section
        )
        NavRoutes.Sources.route -> listOf(LegendEntry("A", stringResource(R.string.pad_open)), back, section)
        NavRoutes.Duplicates.route -> listOf(LegendEntry("A", stringResource(R.string.pad_delete)), back, section)
        NavRoutes.Overview.route -> listOf(LegendEntry("A", stringResource(R.string.pad_rescan)), back, section)
        NavRoutes.Tools.route -> listOf(LegendEntry("A", stringResource(R.string.pad_open)), back, section)
        NavRoutes.Sets.route -> listOf(LegendEntry("A", stringResource(R.string.pad_apply)), back, section)
        NavRoutes.Storage.route -> listOf(LegendEntry("A", stringResource(R.string.pad_delete)), back, section)
        NavRoutes.Wishlist.route -> listOf(LegendEntry("A", stringResource(R.string.pad_open)), back, section)
        NavRoutes.Collections.route, NavRoutes.Switch.route, NavRoutes.Dat.route, NavRoutes.Bios.route, NavRoutes.Stats.route, NavRoutes.RetroAchievements.route, NavRoutes.Profiles.route, NavRoutes.Frontends.route, NavRoutes.Cloud.route, NavRoutes.CollectionGoals.route, NavRoutes.History.route, NavRoutes.Health.route, NavRoutes.Recap.route, NavRoutes.BestGames.route, NavRoutes.FrontendMetadata.route, NavRoutes.BetterVersions.route, NavRoutes.FreeSpace.route -> listOf(LegendEntry("A", stringResource(R.string.pad_open)), back, section)
        NavRoutes.Files.route -> listOf(LegendEntry("A", stringResource(R.string.pad_open)), LegendEntry("B", stringResource(R.string.files_up)), section)
        NavRoutes.Settings.route, NavRoutes.Romm.route, NavRoutes.SaveSync.route, NavRoutes.CloudBackup.route -> listOf(
            LegendEntry("A", stringResource(R.string.pad_change)),
            LegendEntry("◀ ▶", stringResource(R.string.pad_adjust)), back, section
        )
        else -> listOf(back)
    }
}

@Composable
fun NoGamepadHint(trailing: @Composable (() -> Unit)? = null) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.pad_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}
