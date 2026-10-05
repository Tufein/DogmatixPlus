package com.cortinadev.dogmatix.ui.screens.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.CloudBackupService
import com.cortinadev.dogmatix.data.service.CloudStatusService
import com.cortinadev.dogmatix.data.service.DavStatus
import com.cortinadev.dogmatix.data.service.DavStatusService
import com.cortinadev.dogmatix.data.service.DeviceSyncService
import com.cortinadev.dogmatix.data.service.RommServerInfo
import com.cortinadev.dogmatix.data.service.RommServerService
import com.cortinadev.dogmatix.data.service.SaveSyncService
import com.cortinadev.dogmatix.data.service.SaveSyncState
import com.cortinadev.dogmatix.util.CloudStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Debrid card shows: which service resolves torrent links, and whether it has a key. */
data class DebridCard(val provider: DebridProvider = DebridProvider.NONE, val hasKey: Boolean = false)

/** Whether the save sync has what it needs (RomM plus at least one saves folder), as a flow. */
data class SaveSyncCard(val configured: Boolean = false, val rommConfigured: Boolean = false)

/**
 * The Cloud hub (Settings → Cloud): one place that shows each cloud feature's state and starts its
 * most common action. It only reads what the services already keep; nothing here talks to a server
 * on its own except through [RommServerService.refreshIfStale] when the screen opens.
 */
@HiltViewModel
class CloudViewModel @Inject constructor(
    private val romm: RommServerService,
    private val saveSync: SaveSyncService,
    private val cloudBackup: CloudBackupService,
    private val deviceSync: DeviceSyncService,
    settingsRepository: SettingsRepository,
    appSettings: AppSettings,
    cloudStatus: CloudStatusService,
    davStatus: DavStatusService
) : ViewModel() {

    /** The WebDAV cloud: encrypted backup and device sync. */
    val dav: StateFlow<DavStatus> = davStatus.status


    val rommInfo: StateFlow<RommServerInfo> = romm.info

    val saveSyncState: StateFlow<SaveSyncState> = saveSync.state

    val overall: StateFlow<CloudStatus> = cloudStatus.status

    val saveSyncCard: StateFlow<SaveSyncCard> = combine(
        settingsRepository.rommUrl,
        settingsRepository.rommToken,
        settingsRepository.saveSyncSavesDir,
        settingsRepository.saveSyncStatesDir,
        appSettings.saveSyncEmulatorFolders
    ) { url, token, saves, states, emulators ->
        val romm = url.isNotBlank() && token.isNotBlank()
        SaveSyncCard(
            configured = romm && (saves.isNotBlank() || states.isNotBlank() || emulators.isNotEmpty()),
            rommConfigured = romm
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SaveSyncCard())

    val debrid: StateFlow<DebridCard> = combine(
        settingsRepository.debridProvider,
        settingsRepository.torboxApiKey,
        settingsRepository.realDebridApiKey
    ) { provider, torbox, realDebrid ->
        DebridCard(
            provider = provider,
            hasKey = when (provider) {
                DebridProvider.TORBOX -> torbox.isNotBlank()
                DebridProvider.REAL_DEBRID -> realDebrid.isNotBlank()
                DebridProvider.NONE -> false
            }
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebridCard())

    /** The screen opened: ask the server about itself unless that was done a moment ago. */
    fun onOpen() = romm.refreshIfStale()

    fun refreshRomm() = romm.refresh(force = true)

    fun syncSaves() = saveSync.syncNow()

    /** One encrypted backup to the WebDAV server, now. The result lands in [dav] (last backup or error). */
    fun backupNow() {
        viewModelScope.launch { cloudBackup.backupNow() }
    }

    /** One device sync, now. Removals that look like too many wait for the user in the backup screen. */
    fun syncDevices() {
        viewModelScope.launch { deviceSync.syncNow() }
    }
}
