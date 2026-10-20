package com.tufein.valkyr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.ui.theme.accentInk
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.util.ConsoleFormatter

/** Width of a [CoverCard] on the Home shelves (3.8.0). */
val CoverCardWidth: Dp = 140.dp

/** Height of the cover of a [CoverCard] (a box-art ratio, about 3:4). */
val CoverCardCoverHeight: Dp = 190.dp

private val CardRadius = 16.dp

/**
 * A game as a big cover (3.8.0 Home shelves): the cover with the console-coloured placeholder,
 * its title (at most two lines: a cover card, not a settings text) and the console as a small
 * tag. The whole card is one focus target with the usual ring; A or a tap runs [onClick].
 * [lookup] off draws the placeholder only (previews and screenshots, which have no repository).
 */
@Composable
fun CoverCard(
    consoleId: String,
    fileName: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** On this device: a small check on the cover. */
    owned: Boolean = false,
    width: Dp = CoverCardWidth,
    coverHeight: Dp = CoverCardCoverHeight,
    lookup: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(CardRadius)
    Column(
        modifier = modifier
            .width(width)
            .focusScale(source, 1.04f)
            .focusRing(source, cornerRadius = CardRadius, fill = true)
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box {
            val coverModifier = Modifier.size(width, coverHeight)
            if (lookup) GameCover(consoleId, fileName, title, coverModifier, shape)
            else CoverImage(null, consoleId, coverModifier, shape)
            if (owned) OnDeviceMark(Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
            // Always two lines high, so the console tags of a shelf line up.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        ConsoleTag(consoleId, Modifier.padding(horizontal = 6.dp))
    }
}

/**
 * The card at the end of a shelf ("See all", "All 120 games"): as big as a [CoverCard], an arrow
 * and [label] (never cut; it may wrap) with an optional [detail] line under it.
 */
@Composable
fun SeeAllCoverCard(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    consoleId: String? = null,
    width: Dp = CoverCardWidth,
    coverHeight: Dp = CoverCardCoverHeight
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(CardRadius)
    val tint = consoleId?.let { lerp(scheme.surfaceContainer, consoleColor(it), if (tokens.isDark) 0.16f else 0.12f) } ?: scheme.surfaceContainer
    Column(
        modifier = modifier
            .width(width)
            .size(width, coverHeight)
            .focusScale(source, 1.04f)
            .focusRing(source, cornerRadius = CardRadius, fill = false)
            .clip(shape)
            .background(tint)
            .border(1.dp, tokens.hairline, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(scheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
        }
        Text(label, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        detail?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = accentInk(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** The console's short name in its own colour, as a small rounded tag. */
@Composable
fun ConsoleTag(consoleId: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalDogmatixTokens.current.isDark
    val base = consoleColor(consoleId)
    val ground = scheme.surfaceContainerHigh
    val colors = remember(base, ground, dark) {
        TagColors(
            background = lerp(ground, base, if (dark) 0.24f else 0.18f),
            content = if (dark) lerp(base, Color.White, 0.45f) else lerp(base, Color.Black, 0.55f)
        )
    }
    TagChip(ConsoleFormatter.getConsoleShortName(consoleId), emphasized = true, modifier = modifier, colors = colors)
}

/** A small round check on a cover: the game is on this device. */
@Composable
private fun OnDeviceMark(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier.size(24.dp).clip(CircleShape).background(scheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = androidx.compose.ui.res.stringResource(R.string.next30_on_device),
            tint = scheme.onPrimary,
            modifier = Modifier.size(16.dp)
        )
    }
}
