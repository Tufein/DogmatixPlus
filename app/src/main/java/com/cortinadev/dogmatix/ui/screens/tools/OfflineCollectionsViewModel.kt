package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.OfflineCollectionsService
import com.cortinadev.dogmatix.data.service.OfflineState
import com.cortinadev.dogmatix.data.service.RemovalPlan
import com.cortinadev.dogmatix.data.service.StaleGame
import com.cortinadev.dogmatix.util.OfflineCollections
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the collections screen shows about "Keep on this device". */
data class OfflineData(
    val kept: Set<Long> = emptySet(),
    val cap: Int = OfflineCollections.DEFAULT_CAP,
    val wifiOnly: Boolean = true,
    val last: OfflineCollections.RunInfo? = null,
    val state: OfflineState = OfflineState()
)

@OptIn(FlowPreview::class)
@HiltViewModel
class OfflineCollectionsViewModel @Inject constructor(
    private val service: OfflineCollectionsService
) : ViewModel() {

    val data: StateFlow<OfflineData> = combine(service.keptIds, service.cap, service.wifiOnly, service.lastRun, service.state) { kept, cap, wifi, last, state ->
        OfflineData(kept, cap, wifi, last, state)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OfflineData())

    private val _removal = MutableStateFlow<RemovalPlan?>(null)
    /** The files a removal would delete, while the confirmation is up. */
    val removal: StateFlow<RemovalPlan?> = _removal.asStateFlow()

    init {
        // The status lines follow the collections, the disk index and the settings while the screen is open.
        viewModelScope.launch { service.changes.debounce(800).collect { service.preview() } }
    }

    fun setKept(id: Long, on: Boolean) = service.setKept(id, on)
    fun fetchNow() = service.fetchNow()
    fun setCap(cap: Int) = service.setCap(cap)
    fun setWifiOnly(on: Boolean) = service.setWifiOnly(on)

    /** Reads which files [games] are made of, then asks for the confirmation; nothing is deleted yet. */
    fun askRemoval(games: List<StaleGame>) {
        if (games.isEmpty()) return
        viewModelScope.launch {
            try {
                _removal.value = service.prepareRemoval(games)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _removal.value = null
            }
        }
    }

    fun dismissRemoval() { _removal.value = null }

    /** Deletes exactly the files the confirmation listed. */
    fun confirmRemoval(context: Context) {
        val plan = _removal.value ?: return
        _removal.value = null
        val app = context.applicationContext
        viewModelScope.launch {
            val removed = try { service.remove(plan) } catch (e: CancellationException) { throw e } catch (e: Exception) { 0 }
            ToastUtil.showSuccess(app, app.resources.getQuantityString(R.plurals.offline7_removed, removed, removed))
        }
    }

    /** Stops listing [games]; they stay on the device. */
    fun keep(games: List<StaleGame>) {
        if (games.isEmpty()) return
        viewModelScope.launch { service.keep(games) }
    }
}
