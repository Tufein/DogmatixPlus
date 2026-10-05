package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.local.CloudRecords
import com.cortinadev.dogmatix.data.local.CloudSettings
import com.cortinadev.dogmatix.util.CloudAttention
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the WebDAV cloud (backup and device sync) looks like right now, as one value for the Cloud
 * hub card and the top-bar cloud icon. Error fields are stored codes: show them with
 * `CloudMessages.render(context, code)`.
 */
data class DavStatus(
    /** A server address is set. */
    val configured: Boolean = false,
    /** True: the last connection test passed with these settings; false: it failed; null: not tested yet. */
    val connected: Boolean? = null,
    val autoBackup: Boolean = false,
    val deviceSync: Boolean = false,
    val backupRunning: Boolean = false,
    val syncRunning: Boolean = false,
    /** Epoch ms of this device's last cloud backup; 0 = never. */
    val lastBackupAt: Long = 0L,
    val lastBackupBytes: Long = 0L,
    val lastBackupError: String = "",
    val lastTestError: String = "",
    /** Epoch ms of this device's last device sync; 0 = never. */
    val lastSyncAt: Long = 0L,
    val lastSyncAdded: Int = 0,
    val lastSyncRemoved: Int = 0,
    val lastSyncError: String = "",
    /** The shared family wishlist (6.0) is switched on. */
    val sharedWishlist: Boolean = false,
    /** Epoch ms of this device's last shared wishlist sync; 0 = never. */
    val lastSharedAt: Long = 0L,
    val lastSharedError: String = "",
    /** Sync removals waiting for the user's confirmation. */
    val syncHeldBack: Int = 0,
    val backupStale: Boolean = false,
    /** Things that need a look (failed test, backup or sync, held-back removals); 0 while nothing is set up. */
    val attention: Int = 0
) {
    /** Something is talking to the server right now. */
    val busy: Boolean get() = backupRunning || syncRunning
}

/**
 * The cloud status for other screens (the Cloud hub, the top bar). Always available, cheap: it only
 * combines flows that already exist.
 */
@Singleton
class DavStatusService @Inject constructor(
    settings: CloudSettings,
    backup: CloudBackupService,
    sync: DeviceSyncService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val status: StateFlow<DavStatus> = combine(
        combine(settings.configured, settings.autoBackup, settings.deviceSync) { configured, auto, devices -> Triple(configured, auto, devices) },
        settings.records,
        combine(backup.running, sync.running) { backing, syncing -> backing to syncing },
        settings.sharedActive
    ) { (configured, auto, devices), records, (backing, syncing), shared ->
        of(configured, auto, devices, records, backing, syncing, System.currentTimeMillis(), shared)
    }.stateIn(scope, SharingStarted.Eagerly, DavStatus())

    /** Combines the settings and records into a [DavStatus]. */
    private fun of(
        configured: Boolean, auto: Boolean, devices: Boolean, r: CloudRecords,
        backing: Boolean, syncing: Boolean, now: Long, shared: Boolean
    ) = DavStatus(
        configured = configured,
        connected = if (!configured || r.lastTestAt == 0L) null else r.lastTestError.isEmpty(),
        autoBackup = auto,
        deviceSync = devices,
        backupRunning = backing,
        syncRunning = syncing,
        lastBackupAt = r.lastBackupAt,
        lastBackupBytes = r.lastBackupBytes,
        lastBackupError = r.lastBackupError,
        lastTestError = r.lastTestError,
        lastSyncAt = r.lastSyncAt,
        lastSyncAdded = r.lastSyncAdded,
        lastSyncRemoved = r.lastSyncRemoved,
        lastSyncError = r.lastSyncError,
        sharedWishlist = shared,
        lastSharedAt = r.lastSharedAt,
        lastSharedError = r.lastSharedError,
        syncHeldBack = r.syncHeldBack,
        backupStale = CloudAttention.backupStale(auto, r.lastBackupAt, now),
        attention = CloudAttention.count(configured, r.lastTestError, r.lastBackupError, r.lastSyncError, r.syncHeldBack)
    )
}
