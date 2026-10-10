package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.util.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class ReplacementRecovery(
    val folderUri: String,
    val folderLabel: String,
    val recovery: StorageHelper.Recovery,
    val bytes: Long
) {
    val key: String get() = "$folderUri|${recovery.id}"
}
data class ReplacementRestorePreview(
    val profileId: String,
    val replacement: ReplacementRecovery,
    val currentSha256: String?
)
data class RecoveryFilePreview(val name: String, val bytes: Long, val source: String, val target: String)
data class OperationRestorePreview(
    val profileId: String,
    val operation: LibraryOperation,
    val purge: Boolean,
    val files: List<RecoveryFilePreview>,
    val source: String,
    val target: String
)
data class RecoveryHubSnapshot(
    val profileId: String = "",
    val restrictions: LibraryRestrictions = LibraryRestrictions.NONE,
    val operations: List<LibraryOperation> = emptyList(),
    val replacements: List<ReplacementRecovery> = emptyList(),
    val storage: List<StorageLocationState> = emptyList(),
    val incompleteScan: Boolean = false
)

/** A bounded, read-only discovery pass. Mutations reread the profile, receipt and target first. */
@Singleton
class RecoveryHubService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val history: OperationHistoryService,
    private val actionLog: ActionLogService,
    private val dao: DownloadableFileDao,
    private val availability: StorageAvailabilityService,
    private val copier: VerifiedDocumentCopy,
    private val gate: StorageMoveGate
) {
    private suspend fun scope(): Pair<String, LibraryRestrictions> {
        val id = appSettings.activeProfile.first()
        return id to Profiles.restrictionsOf(Profiles.fromJson(appSettings.profiles.first()), id)
    }

    private suspend fun allowed(operation: LibraryOperation, profile: Pair<String, LibraryRestrictions>): Boolean {
        val removal = if (operation.kind == "trash") actionLog.removal(operation.id) else null
        val console = removal?.consoleId ?: operation.consoleId.takeIf(String::isNotBlank)
        var tags: List<String>? = null
        if (profile.second.hiddenTags.isNotEmpty() && console != null) {
            val names = listOfNotNull(removal?.fileName).ifEmpty { operation.files.map { it.name.substringAfterLast('/') } }.distinct()
            if (names.isNotEmpty() && names.size <= 100) {
                val matching = dao.filesByFileNames(names).filter { it.consoleId == console }
                if (matching.isNotEmpty() && names.all { name -> matching.any { it.fileName == name } }) {
                    tags = matching.flatMap { dao.tagsOf(it.id) }
                }
            }
        }
        return RecoveryScope.allows(profile.first, profile.second, removal?.profileId, console, tags)
    }

    suspend fun snapshot(): RecoveryHubSnapshot = withContext(Dispatchers.IO) {
        val profile = scope()
        val operations = history.entries.value.filter { allowed(it, profile) }
        val storage = availability.recheck().filter { state ->
            // Shared roots can hold hidden games; restricted profiles only see their own console roots.
            !profile.second.active || (!state.location.sharedRoot && state.location.consoleIds.all { it !in profile.second.hiddenConsoles })
        }
        val replacements = ArrayList<ReplacementRecovery>()
        var incomplete = false
        // SAF recovery receipts predate profile ownership. Only the unrestricted default profile sees them.
        if (profile.first.isBlank() && !profile.second.active) {
            val seen = HashSet<String>()
            var visited = 0
            suspend fun walk(dir: DiskDir, label: String, depth: Int) {
                currentCoroutineContext().ensureActive()
                if (!seen.add(StorageAvailabilityService.key(DiskScanner.uriOf(dir).toString()))) return
                if (depth > MAX_DEPTH || visited >= MAX_DIRECTORIES || replacements.size >= MAX_RECOVERIES) { incomplete = true; return }
                visited++
                val children = DiskScanner.listOrNull(context, dir, true)
                if (children == null) { incomplete = true; return }
                val folder = StorageHelper.getDocumentFile(context, DiskScanner.uriOf(dir).toString())
                if (folder == null) { incomplete = true; return }
                if (children.any { it.name.startsWith(".dogmatix-recovery-") && it.name.endsWith(".json") }) {
                    currentCoroutineContext().ensureActive()
                    StorageHelper.pendingRecoveries(context, folder).take(MAX_RECOVERIES - replacements.size).forEach { recovery ->
                        val backup = children.firstOrNull { it.name == recovery.backupName && !it.isDirectory }
                        if (backup != null) replacements += ReplacementRecovery(folder.uri.toString(), label, recovery, backup.size)
                    }
                }
                children.filter { it.isDirectory && !it.name.startsWith(".dogmatix-") }.forEach { entry ->
                    walk(DiskScanner.dirOf(dir, entry), "$label/${entry.name}", depth + 1)
                }
            }
            storage.forEach { state ->
                if (StorageAvailability.readable(state.status)) DiskScanner.rootOf(state.location.uri)?.let { walk(it, state.location.label, 0) }
                else incomplete = true
            }
        }
        check(scope() == profile) { "Profile changed" }
        RecoveryHubSnapshot(profile.first, profile.second, operations, replacements, storage, incomplete)
    }

    suspend fun isCurrent(snapshot: RecoveryHubSnapshot): Boolean = scope() == (snapshot.profileId to snapshot.restrictions)

    suspend fun previewOperation(id: String, purge: Boolean): OperationRestorePreview = withContext(Dispatchers.IO) {
        val profile = scope()
        val operation = history.get(id) ?: error("Recovery receipt unavailable")
        check(allowed(operation, profile)) { "Recovery unavailable for this profile" }
        check(operation.phase != "done")
        if (purge) check(operation.kind == "trash" && operation.phase in setOf("stored", "purging"))
        val files = operation.files.take(20).map { file ->
            RecoveryFilePreview(file.name, file.bytes,
                folderLabel(if (operation.kind == "trash") file.target else file.source),
                folderLabel(if (operation.kind == "trash") file.source else file.target))
        }
        check(scope() == profile)
        OperationRestorePreview(profile.first, operation, purge, files, folderLabel(operation.source, true), folderLabel(operation.target, true))
    }

    /** The confirmed receipt must still describe the same files, and the profile must still permit it. */
    suspend fun revalidate(preview: OperationRestorePreview): LibraryOperation = withContext(Dispatchers.IO) {
        val profile = scope()
        check(profile.first == preview.profileId)
        val fresh = history.get(preview.operation.id) ?: error("Recovery receipt unavailable")
        check(fresh == preview.operation && allowed(fresh, profile)) { "Recovery changed; check it again" }
        check(fresh.phase != "done")
        fresh
    }

    suspend fun previewReplacement(replacement: ReplacementRecovery): ReplacementRestorePreview = withContext(Dispatchers.IO) {
        val profile = scope()
        check(profile.first.isBlank() && !profile.second.active)
        val folder = exactFolder(replacement.folderUri)
        check(StorageHelper.pendingRecoveries(context, folder).contains(replacement.recovery)) { "Recovery receipt changed" }
        val backup = folder.findFile(replacement.recovery.backupName) ?: error("Recovery copy unavailable")
        check(copier.hash(backup.uri) == replacement.recovery.sha256) { "Recovery copy changed" }
        val current = folder.findFile(replacement.recovery.fileName)
        val currentHash = current?.let { check(it.isFile); copier.hash(it.uri) }
        check(currentHash == null || currentHash == replacement.recovery.sha256) { "A different current file is protected" }
        check(scope() == profile)
        ReplacementRestorePreview(profile.first, replacement, currentHash)
    }

    suspend fun restoreReplacement(preview: ReplacementRestorePreview) = withContext(Dispatchers.IO) {
        appSettings.withActiveProfile(preview.profileId) { gate.lock.withLock {
            val fresh = previewReplacement(preview.replacement)
            check(fresh == preview) { "Recovery target changed; check it again" }
            val folder = exactFolder(preview.replacement.folderUri)
            currentCoroutineContext().ensureActive()
            StorageHelper.restoreRecovery(context, folder, preview.replacement.recovery)
        } }
    }

    private fun exactFolder(uri: String): DocumentFile {
        check(StorageAvailability.readable(StorageAvailabilityService.probe(context, uri))) { "Recovery folder unavailable" }
        return StorageHelper.getDocumentFile(context, uri) ?: error("Recovery folder unavailable")
    }

    /** Human readable document paths, without exposing the content provider URI or a server address. */
    private fun folderLabel(uri: String, selectedFolder: Boolean = false): String = runCatching {
        if (uri.isBlank()) return@runCatching context.getString(R.string.road28_recovery_folder)
        val parsed = Uri.parse(uri)
        val id = android.provider.DocumentsContract.getDocumentId(parsed)
        val path = id.substringAfter(':', id).let { if (selectedFolder) it else it.substringBeforeLast('/', "") }
        path.takeIf { it.isNotBlank() } ?: context.getString(R.string.road28_recovery_folder)
    }.getOrDefault(context.getString(R.string.road28_recovery_folder))

    private companion object {
        const val MAX_DEPTH = 16
        const val MAX_DIRECTORIES = 1000
        const val MAX_RECOVERIES = 500
    }
}
