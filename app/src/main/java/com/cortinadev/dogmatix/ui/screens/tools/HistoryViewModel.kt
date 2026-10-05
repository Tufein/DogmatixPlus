package com.cortinadev.dogmatix.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.service.PlayHistoryService
import com.cortinadev.dogmatix.util.DayGroup
import com.cortinadev.dogmatix.util.HistoryEvent
import com.cortinadev.dogmatix.util.HistoryFilter
import com.cortinadev.dogmatix.util.PlayHistory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** What the Play history screen shows. */
data class HistoryUi(
    val loading: Boolean = true,
    val filter: HistoryFilter = HistoryFilter.ALL,
    /** Events of the current filter, all pages. */
    val total: Int = 0,
    /** The pages loaded so far, grouped by day. */
    val groups: List<DayGroup> = emptyList(),
    val shown: Int = 0,
    val hasMore: Boolean = false,
    /** Whether anything happened at all, whatever the filter. */
    val hasAny: Boolean = false,
    val dailyCounts: List<Int> = emptyList(),
    val dailyDates: List<LocalDate> = emptyList(),
    val weeklyCounts: List<Int> = emptyList(),
    val thisWeek: Int = 0,
    /** True when ES-DE is set up (its play dates are in the list). */
    val hasPlays: Boolean = false
)

@HiltViewModel
class HistoryViewModel @Inject constructor(private val service: PlayHistoryService) : ViewModel() {

    private val filter = MutableStateFlow(HistoryFilter.ALL)
    private val limit = MutableStateFlow(PlayHistory.PAGE_SIZE)
    private val base = MutableStateFlow<Base?>(null)

    private class Base(val downloads: List<HistoryEvent>, val plays: List<HistoryEvent>?, val rommReady: Boolean)

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            base.value = Base(service.downloads(), service.plays(), service.rommReady())
        }
    }

    fun setFilter(value: HistoryFilter) {
        filter.value = value
        limit.value = PlayHistory.PAGE_SIZE
    }

    /** Called when the list nears its end. */
    fun loadMore() { limit.value = limit.value + PlayHistory.PAGE_SIZE }

    val ui: StateFlow<HistoryUi> = combine(base, filter, limit, savesFlow()) { b, f, l, saves ->
        if (b == null) return@combine HistoryUi(loading = true, filter = f)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val all = PlayHistory.merge(b.downloads, b.plays.orEmpty(), if (b.rommReady) saves else emptyList(), now)
        val filtered = PlayHistory.filter(all, f)
        val end = PlayHistory.pageEnd(filtered.size, 0, l)
        val weekly = PlayHistory.weeklyCounts(filtered, now, zone)
        HistoryUi(
            loading = false, filter = f, total = filtered.size,
            groups = PlayHistory.groupByDay(filtered.take(end), now, zone),
            shown = end, hasMore = end < filtered.size, hasAny = all.isNotEmpty(),
            dailyCounts = PlayHistory.dailyCounts(filtered, now, zone),
            dailyDates = PlayHistory.dailyDates(now, zone),
            weeklyCounts = weekly, thisWeek = weekly.lastOrNull() ?: 0,
            hasPlays = b.plays != null
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryUi())

    private fun savesFlow() = service.saves().catch { emit(emptyList()) }
}
