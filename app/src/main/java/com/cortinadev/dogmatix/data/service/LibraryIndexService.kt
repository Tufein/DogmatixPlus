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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.GameArtifacts
import com.cortinadev.dogmatix.util.DiskEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Knows what is already on disk: the file names under the download folders (including nested archive folders)
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
    private val downloadableFileDao: DownloadableFileDao,
    private val trash: TrashService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    private val _ownedKeys = MutableStateFlow<Set<String>>(emptySet())
    /** `scope|name` keys of the files found on disk; see [LibraryKeys]. */
    val ownedKeys: StateFlow<Set<String>> = _ownedKeys.asStateFlow()

    private val _freeBytes = MutableStateFlow<Long?>(null)
    val freeBytes: StateFlow<Long?> = _freeBytes.asStateFlow()

    private val finishedNames = com.cortinadev.dogmatix.util.FreshNames()

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

    /** Keys for a download that just finished: scoped to its console (known from the download itself). */
    private suspend fun keysForCompleted(fileName: String): List<String> {
        val consoleId = (downloadService.entityFor(fileName) ?: downloadableFileDao.getFileByFileName(fileName))?.consoleId
            ?: return emptyList()
        val storedName = runCatching { FileParsingUtils.storageFileName(fileName) }.getOrNull() ?: return emptyList()
        return LibraryKeys.keysFor(LibraryKeys.consoleScope(consoleId), storedName)
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
    suspend fun refresh() = refreshLock.withLock { refreshNow() }

    private suspend fun refreshNow() = withContext(Dispatchers.IO) {
        val root = settingsRepository.downloadDirectory.first()
        val custom = settingsRepository.consoleDownloadDirectories.first()
        val keys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val limit = Semaphore(PARALLEL_FOLDERS)
        val knownConsoleFolders = ConsoleFolderAliases.knownFolderScopes()
        val visited = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val folderCount = AtomicInteger()
        val coroutine = currentCoroutineContext()
        val checkActive = { coroutine.ensureActive() }
        coroutineScope {
            if (root.isNotBlank()) DiskScanner.rootOf(root)?.let { dir ->
                for (child in DiskScanner.list(context, dir)) {
                    if (child.isDirectory) launch { limit.withPermit {
                        val folder = LibraryKeys.folderScope(child.name)
                        val scope = if (folder in knownConsoleFolders) folder else LibraryKeys.ROOT_SCOPE
                        collect(DiskScanner.dirOf(dir, child), scope, keys, depth = 1, visited, folderCount, checkActive)
                    } }
                    else keys += LibraryKeys.keysFor(LibraryKeys.ROOT_SCOPE, child.name)
                }
            }
            custom.forEach { (consoleId, uri) ->
                val dir = uri.takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) } ?: return@forEach
                launch { limit.withPermit { collect(dir, LibraryKeys.customScope(consoleId), keys, depth = 0, visited, folderCount, checkActive) } }
            }
        }
        _ownedKeys.value = HashSet(keys)
        _freeBytes.value = (listOf(root) + custom.values).firstOrNull { it.isNotBlank() }
            ?.let { StorageHelper.getFreeBytes(context, it) }
    }

    /** Exact files shown before confirmation. Fail closed if any folder cannot be listed. */
    suspend fun removalPlan(file: DownloadableFileEntity): List<RemovalFile> = artifactPlan(file, protectShared = true)

    /** Play also needs tracks/discs shared with another descriptor; these are read grants, never a deletion plan. */
    suspend fun launchPlan(file: DownloadableFileEntity): List<RemovalFile> = artifactPlan(file, protectShared = false)

    private suspend fun artifactPlan(file: DownloadableFileEntity, protectShared: Boolean): List<RemovalFile> = withContext(Dispatchers.IO) {
        if (!protectShared) return@withContext launchArtifacts(file)
        val name = FileParsingUtils.storageFileName(file.fileName)
        val scopes = LibraryKeys.scopesFor(file.consoleId)
        val result = ArrayList<RemovalFile>()
        fun addFiles(dir: DiskDir, entries: List<com.cortinadev.dogmatix.util.DiskEntry>, path: String) {
            fun references(entry: com.cortinadev.dogmatix.util.DiskEntry): List<String> {
                val ext = entry.name.substringAfterLast('.').lowercase()
                if (ext !in setOf("cue", "m3u", "gdi")) return emptyList()
                val text = context.contentResolver.openInputStream(entry.uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (out.size() <= 1024 * 1024) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                    val bytes = out.toByteArray()
                    check(bytes.size <= 1024 * 1024) { "Descriptor too large" }
                    bytes.toString(Charsets.UTF_8)
                } ?: error("Descriptor unavailable")
                return when (ext) {
                    "cue" -> com.cortinadev.dogmatix.util.SheetParser.cueFiles(text)
                    "gdi" -> com.cortinadev.dogmatix.util.SheetParser.gdiFiles(text)
                    else -> com.cortinadev.dogmatix.util.SheetParser.m3uFiles(text)
                }
            }
            val files = entries.filter { !it.isDirectory }.associateBy { it.name }
            val selected = com.cortinadev.dogmatix.util.GameArtifacts.plan(files.keys.toList(), name, protectShared) { references(files.getValue(it)) }
            selected.map { files.getValue(it) }.forEach { entry -> result += RemovalFile(entry.uri.toString(), DiskScanner.uriOf(dir).toString(), entry.name, entry.size, "$path${entry.name}") }
        }
        fun walk(dir: DiskDir, path: String, depth: Int) {
            val entries = DiskScanner.listOrNull(context, dir, true) ?: error("Folder cannot be read completely")
            addFiles(dir, entries, path)
            if (depth < 2) entries.filter { it.isDirectory && !it.name.startsWith(".dogmatix-") }.forEach { walk(DiskScanner.dirOf(dir, it), "$path${it.name}/", depth + 1) }
        }
        val root = settingsRepository.downloadDirectory.first()
        DiskScanner.rootOf(root)?.let { dir ->
            val entries = DiskScanner.listOrNull(context, dir, true) ?: error("Library unavailable")
            addFiles(dir, entries, "")
            entries.filter { it.isDirectory && LibraryKeys.folderScope(it.name) in scopes }.forEach { walk(DiskScanner.dirOf(dir, it), "${it.name}/", 1) }
        }
        settingsRepository.consoleDownloadDirectories.first()[file.consoleId]?.let { uri -> DiskScanner.rootOf(uri)?.let { walk(it, "", 0) } }
        result.distinctBy { it.uri }
    }

    /** [consoleId] and [fileName] (the library row's) only go to the action history. */
    suspend fun deletePlan(plan: List<RemovalFile>, title: String, consoleId: String? = null, fileName: String? = null): Boolean = withContext(Dispatchers.IO) {
        val removed = trash.move(plan, title, consoleId, fileName)
        if (removed > 0) requestRefresh()
        removed > 0
    }

    suspend fun deleteOwned(file: DownloadableFileEntity): Boolean = deletePlan(removalPlan(file), file.name, file.consoleId, file.fileName)

    /** Reads only selected descriptors, within a recognized console root or flat archive tree. */
    private suspend fun launchArtifacts(file: DownloadableFileEntity): List<RemovalFile> {
        val requested = FileParsingUtils.storageFileName(file.fileName)
        val scopes = LibraryKeys.scopesFor(file.consoleId)
        val knownConsoles = ConsoleFolderAliases.knownFolderScopes()
        val result = ArrayList<RemovalFile>()
        val coroutine = currentCoroutineContext()
        val checkActive = { coroutine.ensureActive() }
        fun plan(root: DiskDir, filterTopLevel: Boolean) {
            data class Located(val directory: DiskDir, val entry: DiskEntry)
            val files = LinkedHashMap<String, Located>()
            val visited = HashSet<String>()
            var directories = 0
            fun walk(dir: DiskDir, path: String, depth: Int) {
                checkActive()
                if (!visited.add(DiskScanner.canonicalKey(dir))) return
                check(++directories <= MAX_SCANNED_FOLDERS) { "Library has too many folders" }
                val entries = DiskScanner.listOrNull(context, dir, true) ?: error("Folder cannot be read completely")
                for (entry in entries) {
                    checkActive()
                    if (entry.name.startsWith(".dogmatix-")) continue
                    if (entry.isDirectory) {
                        if (depth == 0 && filterTopLevel) {
                            val scope = LibraryKeys.folderScope(entry.name)
                            if (scope in knownConsoles && scope !in scopes) continue
                        }
                        if (depth < MAX_SCAN_DEPTH) walk(DiskScanner.dirOf(dir, entry), "$path${entry.name}/", depth + 1)
                    } else {
                        val relative = "$path${entry.name}"
                        if (GameArtifacts.safeLaunchPath(relative)) {
                            check(relative !in files) { "Ambiguous library file" }
                            files[relative] = Located(dir, entry)
                        }
                    }
                }
            }
            walk(root, "", 0)
            val selected = GameArtifacts.playPaths(files.keys.toList(), requested) { path ->
                val entry = files.getValue(path).entry
                val text = context.contentResolver.openInputStream(entry.uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= MAX_DESCRIPTOR_BYTES) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    check(output.size() <= MAX_DESCRIPTOR_BYTES) { "Descriptor too large" }
                    output.toByteArray().toString(Charsets.UTF_8)
                } ?: error("Descriptor unavailable")
                when (entry.name.substringAfterLast('.').lowercase()) {
                    "cue" -> com.cortinadev.dogmatix.util.SheetParser.cueFiles(text)
                    "gdi" -> com.cortinadev.dogmatix.util.SheetParser.gdiFiles(text)
                    else -> com.cortinadev.dogmatix.util.SheetParser.m3uFiles(text)
                }
            }
            selected.forEach { path ->
                val found = files.getValue(path)
                val entry = found.entry
                result += RemovalFile(entry.uri.toString(), DiskScanner.uriOf(found.directory).toString(), entry.name, entry.size, path)
            }
        }
        DiskScanner.rootOf(settingsRepository.downloadDirectory.first())?.let { plan(it, filterTopLevel = true) }
        settingsRepository.consoleDownloadDirectories.first()[file.consoleId]?.let { uri ->
            DiskScanner.rootOf(uri)?.let { plan(it, filterTopLevel = false) }
        }
        return result.distinctBy { it.uri }
    }

    private fun collect(dir: DiskDir, scope: String, into: MutableSet<String>, depth: Int,
        visited: MutableSet<String>, folderCount: AtomicInteger, checkActive: () -> Unit) {
        checkActive()
        if (!visited.add("$scope|${DiskScanner.canonicalKey(dir)}") || folderCount.incrementAndGet() > MAX_SCANNED_FOLDERS) return
        for (child in DiskScanner.list(context, dir)) {
            checkActive()
            if (child.isDirectory) {
                if (depth < MAX_SCAN_DEPTH) collect(DiskScanner.dirOf(dir, child), scope, into, depth + 1, visited, folderCount, checkActive)
            } else {
                into += LibraryKeys.keysFor(scope, child.name)
            }
        }
    }

    private companion object {
        /** Console folders listed at the same time. */
        const val PARALLEL_FOLDERS = 4
        // Archive paths permit 64 segments; a console folder adds one container level.
        const val MAX_SCAN_DEPTH = 65
        const val MAX_SCANNED_FOLDERS = 10000
        const val MAX_DESCRIPTOR_BYTES = 1024 * 1024
    }
}
