package com.cortinadev.dogmatix.ui.screens.cloud.saves

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.local.CloudSavesSettings
import com.cortinadev.dogmatix.data.service.ContinueItem
import com.cortinadev.dogmatix.data.service.ContinuePlayingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The "Continue playing" shelf (Home) and its switch (Settings). The shelf itself is worked out and
 * cached by [ContinuePlayingService] off the main thread; this only exposes it.
 */
@HiltViewModel
class ContinuePlayingViewModel @Inject constructor(
    private val service: ContinuePlayingService,
    private val settings: CloudSavesSettings
) : ViewModel() {

    /** Settings switch "Continue playing on Home" (default on). */
    val enabled: StateFlow<Boolean> = settings.continuePlaying
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The cards, newest first; empty when switched off or nothing is known. */
    val items: StateFlow<List<ContinueItem>> = combine(service.items, settings.continuePlaying) { list, on -> if (on) list else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The shelf is on screen: stored shelf at once, a fresh one when stale. */
    fun onShown() = service.onShown()

    fun setEnabled(on: Boolean) {
        viewModelScope.launch {
            settings.setContinuePlaying(on)
            if (on) service.refreshNow()
        }
    }
}
