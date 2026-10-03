package com.cortinadev.dogmatix.ui.screens.settings.savesync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.SaveConflict
import com.cortinadev.dogmatix.data.service.SaveSyncService
import com.cortinadev.dogmatix.data.service.SaveSyncState
import com.cortinadev.dogmatix.ui.common.executeWithToast
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SaveSyncUiState(
    val rommUrl: String = "",
    val savesDir: String = "",
    val statesDir: String = "",
    val auto: Boolean = false
)

@HiltViewModel
class SaveSyncViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val service: SaveSyncService
) : ViewModel() {

    val uiState: StateFlow<SaveSyncUiState> = combine(
        settingsRepository.rommUrl,
        settingsRepository.saveSyncSavesDir,
        settingsRepository.saveSyncStatesDir,
        settingsRepository.saveSyncAuto
    ) { url, saves, states, auto -> SaveSyncUiState(url, saves, states, auto) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SaveSyncUiState())

    val syncState: StateFlow<SaveSyncState> = service.state

    fun setSavesDir(context: Context, uri: String) = executeWithToast(context, TAG) { settingsRepository.setSaveSyncSavesDir(uri) }
    fun setStatesDir(context: Context, uri: String) = executeWithToast(context, TAG) { settingsRepository.setSaveSyncStatesDir(uri) }
    fun setAuto(context: Context, enabled: Boolean) = executeWithToast(context, TAG) { settingsRepository.setSaveSyncAuto(enabled) }

    fun syncNow() = service.syncNow()

    fun resolve(conflict: SaveConflict, keepDevice: Boolean) = service.resolve(conflict, keepDevice)

    private companion object { const val TAG = "SaveSyncViewModel" }
}
