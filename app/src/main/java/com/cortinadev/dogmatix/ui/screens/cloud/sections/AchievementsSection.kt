package com.cortinadev.dogmatix.ui.screens.cloud.sections

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.RetroAchievementsService
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.PanelTone
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.ProgressRing
import com.cortinadev.dogmatix.ui.components.SectionTitle
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion
import com.cortinadev.dogmatix.ui.theme.tabular
import com.cortinadev.dogmatix.util.RaAchievement
import com.cortinadev.dogmatix.util.RaApi
import com.cortinadev.dogmatix.util.RaAward
import com.cortinadev.dogmatix.util.RaErrorKind
import com.cortinadev.dogmatix.util.RaGame
import com.cortinadev.dogmatix.util.RaGameProgress
import kotlinx.coroutines.CancellationException
import kotlin.math.floor

/*
 * Stage B: GameDetailsDialog, in its extra-sections slot:
 *   AchievementsSection(consoleId = rom.consoleId, fileName = rom.fileName, title = rom.name, match = state.achievements)
 */

/** What the section shows. */
private sealed interface AchievementsState {
    data object Hidden : AchievementsState
    data class Loading(val byHash: Boolean) : AchievementsState
    data class Ready(val progress: RaGameProgress, val byHash: Boolean) : AchievementsState
    data class Failed(val kind: RaErrorKind, val byHash: Boolean) : AchievementsState
}

private const val COLLAPSED_BADGES = 20

/**
 * The user's RetroAchievements progress in one library game, for the game details dialog: a
 * completion ring, points, a grid of badges (earned in colour, the rest greyed) and focusable rows
 * with each achievement's title and description (what is left first), folded to [collapsedRows]
 * with a "Show all" action. Draws nothing when RA is not set up, the game is unknown to RA, or it
 * has no achievements. The progress comes from [RetroAchievementsService.gameProgress] (cached for
 * a few minutes, so reopening the dialog does not call RA again).
 *
 * @param match the RA game when the caller already has it (`DetailsState.achievements`); otherwise
 *   it is resolved here from the hash marks, then by [title] in RA's cached game list.
 * @param framed draw the section on its own raised panel (off when the slot already frames it).
 */
@Composable
fun AchievementsSection(
    consoleId: String,
    fileName: String,
    modifier: Modifier = Modifier,
    title: String = "",
    match: Pair<RaGame, Boolean>? = null,
    collapsedRows: Int = 3,
    framed: Boolean = true
) {
    val service = rememberRaService()
    val ready by service.accountReady.collectAsState()
    if (ready != true) return
    var attempt by remember(consoleId, fileName) { mutableIntStateOf(0) }
    val state by produceState<AchievementsState>(AchievementsState.Hidden, consoleId, fileName, match?.first?.id, attempt) {
        val found = try {
            (match ?: service.resolveGame(consoleId, fileName, title))?.takeIf { it.first.id > 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (found == null) {
            value = AchievementsState.Hidden
            return@produceState
        }
        val (game, byHash) = found
        // The last copy (even a stale one) shows at once; a fresh one replaces it when it comes.
        val cached = service.cachedProgress(game.id)?.takeIf { it.hasAchievements }
        value = if (cached != null) AchievementsState.Ready(cached, byHash) else AchievementsState.Loading(byHash)
        value = try {
            val progress = service.gameProgress(game.id, force = attempt > 0)
            if (progress == null || !progress.hasAchievements) AchievementsState.Hidden else AchievementsState.Ready(progress, byHash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RetroAchievementsService.NoCredentialsException) {
            AchievementsState.Hidden
        } catch (e: RetroAchievementsService.RaApiException) {
            when {
                e.kind == RaErrorKind.NOT_FOUND -> AchievementsState.Hidden
                cached != null -> AchievementsState.Ready(cached, byHash)
                else -> AchievementsState.Failed(e.kind, byHash)
            }
        } catch (e: Exception) {
            if (cached != null) AchievementsState.Ready(cached, byHash) else AchievementsState.Failed(RaErrorKind.BAD_RESPONSE, byHash)
        }
    }

    val current = state
    if (current is AchievementsState.Hidden) return
    val content: @Composable () -> Unit = {
        when (current) {
            is AchievementsState.Loading -> LoadingBody(current.byHash)
            is AchievementsState.Failed -> FailedBody(current.kind, current.byHash, onRetry = { attempt++ })
            is AchievementsState.Ready -> ReadyBody(current.progress, current.byHash, collapsedRows)
            AchievementsState.Hidden -> Unit
        }
    }
    if (framed) {
        Panel(
            modifier = modifier.fillMaxWidth(),
            tone = PanelTone.Raised,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
        ) { content() }
    } else {
        Box(modifier = modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun Header(byHash: Boolean, award: RaAward = RaAward.NONE) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionTitle(stringResource(R.string.ra5_section_title), modifier = Modifier.weight(1f), icon = R.drawable.ic_trophy)
        if (!byHash) Pill(stringResource(R.string.ra5_probable), tone = PillTone.Warning)
        when (award) {
            RaAward.MASTERED -> Pill(stringResource(R.string.ra5_award_mastered), tone = PillTone.Success, icon = R.drawable.ic_workspace_premium)
            RaAward.COMPLETED -> Pill(stringResource(R.string.ra5_award_completed), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
            RaAward.BEATEN -> Pill(stringResource(R.string.ra5_award_beaten), tone = PillTone.Accent, icon = R.drawable.ic_award)
            RaAward.BEATEN_SOFTCORE -> Pill(stringResource(R.string.ra5_award_beaten_softcore), tone = PillTone.Neutral, icon = R.drawable.ic_award)
            RaAward.NONE -> Unit
        }
    }
}

@Composable
private fun LoadingBody(byHash: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header(byHash)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.ra5_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FailedBody(kind: RaErrorKind, byHash: Boolean, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header(byHash)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = scheme.error, modifier = Modifier.size(18.dp))
            Text(raErrorText(kind), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            ActionPill(stringResource(R.string.ra5_retry), onClick = onRetry, icon = R.drawable.ic_retry)
        }
    }
}

@Composable
private fun ReadyBody(p: RaGameProgress, byHash: Boolean, collapsedRows: Int) {
    val reduce = LocalReduceMotion.current
    var expanded by rememberSaveable(p.gameId) { mutableStateOf(false) }
    val rows = remember(p) { RaApi.rowsOrder(p.achievements) }
    val visibleRows = if (expanded) rows else rows.take(collapsedRows.coerceAtLeast(1))
    val foldable = rows.size > visibleRows.size || p.achievements.size > COLLAPSED_BADGES || expanded

    Column(
        modifier = if (reduce) Modifier else Modifier.animateContentSize(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Header(byHash, p.award)

        // Wide (landscape dialog): summary and badges side by side, to keep the section short.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth >= 560.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ProgressSummary(p, Modifier.width(250.dp))
                    Box(modifier = Modifier.weight(1f)) { BadgeGrid(p.achievements, expanded) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProgressSummary(p, Modifier.fillMaxWidth())
                    BadgeGrid(p.achievements, expanded)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            visibleRows.forEach { a -> key(a.id) { AchievementRow(a) } }
        }

        if (foldable) {
            ActionPill(
                label = if (expanded) stringResource(R.string.ra5_show_fewer) else stringResource(R.string.ra5_show_all, rows.size),
                onClick = { expanded = !expanded },
                icon = if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
        }
    }
}

/** The completion ring with the earned count, points and hardcore count beside it. */
@Composable
private fun ProgressSummary(p: RaGameProgress, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ProgressRing(fraction = p.completion, size = 72.dp, stroke = 7.dp) {
            // Whole percents rounded down: 249 of 250 must not read "100 %".
            val shown = if (p.total > 0) (p.earned * 100 / p.total) / 100f else floor(p.completion * 100f) / 100f
            Text(formatPercent(shown), style = MaterialTheme.typography.titleSmall.tabular(), color = scheme.onSurface, maxLines = 1)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                pluralStringResource(R.plurals.ra5_earned_of, p.total, p.earned, p.total),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface
            )
            Text(
                pluralStringResource(R.plurals.ra5_points_of, p.points, formatCount(p.earnedPoints), formatCount(p.points)),
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = scheme.onSurfaceVariant
            )
            if (p.earnedHardcore > 0) {
                Pill(stringResource(R.string.ra5_hardcore_count, p.earnedHardcore), tone = PillTone.Accent, icon = R.drawable.ic_award)
            }
        }
    }
}

/** Every badge in RA's order; folded to the first [COLLAPSED_BADGES] and a "+N" tile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BadgeGrid(achievements: List<RaAchievement>, expanded: Boolean) {
    val shown = if (expanded) achievements else achievements.take(COLLAPSED_BADGES)
    val hidden = achievements.size - shown.size
    val badge = 34.dp
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        shown.forEach { a ->
            key(a.id) { RaBadge(a.badgeUrl, a.earned, a.earnedHardcore, badge, contentDescription = a.title) }
        }
        if (hidden > 0) {
            Box(
                modifier = Modifier
                    .size(badge)
                    .clip(RoundedCornerShape(badge * 0.2f))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.ra5_more_count, hidden),
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** One achievement: badge, title, description (A / tap unfolds a long one), when earned or how rare, points. */
@Composable
private fun AchievementRow(a: RaAchievement) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    var open by remember(a.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(source, cornerRadius = 10.dp)
            .clip(shape)
            .clickable(interactionSource = source, indication = null) { open = !open }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RaBadge(a.badgeUrl, a.earned, a.earnedHardcore, 44.dp, contentDescription = null)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                a.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (a.earned) scheme.onSurface else scheme.onSurface.copy(alpha = 0.85f),
                maxLines = if (open) 3 else 1,
                overflow = TextOverflow.Ellipsis
            )
            if (a.description.isNotEmpty()) {
                Text(
                    a.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = if (open) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            statusLine(a)?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (a.earned) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill(formatCount(a.points), tone = if (a.earned) PillTone.Accent else PillTone.Neutral, icon = R.drawable.ic_star)
            if (a.missable && !a.earned) Pill(stringResource(R.string.ra5_missable), tone = PillTone.Warning)
        }
    }
}

/** "Earned 3 days ago", "Earned in hardcore …", or for what is left "12 % of players have it". */
@Composable
private fun statusLine(a: RaAchievement): String? {
    val at = a.earnedAt
    return when {
        a.earned && at != null -> stringResource(
            if (a.earnedHardcore) R.string.ra5_earned_hardcore_at else R.string.ra5_earned_at,
            raRelativeTime(at)
        )
        a.earned -> stringResource(if (a.earnedHardcore) R.string.ra5_earned_hardcore else R.string.ra5_earned)
        a.rarity != null -> stringResource(R.string.ra5_rarity, formatPercent(a.rarity))
        else -> null
    }
}
