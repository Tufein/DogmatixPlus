package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.SpaceReclaimService
import com.cortinadev.dogmatix.data.service.SpaceRemoval
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.util.SpaceCandidate
import com.cortinadev.dogmatix.util.SpaceConsole
import com.cortinadev.dogmatix.util.SpacePlay
import com.cortinadev.dogmatix.util.SpaceReclaim
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the last removal did, shown on the screen until the next pick. */
data class FreeSpaceDone(val removed: Int, val bytes: Long, val wished: Int, val failed: Int)

data class FreeSpaceUiState(
    val loading: Boolean = true,
    val folderSet: Boolean = true,
    /** Every offered game, biggest first. */
    val candidates: List<SpaceCandidate> = emptyList(),
    val playedCount: Int = 0,
    val playedBytes: Long = 0L,
    val hasPlayData: Boolean = false,
    val freeBytes: Long? = null,
    val savesChecked: Boolean = false,
    val achievementsChecked: Boolean = false,
    /** Console filter; null = all. */
    val console: String? = null,
    val selected: Set<String> = emptySet(),
    val freeGb: Int = SpaceReclaim.DEFAULT_FREE_GB,
    val removing: Boolean = false,
    val done: FreeSpaceDone? = null
) {
    /** The games of the filter, ranked. */
    val visible: List<SpaceCandidate> by lazy { SpaceReclaim.forConsole(candidates, console) }
    val consoles: List<SpaceConsole> by lazy { SpaceReclaim.consoles(candidates) }
    val selectedGames: List<SpaceCandidate> by lazy { candidates.filter { it.id in selected } }
    val selectedBytes: Long get() = selectedGames.sumOf { it.bytes }
    val selectedProtected: Int get() = selectedGames.count { it.isProtected }
    val visibleBytes: Long get() = visible.sumOf { it.bytes }
    val unknownCount: Int get() = visible.count { it.play == SpacePlay.UNKNOWN }
}

@HiltViewModel
class FreeSpaceViewModel @Inject constructor(
    private val service: SpaceReclaimService
) : ViewModel() {

    private val _ui = MutableStateFlow(FreeSpaceUiState())
    val ui: StateFlow<FreeSpaceUiState> = _ui.asStateFlow()

    private var loadJob: Job? = null

    init { load() }

    /** Reads the library folders and the facts again. [silent] keeps the list on screen while it does. */
    fun load(silent: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!silent) _ui.update { it.copy(loading = true) }
            // A newer load cancels this one: stop here instead of publishing an empty result.
            val scan = try {
                service.scan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _ui.update { old ->
                val candidates = scan?.report?.candidates.orEmpty()
                val ids = candidates.mapTo(HashSet()) { it.id }
                val total = candidates.sumOf { it.bytes }
                old.copy(
                    loading = false,
                    folderSet = scan?.folderSet ?: old.folderSet,
                    candidates = candidates,
                    playedCount = scan?.report?.playedCount ?: 0,
                    playedBytes = scan?.report?.playedBytes ?: 0L,
                    hasPlayData = scan?.report?.hasPlayData ?: false,
                    freeBytes = scan?.freeBytes,
                    savesChecked = scan?.savesChecked ?: false,
                    achievementsChecked = scan?.achievementsChecked ?: false,
                    // The filter and the pick survive a refresh as far as their games are still there.
                    console = old.console?.takeIf { c -> candidates.any { it.consoleId == c } },
                    selected = old.selected.filterTo(HashSet()) { it in ids },
                    freeGb = if (old.candidates.isEmpty()) SpaceReclaim.suggestedFreeGb(total) else old.freeGb
                )
            }
        }
    }

    fun setConsole(consoleId: String?) { _ui.update { it.copy(console = consoleId) } }

    fun toggle(id: String) {
        _ui.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id, done = null) }
    }

    /** Ticks every game of the filter that is not protected. */
    fun selectAll() {
        _ui.update { it.copy(selected = it.selected + SpaceReclaim.unprotectedIds(it.visible), done = null) }
    }

    fun clear() { _ui.update { it.copy(selected = emptySet()) } }

    fun stepFree(delta: Int) { _ui.update { it.copy(freeGb = SpaceReclaim.stepGb(it.freeGb, delta)) } }

    /** Replaces the pick by the biggest unprotected games of the filter that free [FreeSpaceUiState.freeGb]. */
    fun selectBiggest(context: Context) {
        val state = _ui.value
        val pick = SpaceReclaim.selectBiggest(state.visible, state.freeGb * SpaceReclaim.GB)
        _ui.update { it.copy(selected = pick.ids, done = null) }
        if (!pick.reached) {
            ToastUtil.showInfo(context.applicationContext, context.getString(R.string.space7_pick_short, formatBytes(pick.bytes)))
        }
    }

    /**
     * Removes the picked games (and puts them on the wishlist when [addToWishlist]). The rows leave
     * the list as soon as the files are gone; the folders are read again afterwards.
     */
    fun remove(context: Context, addToWishlist: Boolean) {
        val chosen = _ui.value.selectedGames
        if (chosen.isEmpty() || _ui.value.removing) return
        val app = context.applicationContext
        _ui.update { it.copy(removing = true) }
        viewModelScope.launch {
            val result: SpaceRemoval? = try {
                service.remove(chosen, addToWishlist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val gone = result?.removed?.mapTo(HashSet()) { it.id }.orEmpty()
            val done = FreeSpaceDone(gone.size, result?.bytes ?: 0L, result?.wished ?: 0, chosen.size - gone.size)
            _ui.update { state ->
                state.copy(
                    removing = false,
                    candidates = state.candidates.filter { it.id !in gone },
                    selected = state.selected - gone,
                    done = done,
                    console = state.console?.takeIf { c -> state.candidates.any { it.consoleId == c && it.id !in gone } }
                )
            }
            when {
                done.removed > 0 -> ToastUtil.showSuccess(app, app.resources.getQuantityString(R.plurals.space7_done, done.removed, done.removed, formatBytes(done.bytes)))
                else -> ToastUtil.showError(app, app.resources.getQuantityString(R.plurals.space7_failed, done.failed, done.failed))
            }
            load(silent = true)
        }
    }
}
