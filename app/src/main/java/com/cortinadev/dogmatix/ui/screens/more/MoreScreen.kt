package com.cortinadev.dogmatix.ui.screens.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.components.ContentEntry
import com.cortinadev.dogmatix.ui.components.IconTile
import com.cortinadev.dogmatix.ui.components.NavChevron
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.tvSized
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.theme.Dimens
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.accentInk

/**
 * 3.8.0: the More tab. Everything that is not one of the four sections, as big rounded rows in
 * groups (see [MoreMenu]); two rows side by side when there is room. A / a tap opens the screen.
 * Titles and the line under them always show in full: they wrap, they are never cut off.
 */
@Composable
fun MoreScreen(onOpen: (NavRoutes) -> Unit, modifier: Modifier = Modifier) {
    val firstFocus = remember { FocusRequester() }
    // 3.9.0: back from a screen opened here, the cursor returns to the row that opened it
    // (saved with the back stack entry), not to the top of the list.
    var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
    // Like rememberInitialFocus, but not after a ZL / ZR section switch: the shell then parks the
    // cursor out of sight on purpose and the next D-pad press brings it back on that same row.
    LaunchedEffect(Unit) {
        repeat(5) {
            withFrameNanos { }
            if (Gamepad.pointerHidden.value) return@LaunchedEffect
            if (runCatching { firstFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    ContentEntry(NavRoutes.More.route, firstFocus)
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columns = MoreMenu.columnsFor(maxWidth.value.toInt())
        val entryRoute = lastOpened ?: MoreMenu.groups.first().items.first().route.route
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Dimens.gutter, end = Dimens.gutter, top = 12.dp, bottom = Dimens.section),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.shell38_more),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() }
                )
            }
            MoreMenu.groups.forEach { group ->
                item(key = "group:${group.id}") { MoreGroupTitle(stringResource(group.title), group.icon) }
                MoreMenu.lines(group, columns).forEach { line ->
                    item(key = "line:${line.first().route.route}") {
                        // Equal heights side by side: the taller row sets the line's height.
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.gap)
                        ) {
                            line.forEach { item ->
                                MoreRow(
                                    item = item,
                                    onClick = { lastOpened = item.route.route; onOpen(item.route) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .then(if (item.route.route == entryRoute) Modifier.focusRequester(firstFocus) else Modifier)
                                )
                            }
                            // An odd last row keeps the width of its neighbours above.
                            repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** Group heading: small accent text with the group's icon (not focusable, never cut off). */
@Composable
private fun MoreGroupTitle(text: String, icon: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 2.dp)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = accentInk(), modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = accentInk())
    }
}

/** One big rounded row: icon tile, title, one line of help, the "opens a screen" arrow. */
@Composable
internal fun MoreRow(item: MoreItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(Dimens.cardRadius)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = tvSized(72.dp))
            .background(scheme.surfaceContainer, shape)
            .border(1.dp, tokens.hairline, shape)
            .focusRing(source, cornerRadius = Dimens.cardRadius)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = Dimens.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        IconTile(item.route.icon, size = tvSized(Dimens.rowIcon))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(item.title), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            Text(stringResource(item.hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        NavChevron()
    }
}
