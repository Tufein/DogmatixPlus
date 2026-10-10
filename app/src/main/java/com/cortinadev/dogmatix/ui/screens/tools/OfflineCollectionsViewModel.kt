package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.OfflineCollectionsService
import com.cortinadev.dogmatix.data.service.OfflineState
import com.cortinadev.dogmatix.data.service.RemovalPlan
import com.cortinadev.dogmatix.data.service.StaleGame
import com.cortinadev.dogmatix.data.service.OfflineReadyCollection
import com.cortinadev.dogmatix.util.OfflineCollections
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the collections screen shows about "Keep on this device". */
data class OfflineData(
    val quotas: Map<Long, Int> = emptyMap(),
    val reserveGb: Int = 1,
    val kept: Set<Long> = emptySet(),
    val cap: Int = OfflineCollections.DEFAULT_CAP,
    val wifiOnly: Boolean = true,
    val last: OfflineCollections.RunInfo? = null,
    val state: OfflineState = OfflineState()
)

data class OfflineReadyUi(val collectionId: Long, val loading: Boolean = true,
    val report: OfflineReadyCollection? = null, val failed: Boolean = false)

@OptIn(FlowPreview::class)
@HiltViewModel
class OfflineCollectionsViewModel @Inject constructor(
    private val service: OfflineCollectionsService
) : ViewModel() {

    val data: StateFlow<OfflineData> = combine(service.keptIds, service.cap, service.wifiOnly, service.lastRun, service.state) { kept, cap, wifi, last, state ->
        OfflineData(kept = kept, cap = cap, wifiOnly = wifi, last = last, state = state)
    }.let { base -> combine(base, service.quotas, service.reserveGb) { data, quotas, reserve -> data.copy(quotas = quotas, reserveGb = reserve) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OfflineData())

    fun setQuota(id: Long, gb: Int) = service.setQuota(id, gb)
    fun setReserveGb(gb: Int) = service.setReserveGb(gb)

    private val _removal = MutableStateFlow<RemovalPlan?>(null)
    /** The files a removal would delete, while the confirmation is up. */
    val removal: StateFlow<RemovalPlan?> = _removal.asStateFlow()
    private val _readiness = MutableStateFlow<OfflineReadyUi?>(null)
    val readiness = _readiness.asStateFlow()
    private var readyJob: Job? = null
    private var readyGeneration = 0L

    fun checkOffline(id: Long) {
        readyJob?.cancel()
        val generation = ++readyGeneration
        _readiness.value = OfflineReadyUi(id)
        readyJob = viewModelScope.launch {
            try {
                val report = service.checkCollection(id) { progress ->
                    if (generation == readyGeneration) _readiness.value = OfflineReadyUi(id, true, progress)
                }
                if (generation == readyGeneration) _readiness.value = OfflineReadyUi(id, false, report)
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == readyGeneration) _readiness.value = OfflineReadyUi(id, false, failed = true) }
        }
    }
    fun dismissReadiness() { ++readyGeneration; readyJob?.cancel(); readyJob = null; _readiness.value = null }

    init {
        // The status lines follow the collections, the disk index and the settings while the screen is open.
        viewModelScope.launch { service.changes.debounce(800).collect { service.preview() } }
        viewModelScope.launch { service.activeProfile.drop(1).collect { dismissReadiness() } }
        viewModelScope.launch { service.readinessRestrictions.drop(1).collect { dismissReadiness() } }
    }

    fun setKept(id: Long, on: Boolean) = service.setKept(id, on)
    private val _preview = MutableStateFlow<List<OfflineCollections.Pick>?>(null)
    val fetchPreview = _preview.asStateFlow()
    fun askFetch() { viewModelScope.launch { service.preview(); _preview.value = service.state.value.planned } }
    fun dismissFetch() { _preview.value = null }
    fun confirmFetch() {
        val picks = _preview.value ?: return
        _preview.value = null
        service.fetchNow(picks.map { it.game }.toSet())
    }
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
