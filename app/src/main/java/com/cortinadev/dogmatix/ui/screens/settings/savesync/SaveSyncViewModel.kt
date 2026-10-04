package com.cortinadev.dogmatix.ui.screens.settings.savesync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.local.AppSettings
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
import com.cortinadev.dogmatix.util.EmulatorSaveFolder
import com.cortinadev.dogmatix.util.EmulatorSaveFolders
import kotlinx.coroutines.flow.first
import javax.inject.Inject

data class SaveSyncUiState(
    val rommUrl: String = "",
    val savesDir: String = "",
    val statesDir: String = "",
    val auto: Boolean = false,
    val deletions: Boolean = false,
    val background: Boolean = false,
    val intervalHours: Int = 6,
    val wifiOnly: Boolean = true,
    val charging: Boolean = false
)

@HiltViewModel
class SaveSyncViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val service: SaveSyncService,
    private val appSettings: AppSettings
) : ViewModel() {

    /** Standalone emulators' saves folders (DraStic, mGBA…). */
    val emulatorFolders: StateFlow<List<EmulatorSaveFolder>> = appSettings.saveSyncEmulatorFolders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addEmulatorFolder(context: Context, preset: EmulatorSaveFolders.Preset, uri: String) = executeWithToast(context, TAG) {
        val current = appSettings.saveSyncEmulatorFolders.first()
        val label = EmulatorSaveFolders.uniqueLabel(preset.label, current.map { it.label })
        appSettings.setSaveSyncEmulatorFolders(current + EmulatorSaveFolder(label, preset.platforms, uri))
    }

    fun removeEmulatorFolder(context: Context, label: String) = executeWithToast(context, TAG) {
        appSettings.setSaveSyncEmulatorFolders(appSettings.saveSyncEmulatorFolders.first().filterNot { it.label == label })
    }

    val uiState: StateFlow<SaveSyncUiState> = combine(
        combine(
            settingsRepository.rommUrl,
            settingsRepository.saveSyncSavesDir,
            settingsRepository.saveSyncStatesDir,
            settingsRepository.saveSyncAuto,
            settingsRepository.saveSyncDeletions
        ) { url, saves, states, auto, deletions -> SaveSyncUiState(url, saves, states, auto, deletions) },
        combine(
            settingsRepository.saveSyncBackground, settingsRepository.saveSyncBgIntervalHours,
            settingsRepository.saveSyncBgWifiOnly, settingsRepository.saveSyncBgCharging
        ) { bg, hours, wifi, charging -> listOf(bg, hours, wifi, charging) }
    ) { base, bg -> base.copy(background = bg[0] as Boolean, intervalHours = bg[1] as Int, wifiOnly = bg[2] as Boolean, charging = bg[3] as Boolean) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SaveSyncUiState())

    val syncState: StateFlow<SaveSyncState> = service.state

    fun setSavesDir(context: Context, uri: String) = executeWithToast(context, TAG) { settingsRepository.setSaveSyncSavesDir(uri) }
    fun setStatesDir(context: Context, uri: String) = executeWithToast(context, TAG) { settingsRepository.setSaveSyncStatesDir(uri) }
    fun setAuto(context: Context, enabled: Boolean) = executeWithToast(context, TAG) { settingsRepository.setSaveSyncAuto(enabled) }

    fun setDeletions(context: Context, enabled: Boolean) = executeWithToast(context, TAG) { settingsRepository.setSaveSyncDeletions(enabled) }
    fun setBackground(context: Context, enabled: Boolean) = executeWithToast(context, TAG) { settingsRepository.setSaveSyncBackground(enabled) }
    fun setInterval(context: Context, hours: Int) = executeWithToast(context, TAG) { settingsRepository.setSaveSyncBgIntervalHours(hours) }
    fun setWifiOnly(context: Context, enabled: Boolean) = executeWithToast(context, TAG) { settingsRepository.setSaveSyncBgWifiOnly(enabled) }
    fun setCharging(context: Context, enabled: Boolean) = executeWithToast(context, TAG) { settingsRepository.setSaveSyncBgCharging(enabled) }

    fun syncNow() = service.syncNow()

    /** Lets deletions that were held back (more than usual) go through. */
    fun applyHeldDeletions() = service.syncNow(confirmDeletions = true)

    fun resolve(conflict: SaveConflict, keepDevice: Boolean) = service.resolve(conflict, keepDevice)

    private companion object { const val TAG = "SaveSyncViewModel" }
}
