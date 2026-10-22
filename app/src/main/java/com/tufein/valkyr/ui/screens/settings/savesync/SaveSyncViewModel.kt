package com.tufein.valkyr.ui.screens.settings.savesync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.SaveConflict
import com.tufein.valkyr.data.service.SaveSyncService
import com.tufein.valkyr.data.service.SaveSyncState
import com.tufein.valkyr.ui.common.executeWithToast
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import com.tufein.valkyr.util.EmulatorSaveFolder
import com.tufein.valkyr.util.EmulatorSaveFolders
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        appSettings.setSaveSyncEmulatorFolders(current + EmulatorSaveFolder(label, preset.platforms, uri, preset.layout))
    }

    /** RomM games found for a folder's shared memory cards (the picker's list); null while nothing was searched. */
    private val _gameResults = kotlinx.coroutines.flow.MutableStateFlow<List<com.tufein.valkyr.util.SaveSyncPlanner.RomCandidate>?>(null)
    val gameResults: StateFlow<List<com.tufein.valkyr.util.SaveSyncPlanner.RomCandidate>?> = _gameResults
    private val _searching = kotlinx.coroutines.flow.MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching
    private var searchJob: kotlinx.coroutines.Job? = null

    fun searchGames(context: Context, term: String) {
        searchJob?.cancel()
        _searching.value = true
        searchJob = viewModelScope.launch {
            try { _gameResults.value = service.searchGames(term) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _gameResults.value = emptyList(); com.tufein.valkyr.util.ToastUtil.showError(context, e.message ?: e.javaClass.simpleName) }
            finally { _searching.value = false }
        }
    }

    fun clearGameResults() { searchJob?.cancel(); _searching.value = false; _gameResults.value = null }

    /** Syncs [label]'s shared memory cards with RomM game [rom] from now on; null stops syncing them (nothing is deleted). */
    fun setSharedCards(context: Context, label: String, rom: com.tufein.valkyr.util.SaveSyncPlanner.RomCandidate?) = executeWithToast(context, TAG) {
        appSettings.setSaveSyncEmulatorFolders(appSettings.saveSyncEmulatorFolders.first().map { folder ->
            if (folder.label != label || !folder.layout.holdsSharedCards) folder
            else folder.copy(sharedCardsRomId = rom?.id, sharedCardsRomName = rom?.let { it.name.ifBlank { it.fsName } }.orEmpty())
        })
    }

    /**
     * Syncs [label]'s Dolphin GCI folders ([DolphinBundleKind.GCI]) or Wii saves ([DolphinBundleKind.WII])
     * as bundles kept with RomM game [rom] from now on; null stops syncing them (nothing is deleted).
     */
    fun setDolphinBundles(context: Context, label: String, kind: com.tufein.valkyr.util.DolphinBundleKind, rom: com.tufein.valkyr.util.SaveSyncPlanner.RomCandidate?) = executeWithToast(context, TAG) {
        val id = rom?.id
        val name = rom?.let { it.name.ifBlank { it.fsName } }.orEmpty()
        appSettings.setSaveSyncEmulatorFolders(appSettings.saveSyncEmulatorFolders.first().map { folder ->
            when {
                folder.label != label || folder.layout != com.tufein.valkyr.util.SaveFolderLayout.GC_MEMCARDS -> folder
                kind == com.tufein.valkyr.util.DolphinBundleKind.GCI -> folder.copy(gciRomId = id, gciRomName = name)
                else -> folder.copy(wiiRomId = id, wiiRomName = name, wiiChannels = folder.wiiChannels && id != null, wiiWare = folder.wiiWare && id != null)
            }
        })
    }

    /** Whether [label]'s Wii bundle also takes the saves of channels discs install (`Wii/title/00010004`). */
    fun setWiiChannels(context: Context, label: String, on: Boolean) = executeWithToast(context, TAG) {
        appSettings.setSaveSyncEmulatorFolders(appSettings.saveSyncEmulatorFolders.first().map { folder ->
            if (folder.label != label || folder.wiiRomId == null) folder else folder.copy(wiiChannels = on)
        })
    }

    /** Whether [label]'s Wii bundle also takes the saves of WiiWare and Virtual Console titles (`Wii/title/00010001`). */
    fun setWiiWare(context: Context, label: String, on: Boolean) = executeWithToast(context, TAG) {
        appSettings.setSaveSyncEmulatorFolders(appSettings.saveSyncEmulatorFolders.first().map { folder ->
            if (folder.label != label || folder.wiiRomId == null) folder else folder.copy(wiiWare = on)
        })
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
