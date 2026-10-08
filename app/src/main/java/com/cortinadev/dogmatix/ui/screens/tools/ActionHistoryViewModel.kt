package com.cortinadev.dogmatix.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.service.ActionLogService
import com.cortinadev.dogmatix.data.service.ActionUndoService
import com.cortinadev.dogmatix.data.service.OperationHistoryService
import com.cortinadev.dogmatix.data.service.UndoResult
import com.cortinadev.dogmatix.util.ActionDay
import com.cortinadev.dogmatix.util.ActionEntry
import com.cortinadev.dogmatix.util.ActionFilter
import com.cortinadev.dogmatix.util.ActionHistory
import com.cortinadev.dogmatix.util.UndoAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

/** The route of the action history screen (for NavRoutes and the link in Trash and recovery). */
const val ACTION_HISTORY_ROUTE = "action_history"

/** One line of the screen: [key] is unique in the list; [undo] the way back that works right now, if any. */
data class ActionRowUi(val key: String, val entry: ActionEntry, val undo: UndoAction?)

data class ActionDayUi(val day: ActionDay, val rows: List<ActionRowUi>)

/** What the action history screen shows. */
data class ActionHistoryUi(
    val loading: Boolean = true,
    val filter: ActionFilter = ActionFilter.ALL,
    val query: String = "",
    val groups: List<ActionDayUi> = emptyList(),
    /** Lines of the current filter and search. */
    val total: Int = 0,
    /** Every recorded line, what "Clear history" removes. */
    val all: Int = 0,
    /** The line whose way back is running (its button waits). */
    val busyKey: String? = null
) {
    val hasAny: Boolean get() = all > 0
    val rows: Map<String, ActionRowUi> by lazy { groups.flatMap { it.rows }.associateBy { it.key } }
}

@OptIn(FlowPreview::class)
@HiltViewModel
class ActionHistoryViewModel @Inject constructor(
    private val log: ActionLogService,
    private val undoService: ActionUndoService,
    journal: OperationHistoryService,
    private val profiles: com.cortinadev.dogmatix.data.service.ProfileService
) : ViewModel() {

    private val filter = MutableStateFlow(ActionFilter.ALL)
    private val query = MutableStateFlow("")
    private val busy = MutableStateFlow<String?>(null)
    /** The ways back that work right now, by line id. */
    private val available = MutableStateFlow<Map<String, UndoAction>>(emptyMap())

    init {
        // A line written or a trash operation changed (also from Trash and recovery): check the buttons again.
        viewModelScope.launch {
            combine(log.entries, journal.entries) { lines, _ -> lines }.debounce(300).collect { lines ->
                if (lines != null) available.value = check(lines.asReversed())
            }
        }
    }

    /** Only the newest offers are asked (each check may look at a file or the library). */
    private suspend fun check(newestFirst: List<ActionEntry>): Map<String, UndoAction> {
        val byId = newestFirst.associateBy { it.id }
        val out = HashMap<String, UndoAction>()
        for ((id, action) in ActionHistory.undoCandidates(newestFirst).entries.take(MAX_UNDO_CHECKS)) {
            val entry = byId[id] ?: continue
            if (undoService.available(entry, action)) out[id] = action
        }
        return out
    }

    fun setFilter(value: ActionFilter) { filter.value = value }

    fun setQuery(value: String) { query.value = value.take(80) }

    /** Runs the way back of [row]; [onDone] gets the result (on the main thread). */
    fun undo(row: ActionRowUi, onDone: (UndoAction, UndoResult) -> Unit) {
        val action = row.undo ?: return
        if (busy.value != null) return
        busy.value = row.key
        viewModelScope.launch {
            val result = try { undoService.undo(row.entry, action) } finally { busy.value = null }
            // The button follows what is true now: gone when the game is back, kept when part of it is still in the trash.
            log.entries.value?.let { available.value = check(it.asReversed()) }
            onDone(action, result)
        }
    }

    fun clear(onFailure: () -> Unit = {}) {
        viewModelScope.launch {
            try { log.clear() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { onFailure() }
        }
    }

    val ui: StateFlow<ActionHistoryUi> = combine(combine(log.entries, profiles.activeId) { all, profile -> all?.filter { it.profileId == profile } }, filter, query, available, busy) { lines, f, q, undo, busyKey ->
        if (lines == null) return@combine ActionHistoryUi(loading = true, filter = f, query = q)
        val newestFirst = lines.asReversed()
        val filtered = ActionHistory.filter(newestFirst, f, q)
        val groups = ActionHistory.groupByDay(filtered, System.currentTimeMillis(), ZoneId.systemDefault()).map { day ->
            ActionDayUi(day, day.entries.map { e -> ActionRowUi(e.id, e, undo[e.id]) })
        }
        ActionHistoryUi(loading = false, filter = f, query = q, groups = groups, total = filtered.size, all = lines.size, busyKey = busyKey)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ActionHistoryUi())

    private companion object {
        const val MAX_UNDO_CHECKS = 100
    }
}
