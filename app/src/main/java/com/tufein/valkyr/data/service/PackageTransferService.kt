package com.tufein.valkyr.data.service

import android.content.Context
import android.net.Uri
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.util.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class PackageTransferSnapshot(val profileId: String, val restrictions: LibraryRestrictions,
    val locations: List<StorageLocation>, val records: List<GamePackages.Manifest>)
data class PackageTransferCheck(val record: GamePackages.Manifest, val sourceFound: Boolean, val existingCompatible: Boolean,
    val missing: List<String>, val changed: List<String>) {
    val ready: Boolean get() = sourceFound && existingCompatible && missing.isEmpty() && changed.isEmpty()
}
data class PackageTransferPreview(val profileId: String, val restrictions: LibraryRestrictions,
    val location: StorageLocation, val checks: List<PackageTransferCheck>) {
    val ready: Boolean get() = checks.isNotEmpty() && checks.all { it.ready }
}

/** Imports register existing verified files; they never write, move or delete game/save bytes. */
@Singleton
class PackageTransferService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val packages: GamePackageService,
    private val settings: AppSettings,
    private val repository: DownloadableFileRepository,
    private val storage: StorageAvailabilityService,
    private val gate: StorageMoveGate
) {
    private suspend fun scope(): Pair<String, LibraryRestrictions> {
        val id = settings.activeProfile.first()
        return id to Profiles.restrictionsOf(Profiles.fromJson(settings.profiles.first()), id)
    }
    private suspend fun locations() = storage.configuredLocations().filter { it.downloadRoot }

    suspend fun snapshot(): PackageTransferSnapshot = withContext(Dispatchers.IO) {
        val scope = scope()
        val locations = locations()
        val all = packages.records(locations.map { it.uri }.toSet()).groupBy { it.sourceIdentity }
            .values.map { it.maxBy { record -> record.createdAt } }
        val indexed = if (scope.second.active) repository.exactMatchesBatch(all.map { it.consoleId to it.fileName }) else emptyMap()
        val records = all.filter { record ->
            val rows = indexed[record.consoleId to record.fileName].orEmpty()
            !scope.second.active || rows.isNotEmpty() && rows.all { scope.second.allows(record.consoleId, it.tags) }
        }.sortedWith(compareBy({ it.consoleId }, { it.fileName }))
        check(scope() == scope)
        PackageTransferSnapshot(scope.first, scope.second, locations, records)
    }

    suspend fun export(snapshot: PackageTransferSnapshot, uri: Uri) = withContext(Dispatchers.IO) {
        settings.withActiveProfile(snapshot.profileId) {
            check(scope() == (snapshot.profileId to snapshot.restrictions))
            val fresh = this@PackageTransferService.snapshot()
            check(fresh == snapshot) { "Package records changed; refresh first" }
            val bytes = GamePackageTransfer.encode(fresh.records)
            currentCoroutineContext().ensureActive()
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("Export file cannot be written")
            val written = context.contentResolver.openInputStream(uri)?.use { BoundedStreams.read(it, GamePackageTransfer.MAX_BYTES + 1) }
                ?: error("Export file cannot be read")
            check(written.contentEquals(bytes)) { "Export file verification failed" }
        }
    }

    suspend fun read(uri: Uri): List<GamePackages.Manifest> = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { BoundedStreams.read(it, GamePackageTransfer.MAX_BYTES + 1) }
            ?: error("Import file cannot be read")
        GamePackageTransfer.decode(bytes)
    }

    suspend fun preview(records: List<GamePackages.Manifest>, location: StorageLocation): PackageTransferPreview = withContext(Dispatchers.IO) {
        // Also bounds and validates caller-provided lists; imported root grants are never consulted.
        GamePackageTransfer.encode(records)
        val scope = scope()
        check(!scope.second.active) { "Switch to an unrestricted profile to restore package records" }
        check(locations().any { it == location }) { "Select a configured game folder" }
        val proposed = records.map { it.copy(rootUri = location.uri) }
        val sources = repository.exactMatchesBatch(proposed.map { it.consoleId to it.fileName })
        val matched = proposed.filter { record -> sources[record.consoleId to record.fileName].orEmpty().any { packages.identity(it.file) == record.sourceIdentity } }
        val inspections = packages.inspectBatch(matched, verifyHashes = true).associateBy { it.manifest.sourceIdentity }
        val checks = proposed.map { record ->
            val inspection = inspections[record.sourceIdentity]
            PackageTransferCheck(record, inspection != null, packages.importCompatible(record), inspection?.missing.orEmpty(), inspection?.changed.orEmpty())
        }
        check(scope() == scope && locations().any { it == location })
        PackageTransferPreview(scope.first, scope.second, location, checks)
    }

    suspend fun restore(preview: PackageTransferPreview): Int = withContext(Dispatchers.IO) {
        check(preview.ready)
        gate.lock.withLock { settings.withActiveProfile(preview.profileId) {
            check(scope() == (preview.profileId to preview.restrictions))
            val fresh = this@PackageTransferService.preview(preview.checks.map { it.record.copy(rootUri = GamePackageTransfer.PORTABLE_ROOT) }, preview.location)
            check(fresh == preview && fresh.ready) { "Package files changed; check them again" }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { packages.importVerified(fresh.checks.map { it.record }) }
        } }
    }
}
