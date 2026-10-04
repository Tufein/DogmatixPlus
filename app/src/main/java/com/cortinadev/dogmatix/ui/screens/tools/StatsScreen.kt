package com.cortinadev.dogmatix.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.CollectionDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dao.FavouriteDao
import com.cortinadev.dogmatix.data.service.DownloadLog
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DownloadStats
import com.cortinadev.dogmatix.util.DownloadStatsSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatsUiState(
    val loading: Boolean = true,
    val downloads: DownloadStatsSummary? = null,
    val libraryGames: Int = 0,
    val favourites: Int = 0,
    val collections: Int = 0,
    val newPerWeek: List<Int> = emptyList()
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val log: DownloadLog,
    private val fileDao: DownloadableFileDao,
    private val favouriteDao: FavouriteDao,
    private val collectionDao: CollectionDao
) : ViewModel() {
    private val _ui = MutableStateFlow(StatsUiState())
    val ui: StateFlow<StatsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            _ui.value = StatsUiState(
                loading = false,
                downloads = DownloadStats.summarize(log.entries(), now),
                libraryGames = fileDao.getFilesCount(),
                favourites = favouriteDao.getAll().size,
                collections = collectionDao.observeAll().first().size,
                newPerWeek = DownloadStats.newPerWeek(fileDao.firstSeenSince(now - 8 * 7 * 24 * 3_600_000L), now)
            )
        }
    }
}

/** Statistics: what was downloaded per month and per console, and how the library grows. */
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_stats))
        val d = ui.downloads
        if (ui.loading || d == null) { Row(Modifier.padding(16.dp)) { CircularProgressIndicator() }; return@Column }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            item {
                InfoCard(listOf(
                    stringResource(R.string.stats_downloads_total, d.total, formatBytes(d.totalBytes)),
                    stringResource(R.string.stats_library, ui.libraryGames, ui.favourites, ui.collections)
                ))
            }
            item { SectionHeader(stringResource(R.string.stats_per_month)) }
            val maxMonth = d.perMonth.maxOfOrNull { it.third }?.coerceAtLeast(1) ?: 1
            d.perMonth.forEach { (month, count, bytes) ->
                item(key = "m$month") { Bar(month, bytes.toFloat() / maxMonth, if (count == 0) "—" else "$count · ${formatBytes(bytes)}") }
            }
            if (d.perConsole.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.stats_per_console)) }
                val maxConsole = d.perConsole.first().third.coerceAtLeast(1)
                d.perConsole.take(10).forEach { (console, count, bytes) ->
                    item(key = "c$console") { Bar(ConsoleFormatter.getConsoleShortName(console), bytes.toFloat() / maxConsole, "$count · ${formatBytes(bytes)}") }
                }
            }
            item { SectionHeader(stringResource(R.string.stats_new_per_week), stringResource(R.string.stats_new_per_week_hint)) }
            val maxNew = ui.newPerWeek.maxOrNull()?.coerceAtLeast(1) ?: 1
            ui.newPerWeek.forEachIndexed { i, n ->
                item(key = "w$i") {
                    val label = if (i == ui.newPerWeek.lastIndex) stringResource(R.string.stats_this_week) else (ui.newPerWeek.lastIndex - i).let { w -> pluralStringResource(R.plurals.stats_weeks_ago, w, w) }
                    Bar(label, n.toFloat() / maxNew, n.toString())
                }
            }
        }
    }
}

@Composable
private fun Bar(label: String, fraction: Float, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(26.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
        Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 5.dp).clip(RoundedCornerShape(4.dp)).background(scheme.surfaceContainerHigh)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(scheme.primary))
        }
        Text(value, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.width(120.dp))
    }
}
