package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.CloudStatus
import com.cortinadev.dogmatix.util.CloudStatusModel
import com.cortinadev.dogmatix.util.CloudStatusPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The state behind the small cloud icon in the top bar (5.0): hidden while no cloud feature is set
 * up, idle, syncing (with progress when known) or "needs you" with a count of conflicts and errors.
 *
 * RomM (library refresh) and the save sync are built in. Other cloud features add theirs without
 * this class knowing them, through one of two hooks:
 * - `report("webdav_backup", CloudStatusPart(configured = true, syncing = true))` at each change
 *   (`report(id, null)` removes it), or
 * - `attach("device_sync", someFlowOfCloudStatusPart)` once, from the feature's own scope.
 */
@Singleton
class CloudStatusService @Inject constructor(
    settingsRepository: SettingsRepository,
    appSettings: AppSettings,
    saveSync: SaveSyncService,
    rommLibrary: RommLibraryService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Parts reported by other features (WebDAV backup, device sync…), by their id. */
    private val external = MutableStateFlow<Map<String, CloudStatusPart>>(emptyMap())
    private val attached = ConcurrentHashMap<String, Job>()

    private val rommConfigured: Flow<Boolean> =
        combine(settingsRepository.rommUrl, settingsRepository.rommToken) { url, token -> url.isNotBlank() && token.isNotBlank() }
            .distinctUntilChanged()

    /** Same test as [SaveSyncService.isConfigured], as a flow. */
    private val saveSyncConfigured: Flow<Boolean> = combine(
        rommConfigured, settingsRepository.saveSyncSavesDir, settingsRepository.saveSyncStatesDir, appSettings.saveSyncEmulatorFolders
    ) { romm, saves, states, emulators -> romm && (saves.isNotBlank() || states.isNotBlank() || emulators.isNotEmpty()) }
        .distinctUntilChanged()

    private val rommPart: Flow<CloudStatusPart> = combine(rommConfigured, rommLibrary.state) { on, library ->
        CloudStatusPart(configured = on, syncing = on && library.refreshing)
    }

    private val saveSyncPart: Flow<CloudStatusPart> = combine(saveSyncConfigured, saveSync.state) { on, s ->
        CloudStatusModel.saveSyncPart(
            configured = on,
            running = s.running,
            conflicts = s.conflicts.size,
            failed = s.last?.failed ?: 0,
            hasError = s.error != null,
            deletionsHeld = s.last?.deletionsHeld ?: 0,
            progress = s.progress
        )
    }

    /** What the top-bar icon shows. */
    val status: StateFlow<CloudStatus> = combine(rommPart, saveSyncPart, external) { romm, sync, others ->
        CloudStatusModel.merge(listOf(romm, sync) + others.values)
    }
        .catch { emit(CloudStatus.Hidden) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, CloudStatus.Hidden)

    /** Sets (or with null removes) the part of feature [id]. */
    fun report(id: String, part: CloudStatusPart?) {
        external.update { if (part == null) it - id else it + (id to part) }
    }

    /** Follows [parts] for feature [id] until [detach]; replaces an earlier attach of [id]. */
    fun attach(id: String, parts: Flow<CloudStatusPart>) {
        attached.remove(id)?.cancel()
        attached[id] = scope.launch {
            // A failing feature flow takes its part away instead of freezing it.
            parts.distinctUntilChanged()
                .catch { report(id, null) }
                .collect { report(id, it) }
        }
    }

    fun detach(id: String) {
        attached.remove(id)?.cancel()
        report(id, null)
    }
}
