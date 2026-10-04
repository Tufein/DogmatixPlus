package com.cortinadev.dogmatix.data.service

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.DiskDir
import com.cortinadev.dogmatix.util.DiskScanner
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Knows what is already on disk: the file names under the download folders (two levels deep)
 * so the library can mark owned games, plus the free space of the download volume.
 * Keys are scoped by the folder they were found in (see [LibraryKeys]) so a game owned for one
 * console is not marked for another console whose ROM happens to share the file name.
 * Rescans when the folders change in Settings or a download finishes.
 */
@OptIn(FlowPreview::class)
@Singleton
class LibraryIndexService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val downloadService: DownloadService,
    private val downloadableFileDao: DownloadableFileDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ownedKeys = MutableStateFlow<Set<String>>(emptySet())
    /** `scope|name` keys of the files found on disk; see [LibraryKeys]. */
    val ownedKeys: StateFlow<Set<String>> = _ownedKeys.asStateFlow()

    private val _freeBytes = MutableStateFlow<Long?>(null)
    val freeBytes: StateFlow<Long?> = _freeBytes.asStateFlow()

    init {
        scope.launch {
            combine(settingsRepository.downloadDirectory, settingsRepository.consoleDownloadDirectories) { root, perConsole ->
                listOf(root) + perConsole.values
            }.distinctUntilChanged().collect { refresh() }
        }
        scope.launch {
            downloadService.downloads
                .map { list -> list.filter { it.status == DownloadStatus.COMPLETED }.map { it.fileName }.toSet() }
                .distinctUntilChanged()
                // Mark finished downloads as owned right away; the full disk walk is slow over SAF.
                // Only the ones that finished since the last change: looking up every finished row
                // again each time was quadratic, and each lookup scanned the whole library table.
                .onEach { completed ->
                    val keys = finishedNames.next(completed).flatMap { keysForCompleted(it) }
                    if (keys.isNotEmpty()) _ownedKeys.update { it + keys }
                }
                .debounce(500)
                .collect { refresh() }
        }
    }

    /** Finished downloads already marked as owned (see [FreshNames]). */
    private val finishedNames = com.cortinadev.dogmatix.util.FreshNames()

    /** Keys for a download that just finished: scoped to its console (known from the download itself). */
    private suspend fun keysForCompleted(fileName: String): List<String> {
        val consoleId = (downloadService.entityFor(fileName) ?: downloadableFileDao.getFileByFileName(fileName))?.consoleId
            ?: return emptyList()
        return LibraryKeys.keysFor(LibraryKeys.consoleScope(consoleId), fileName)
    }

    fun isOwned(file: DownloadableFileEntity, keys: Set<String> = _ownedKeys.value): Boolean =
        LibraryKeys.isOwned(file.consoleId, file.fileName, keys)

    /** Starts a [refresh] on the service's own scope: it outlives the screen that asked for it. */
    fun requestRefresh() {
        scope.launch { refresh() }
    }

    /**
     * Reads the download folders again. Each folder is listed with one provider query
     * ([DiskScanner]) instead of one per file, and the console folders are read a few at a time.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val root = settingsRepository.downloadDirectory.first()
        val custom = settingsRepository.consoleDownloadDirectories.first()
        val keys = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        val limit = Semaphore(PARALLEL_FOLDERS)
        coroutineScope {
            if (root.isNotBlank()) DiskScanner.rootOf(root)?.let { dir ->
                for (child in DiskScanner.list(context, dir)) {
                    if (child.isDirectory) launch { limit.withPermit { collect(DiskScanner.dirOf(dir, child), LibraryKeys.folderScope(child.name), keys, depth = 1) } }
                    else keys += LibraryKeys.keysFor(LibraryKeys.ROOT_SCOPE, child.name)
                }
            }
            custom.forEach { (consoleId, uri) ->
                val dir = uri.takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) } ?: return@forEach
                launch { limit.withPermit { collect(dir, LibraryKeys.customScope(consoleId), keys, depth = 0) } }
            }
        }
        _ownedKeys.value = HashSet(keys)
        _freeBytes.value = (listOf(root) + custom.values).firstOrNull { it.isNotBlank() }
            ?.let { StorageHelper.getFreeBytes(context, it) }
    }

    /**
     * Delete every file on disk that [isOwned] would match for [file] — only inside the folders
     * that belong to its console — then refresh the index. Returns true if something was removed.
     */
    suspend fun deleteOwned(file: DownloadableFileEntity): Boolean = withContext(Dispatchers.IO) {
        val name = FileParsingUtils.decodeUrlEncodedFileName(file.fileName).lowercase()
        val base = LibraryKeys.baseName(name)
        val scopes = LibraryKeys.scopesFor(file.consoleId)
        val root = settingsRepository.downloadDirectory.first()
        val custom = settingsRepository.consoleDownloadDirectories.first()
        var deleted = false

        if (root.isNotBlank()) {
            runCatching { StorageHelper.getDocumentFile(context, root) }.getOrNull()?.let { dir ->
                val children = runCatching { dir.listFiles() }.getOrNull().orEmpty()
                for (child in children) {
                    val childName = child.name ?: continue
                    if (child.isDirectory) {
                        if (LibraryKeys.folderScope(childName) in scopes) {
                            deleted = deleteMatching(child, name, base, depth = 1) || deleted
                        }
                    } else if (matches(childName, name, base)) {
                        deleted = runCatching { child.delete() }.getOrDefault(false) || deleted
                    }
                }
            }
        }
        custom[file.consoleId]?.takeIf { it.isNotBlank() }?.let { uri ->
            runCatching { StorageHelper.getDocumentFile(context, uri) }.getOrNull()?.let { dir ->
                deleted = deleteMatching(dir, name, base, depth = 0) || deleted
            }
        }
        if (deleted) {
            // Drop the keys now so the list reflects the deletion immediately; rescan in the background.
            _ownedKeys.update { keys -> keys.filterNot { k -> scopes.any { s -> k == "$s|$name" || k == "$s|$base" } }.toSet() }
            scope.launch { refresh() }
        }
        deleted
    }

    private fun matches(childName: String, name: String, base: String): Boolean {
        val lower = childName.lowercase()
        return lower == name || LibraryKeys.baseName(lower) == base
    }

    private fun deleteMatching(dir: DocumentFile, name: String, base: String, depth: Int): Boolean {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return false
        var deleted = false
        for (child in children) {
            val childName = child.name ?: continue
            if (child.isDirectory) {
                if (depth < 2) deleted = deleteMatching(child, name, base, depth + 1) || deleted
            } else if (matches(childName, name, base)) {
                deleted = runCatching { child.delete() }.getOrDefault(false) || deleted
            }
        }
        return deleted
    }

    private fun collect(dir: DiskDir, scope: String, into: MutableSet<String>, depth: Int) {
        for (child in DiskScanner.list(context, dir)) {
            if (child.isDirectory) {
                if (depth < 2) collect(DiskScanner.dirOf(dir, child), scope, into, depth + 1)
            } else {
                into += LibraryKeys.keysFor(scope, child.name)
            }
        }
    }

    private companion object {
        /** Console folders listed at the same time. */
        const val PARALLEL_FOLDERS = 4
    }
}
