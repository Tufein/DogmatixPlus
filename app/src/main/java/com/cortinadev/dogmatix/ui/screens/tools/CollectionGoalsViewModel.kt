package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.CollectionGoalsSettings
import com.cortinadev.dogmatix.data.repository.WishlistRepository
import com.cortinadev.dogmatix.data.service.CollectionGoalsService
import com.cortinadev.dogmatix.data.state.ListImportRequest
import com.cortinadev.dogmatix.data.state.PendingListImport
import com.cortinadev.dogmatix.util.CollectionGoals
import com.cortinadev.dogmatix.util.ConsoleProgress
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The console list: rings, goals first. */
data class CollectionUi(
    val loading: Boolean = true,
    val rows: List<ConsoleProgress> = emptyList(),
    val owned: Int = 0,
    val total: Int = 0
) {
    val fraction: Float get() = CollectionGoals.fraction(owned, total)
    val percent: Int get() = CollectionGoals.percent(owned, total)
}

/** The open console: its missing titles (null while they are read) and how many are shown. */
data class MissingUi(val consoleId: String, val titles: List<String>? = null, val shown: Int = PAGE)

private const val PAGE = 200

@HiltViewModel
class CollectionGoalsViewModel @Inject constructor(
    private val service: CollectionGoalsService,
    private val settings: CollectionGoalsSettings,
    private val pendingImport: PendingListImport,
    private val wishlist: WishlistRepository
) : ViewModel() {

    init { service.start() }

    val ui: StateFlow<CollectionUi> = combine(service.state, settings.goals) { state, goals ->
        val rows = CollectionGoals.sort(state.rows.map { it.copy(goal = it.consoleId in goals) })
        val (owned, total) = CollectionGoals.overall(rows)
        CollectionUi(state.loading, rows, owned, total)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CollectionUi())

    private val _missing = MutableStateFlow<MissingUi?>(null)
    val missing: StateFlow<MissingUi?> = _missing

    /** Titles already put on the wishlist from this screen (per console id + title). */
    private val _wished = MutableStateFlow<Set<String>>(emptySet())
    val wished: StateFlow<Set<String>> = _wished

    private var loadJob: Job? = null

    fun toggleGoal(consoleId: String) { viewModelScope.launch { settings.toggle(consoleId) } }

    fun open(consoleId: String) {
        _missing.value = MissingUi(consoleId)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val titles = runCatching { service.missing(consoleId) }.getOrDefault(emptyList())
            _missing.update { if (it?.consoleId == consoleId) it.copy(titles = titles) else it }
        }
    }

    fun close() { loadJob?.cancel(); _missing.value = null }

    fun showMore() { _missing.update { it?.copy(shown = it.shown + PAGE) } }

    /** Hands every missing title of the open console to *Import a list* (as the DAT check does). */
    fun findMissing(): Boolean {
        val m = _missing.value ?: return false
        val titles = m.titles.orEmpty()
        if (titles.isEmpty()) return false
        pendingImport.submit(ListImportRequest(titles, m.consoleId))
        return true
    }

    fun addToWishlist(context: Context, title: String) {
        val consoleId = _missing.value?.consoleId ?: return
        val app = context.applicationContext
        viewModelScope.launch {
            val added = runCatching { wishlist.add(title, consoleId) }.getOrDefault(false)
            _wished.update { it + wishKey(consoleId, title) }
            if (!added) ToastUtil.showInfo(app, app.getString(R.string.coll6_wish_none))
        }
    }

    /** Adds the titles on screen (at most the shown page) that are not on the wishlist yet. */
    fun addShownToWishlist(context: Context) {
        val m = _missing.value ?: return
        val app = context.applicationContext
        val shown = m.titles.orEmpty().take(m.shown)
        viewModelScope.launch {
            var added = 0
            for (title in shown) {
                if (runCatching { wishlist.add(title, m.consoleId) }.getOrDefault(false)) added++
            }
            _wished.update { it + shown.map { t -> wishKey(m.consoleId, t) } }
            if (added == 0) ToastUtil.showInfo(app, app.getString(R.string.coll6_wish_none))
            else ToastUtil.showSuccess(app, app.resources.getQuantityString(R.plurals.coll6_wish_added, added, added))
        }
    }

    companion object {
        fun wishKey(consoleId: String, title: String) = "$consoleId|$title"
    }
}
