package com.cortinadev.dogmatix.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.RecapShare
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.ColumnChart
import com.cortinadev.dogmatix.ui.components.EmptyState
import com.cortinadev.dogmatix.ui.components.GameCover
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.theme.consoleColor
import com.cortinadev.dogmatix.ui.theme.tabular
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.PlayHistory
import com.cortinadev.dogmatix.util.Recap
import com.cortinadev.dogmatix.util.RecapPeriod
import com.cortinadev.dogmatix.util.YearRecap
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Your year in games (7.0): what was downloaded and played in a year (the current one at first) or
 * in the last 12 months. Numbers, a column per month, the top consoles and a few highlights, and
 * a Share button that turns it into a picture card.
 */
@Composable
fun RecapScreen(viewModel: RecapViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locale = Locale.getDefault()
    var sharing by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(ui.loading) {
        if (!ui.loading) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
    }
    PublishLegend(
        if (ui.loading) null else listOf(
            LegendEntry("A", stringResource(R.string.pad_select)),
            LegendEntry("B", stringResource(R.string.pad_back)),
            LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
        )
    )

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.quick7_title), icon = R.drawable.ic_sparkle, subtitle = stringResource(R.string.quick7_subtitle))
        val recap = ui.recap
        if (ui.loading || recap == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.quick7_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "periods") {
                ToolsActions {
                    ui.years.forEachIndexed { i, year ->
                        PeriodChip(year.toString(), ui.period == RecapPeriod.Year(year), if (i == 0) Modifier.focusRequester(firstFocus) else Modifier) {
                            viewModel.select(RecapPeriod.Year(year))
                        }
                    }
                    PeriodChip(stringResource(R.string.quick7_period_last12), ui.period == RecapPeriod.Last12Months, Modifier) {
                        viewModel.select(RecapPeriod.Last12Months)
                    }
                }
            }
            if (!ui.hasAny) {
                item(key = "empty") {
                    EmptyState(
                        title = stringResource(R.string.quick7_empty_title),
                        message = stringResource(R.string.quick7_empty_message),
                        illustration = R.drawable.milou,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                return@LazyColumn
            }
            if (!recap.isEmpty) item(key = "share") {
                ToolsActions {
                    ToolAction(stringResource(R.string.quick7_share), icon = R.drawable.ic_share, tone = ActionTone.Accent) {
                        if (!sharing) {
                            sharing = true
                            scope.launch { RecapShare.share(context, recap); sharing = false }
                        }
                    }
                }
            }
            if (recap.isEmpty) {
                item(key = "quiet") {
                    EmptyState(
                        title = stringResource(R.string.quick7_quiet_title),
                        message = stringResource(R.string.quick7_quiet_message),
                        icon = R.drawable.ic_calendar_month,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                recapItems(recap, locale)
            }
            item(key = "note") {
                InfoCard(
                    listOf(stringResource(if (recap.played == null) R.string.quick7_note_no_esde else R.string.quick7_note_esde)),
                    icon = R.drawable.ic_info
                )
            }
        }
    }
}

@Composable
private fun PeriodChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ActionPill(
        label,
        onClick = onClick,
        modifier = modifier,
        icon = if (selected) R.drawable.ic_check else null,
        tone = if (selected) ActionTone.Accent else ActionTone.Neutral
    )
}

private fun LazyListScope.recapItems(recap: YearRecap, locale: Locale) {
    item(key = "kpi") { Kpis(recap, locale) }
    item(key = "months") { MonthChart(recap, locale) }
    if (recap.topConsoles.isNotEmpty()) item(key = "consoles") { TopConsoles(recap) }
    item(key = "highlights") { Highlights(recap, locale) }
}

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

@Composable
private fun Kpis(recap: YearRecap, locale: Locale) {
    val dates = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val streak = recap.longestStreak
    StatGrid(
        listOf(
            ToolStat(stringResource(R.string.quick7_stat_downloaded), count(recap.downloaded), R.drawable.ic_download),
            ToolStat(
                stringResource(R.string.quick7_stat_played), recap.played?.let(::count) ?: "—", R.drawable.ic_play_circle,
                if (recap.played == null) stringResource(R.string.quick7_played_unknown) else null
            ),
            ToolStat(stringResource(R.string.quick7_stat_size), if (recap.bytes > 0) formatBytes(recap.bytes) else "—", R.drawable.ic_storage),
            ToolStat(stringResource(R.string.quick7_stat_new), count(recap.newToYou), R.drawable.ic_new, stringResource(R.string.quick7_stat_new_hint)),
            ToolStat(
                stringResource(R.string.quick7_stat_streak),
                streak?.let { pluralStringResource(R.plurals.quick7_days, it.days, it.days) } ?: "—",
                R.drawable.ic_bolt,
                streak?.takeIf { it.days > 1 }?.let { dates.format(it.from) + " – " + dates.format(it.to) }
            ),
            ToolStat(stringResource(R.string.quick7_stat_active_days), count(recap.activeDays), R.drawable.ic_calendar_today)
        ),
        focusable = true
    )
}

@Composable
private fun MonthChart(recap: YearRecap, locale: Locale) {
    val labels = remember(recap.months, locale) {
        recap.months.map { it.month.month.getDisplayName(TextStyle.SHORT, locale).trimEnd('.') }
    }
    val busiest = remember(recap) { recap.months.indexOfFirst { it == recap.busiestMonth }.takeIf { it >= 0 } ?: recap.months.lastIndex }
    SectionPanel(stringResource(R.string.quick7_section_months), icon = R.drawable.ic_calendar_month, focusable = true) {
        ColumnChart(
            values = recap.months.map { it.count.toFloat() },
            labels = labels,
            highlight = busiest,
            valueLabels = recap.months.map { if (it.count == 0) "" else it.count.toString() }
        )
    }
}

@Composable
private fun TopConsoles(recap: YearRecap) {
    val max = recap.topConsoles.first().total.coerceAtLeast(1)
    SectionPanel(stringResource(R.string.quick7_section_consoles), icon = R.drawable.ic_controller, focusable = true) {
        recap.topConsoles.forEach { c ->
            BarRow(
                ConsoleFormatter.getConsoleShortName(c.consoleId), c.total.toFloat() / max,
                stringResource(R.string.quick7_console_line, c.downloads, c.played), consoleColor(c.consoleId)
            )
        }
    }
}

@Composable
private fun Highlights(recap: YearRecap, locale: Locale) {
    val dates = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val withYear = recap.period == RecapPeriod.Last12Months
    SectionPanel(stringResource(R.string.quick7_section_highlights), icon = R.drawable.ic_trophy, focusable = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            recap.mostPlayed?.let { game ->
                HighlightRow(
                    label = stringResource(R.string.quick7_most_played),
                    value = game.title,
                    detail = if (game.plays > 0) pluralStringResource(R.plurals.quick7_times_played, game.plays, game.plays)
                    else stringResource(R.string.quick7_last_played, dates.format(dayOf(game.lastAt))),
                    leading = { GameCover(game.consoleId, game.fileName, game.title, Modifier.size(width = 36.dp, height = 48.dp), showLabel = false) }
                )
            }
            recap.busiestMonth?.let { m ->
                HighlightRow(
                    label = stringResource(R.string.quick7_busiest_month),
                    value = Recap.monthName(m.month, withYear, locale),
                    detail = pluralStringResource(R.plurals.quick7_activity, m.count, m.count)
                )
            }
            recap.busiestDay?.let { d ->
                HighlightRow(
                    label = stringResource(R.string.quick7_busiest_day),
                    value = dates.format(d.day),
                    detail = pluralStringResource(R.plurals.quick7_activity, d.count, d.count)
                )
            }
        }
    }
}

private fun dayOf(at: Long): LocalDate = PlayHistory.dayOf(at, ZoneId.systemDefault())

/** A label over a value and a detail line, with an optional cover in front. */
@Composable
private fun HighlightRow(label: String, value: String, detail: String, leading: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(detail, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
