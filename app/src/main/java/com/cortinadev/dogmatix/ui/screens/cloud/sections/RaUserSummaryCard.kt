package com.cortinadev.dogmatix.ui.screens.cloud.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.RetroAchievementsService
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.CoverImage
import com.cortinadev.dogmatix.ui.components.IconTile
import com.cortinadev.dogmatix.ui.components.MeterBar
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.SectionTitle
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.theme.tabular
import com.cortinadev.dogmatix.util.RaErrorKind
import com.cortinadev.dogmatix.util.RaRecentGame
import com.cortinadev.dogmatix.util.RaUserSummary
import java.time.Instant
import java.time.ZoneId

/*
 * Stage B:
 *  - RetroAchievementsScreen (LazyColumn, after the "account" item):
 *      item(key = "ra5_summary") { RaUserSummaryCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), onSetUp = { editing = true }) }
 *  - Cloud hub card:
 *      RaUserSummaryCard(modifier, title = stringResource(R.string.nav_ra), compact = true,
 *          onOpen = { navigate(NavRoutes.RetroAchievements) }, onSetUp = { navigate(NavRoutes.RetroAchievements) })
 */

/**
 * The user's RetroAchievements profile: avatar, name, points, RetroPoints and rank, the recently
 * played games with their icons and progress, and (unless [compact]) the latest unlocks. Reads
 * [RetroAchievementsService.summary] and asks for a refresh when shown (RA is called at most every
 * few minutes; the Refresh action forces it). Not set up: a one-line pitch and a "Set up" action.
 *
 * @param title a card heading with the trophy tile and a status pill (the Cloud hub); null leaves
 *   it out (the RA tool already has its title).
 * @param onGameClick makes the recently played rows focusable and clickable.
 */
@Composable
fun RaUserSummaryCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    compact: Boolean = false,
    onOpen: (() -> Unit)? = null,
    onSetUp: (() -> Unit)? = null,
    onGameClick: ((RaRecentGame) -> Unit)? = null
) {
    val service = rememberRaService()
    val ready by service.accountReady.collectAsState()
    val summary by service.summary.collectAsState()
    val status by service.summaryStatus.collectAsState()
    // Also re-asks after another account was saved (the summary is cleared then). The service
    // answers from memory while the summary is fresh or a failure is recent, so this never hammers RA.
    val missing = summary == null
    LaunchedEffect(ready, missing) {
        if (ready == true) service.requestSummary()
    }
    val scheme = MaterialTheme.colorScheme

    Panel(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (title != null) CardHeader(title, ready, summary, status)

        val shown = summary
        val error = status.error
        when {
            ready == null -> Unit
            ready == false -> {
                Text(stringResource(R.string.ra5_pitch), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            shown != null -> {
                Profile(shown, compact, onGameClick)
                if (error != null && !status.loading) ErrorLine(error)
            }
            error != null && !status.loading -> ErrorLine(error)
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.ra5_profile_loading), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }

        Actions(ready, status, onOpen, onSetUp, onRefresh = {
            // A press while a refresh runs would only queue a second identical call.
            if (!service.summaryStatus.value.loading) service.requestSummary(force = true)
        })
    }
}

@Composable
private fun CardHeader(title: String, ready: Boolean?, summary: RaUserSummary?, status: RetroAchievementsService.SummaryStatus) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(R.drawable.ic_trophy)
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        when {
            ready == false -> Pill(stringResource(R.string.ra5_status_not_set_up), tone = PillTone.Neutral)
            ready == null -> Unit
            status.loading -> Pill(stringResource(R.string.ra5_status_updating), tone = PillTone.Info, icon = R.drawable.ic_sync)
            status.error != null -> Pill(stringResource(R.string.ra5_status_problem), tone = PillTone.Warning, icon = R.drawable.ic_warning)
            summary != null -> Pill(stringResource(R.string.ra5_status_connected), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
        }
    }
}

@Composable
private fun Profile(s: RaUserSummary, compact: Boolean, onGameClick: ((RaRecentGame) -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RaAvatar(s.avatarUrl, size = if (compact) 48.dp else 56.dp, contentDescription = stringResource(R.string.ra5_avatar, s.user))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.user, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val memberSince = s.memberSince
                val line = when {
                    s.richPresence.isNotEmpty() -> s.richPresence
                    s.motto.isNotEmpty() -> s.motto
                    memberSince != null -> stringResource(R.string.ra5_member_since, yearOf(memberSince))
                    else -> ""
                }
                if (line.isNotEmpty()) {
                    Text(line, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(
                label = stringResource(R.string.ra5_points),
                value = formatCount(s.points),
                icon = R.drawable.ic_trophy,
                detail = if (s.softcorePoints > 0) stringResource(R.string.ra5_softcore_points, formatCount(s.softcorePoints)) else null,
                modifier = Modifier.weight(1f)
            )
            Stat(
                label = stringResource(R.string.ra5_retropoints),
                value = formatCount(s.truePoints),
                icon = R.drawable.ic_workspace_premium,
                modifier = Modifier.weight(1f)
            )
            val rank = s.rank
            val top = s.topPercent
            Stat(
                label = stringResource(R.string.ra5_rank),
                value = if (rank != null) stringResource(R.string.ra5_rank_value, formatCount(rank)) else "–",
                icon = R.drawable.ic_military_tech,
                detail = when {
                    top != null -> stringResource(R.string.ra5_top_percent, top)
                    rank == null -> stringResource(R.string.ra5_not_ranked)
                    else -> null
                },
                modifier = Modifier.weight(1f)
            )
        }

        SectionTitle(stringResource(R.string.ra5_recently_played), icon = R.drawable.ic_history)
        val games = s.recentlyPlayed.take(if (compact) 3 else 5)
        if (games.isEmpty()) {
            Text(stringResource(R.string.ra5_no_recent), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                games.forEach { g -> key(g.gameId) { RecentGameRow(g, onGameClick) } }
            }
        }

        if (!compact && s.recentUnlocks.isNotEmpty()) {
            SectionTitle(stringResource(R.string.ra5_recent_unlocks), icon = R.drawable.ic_award)
            UnlockStrip(s)
        }
    }
}

/** A number with its label: points, RetroPoints, rank. */
@Composable
private fun Stat(label: String, value: String, icon: Int, modifier: Modifier = Modifier, detail: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, style = MaterialTheme.typography.titleLarge.tabular(), color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A recently played game: icon, title, console and when, and the share of its achievements earned. */
@Composable
private fun RecentGameRow(g: RaRecentGame, onClick: ((RaRecentGame) -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier
                    .focusRing(source, cornerRadius = 10.dp)
                    .clip(shape)
                    .clickable(interactionSource = source, indication = null) { onClick(g) }
                else Modifier
            )
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // The RA console name ("Mega Drive") picks the console colour of the placeholder.
        CoverImage(url = g.iconUrl, consoleId = g.consoleName, modifier = Modifier.size(40.dp), showLabel = false)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(g.title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val played = g.lastPlayed?.let { raRelativeTime(it) }
            val sub = listOfNotNull(g.consoleName.takeIf { it.isNotEmpty() }, played).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (g.possible > 0) {
            Column(modifier = Modifier.width(88.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (g.mastered) {
                    Pill(stringResource(R.string.ra5_award_mastered), tone = PillTone.Success, icon = R.drawable.ic_workspace_premium)
                } else {
                    Text(
                        stringResource(R.string.ra5_progress_count, g.achieved, g.possible),
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                MeterBar(g.fraction, height = 5.dp)
            }
        } else {
            Text(stringResource(R.string.ra5_no_achievements), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** The latest unlocked badges, newest first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnlockStrip(s: RaUserSummary) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s.recentUnlocks.take(8).forEach { u ->
            key(u.achievementId) {
                RaBadge(u.badgeUrl, earned = true, hardcore = u.hardcore, size = 38.dp, contentDescription = u.title)
            }
        }
    }
}

@Composable
private fun ErrorLine(kind: RaErrorKind) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = scheme.error, modifier = Modifier.size(16.dp))
        Text(raErrorText(kind), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(
    ready: Boolean?,
    status: RetroAchievementsService.SummaryStatus,
    onOpen: (() -> Unit)?,
    onSetUp: (() -> Unit)?,
    onRefresh: () -> Unit
) {
    val showRefresh = ready == true
    if (status.updatedAt > 0 && ready == true) {
        Text(
            stringResource(R.string.ra5_updated, raRelativeTime(status.updatedAt)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    val setUp = onSetUp.takeIf { ready == false }
    if (setUp == null && !showRefresh && onOpen == null) return
    // Wraps instead of squeezing on a narrow card or with large text.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (setUp != null) {
            ActionPill(stringResource(R.string.ra5_set_up), onClick = setUp, icon = R.drawable.ic_key, tone = ActionTone.Accent)
        }
        if (showRefresh) {
            ActionPill(stringResource(R.string.ra5_refresh), onClick = onRefresh, icon = R.drawable.ic_sync)
        }
        if (onOpen != null) {
            ActionPill(stringResource(R.string.ra5_open), onClick = onOpen, icon = R.drawable.ic_chevron_right, tone = ActionTone.Accent)
        }
    }
}

/** The calendar year of [millis] in the device's time zone ("2016"). */
private fun yearOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).year.toString()
