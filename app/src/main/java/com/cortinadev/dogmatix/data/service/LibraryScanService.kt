package com.cortinadev.dogmatix.data.service

import android.content.Context
import androidx.core.net.toUri
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.model.ResolvedDownloadPath
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DuplicateFinder
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.SourcesJson
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.DiskDir
import com.cortinadev.dogmatix.util.DiskFile
import com.cortinadev.dogmatix.util.DiskScanner
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.GameEntry
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the download folders hold right now, attributed to consoles where possible. */
data class DiskSnapshot(
    val files: List<DiskFile>,
    /** Top-level folders of the download directory that match no console. */
    val unmatchedFolders: List<String>,
    /** Readable path of the download directory, empty when none is set. */
    val rootDisplay: String,
    val freeBytes: Long?,
    val scannedAt: Long = System.currentTimeMillis()
)

/** One console in the library overview: what its sources indexed against what is on disk. */
data class ConsoleOverview(
    val id: String,
    val name: String,
    val shortName: String,
    val sources: Int,
    val enabledSources: Int,
    /** Games the last source scan indexed for this console. */
    val indexed: Int,
    /** Indexed games that are already on disk (the "owned" mark in the library). */
    val owned: Int,
    /** Games on disk in this console's folders, whether the library knows them or not. */
    val onDisk: Int,
    val onDiskBytes: Long,
    val path: ResolvedDownloadPath?,
    /** Epoch millis of this console's last source scan; null if not recorded yet. */
    val scannedAt: Long?
) {
    enum class Status { OK, NO_SOURCES, NOTHING_FOUND, NOT_SCANNED }

    val status: Status get() = when {
        enabledSources == 0 -> Status.NO_SOURCES
        indexed == 0 && scannedAt == null -> Status.NOT_SCANNED
        indexed == 0 -> Status.NOTHING_FOUND
        else -> Status.OK
    }
}

data class LibraryOverview(
    val consoles: List<ConsoleOverview>,
    val disk: DiskSnapshot,
    /** Games on disk outside every known console folder (loose in the root or unknown folders). */
    val unassignedGames: Int
) {
    val indexed: Int get() = consoles.sumOf { it.indexed }
    val owned: Int get() = consoles.sumOf { it.owned }
    val onDisk: Int get() = consoles.sumOf { it.onDisk } + unassignedGames
    val onDiskBytes: Long get() = disk.files.filter { DuplicateFinder.isGameFile(it.name) }.sumOf { it.size }
}

/**
 * Walks the download directory and the per-console directories (the same places and depth as
 * [LibraryIndexService]) and returns every file with its size and console. Feeds the duplicate
 * finder and the library overview; deleting goes through here too so the owned-index is refreshed.
 */
@Singleton
class LibraryScanService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val consoleDao: ConsoleDao,
    private val downloadableFileDao: DownloadableFileDao,
    private val libraryIndexService: LibraryIndexService,
    private val pathResolver: ConsoleDownloadPathResolver
) {

    /** Per-console numbers for the library overview; walks the disk once. */
    suspend fun overview(): LibraryOverview = withContext(Dispatchers.IO) {
        val disk = scan()
        val consoles = consoleDao.getAllConsoles().first()
        val counts = downloadableFileDao.countsByConsole().associate { it.consoleId to it.count }
        val scannedAt = settingsRepository.consoleScannedAt.first()
        val paths = runCatching { pathResolver.resolveAll(settingsRepository, consoles.map { it.id }) }.getOrDefault(emptyMap())
        val ownedKeys = libraryIndexService.ownedKeys.value
        val entries = DuplicateFinder.entries(disk.files)
        val entriesByConsole = entries.groupBy { it.consoleId }

        val rows = consoles.map { console ->
            val urls = SourcesJson.parseUrlEntries(console.urls)
            val indexed = counts[console.id] ?: 0
            val owned = if (indexed == 0 || ownedKeys.isEmpty()) 0 else
                downloadableFileDao.fileNamesFor(console.id).count { LibraryKeys.isOwned(console.id, it, ownedKeys) }
            val onDisk = entriesByConsole[console.id].orEmpty()
            ConsoleOverview(
                id = console.id,
                name = console.name,
                shortName = ConsoleFormatter.getConsoleShortName(console.id),
                sources = urls.size,
                enabledSources = urls.count { it.enabled },
                indexed = indexed,
                owned = owned,
                onDisk = onDisk.size,
                onDiskBytes = onDisk.sumOf { it.size },
                path = paths[console.id],
                scannedAt = scannedAt[console.id]
            )
        }.sortedBy { it.name.lowercase() }
        LibraryOverview(rows, disk, entriesByConsole[null].orEmpty().size)
    }

    suspend fun scan(): DiskSnapshot = withContext(Dispatchers.IO) {
        val root = settingsRepository.downloadDirectory.first()
        val custom = settingsRepository.consoleDownloadDirectories.first()
        val consoleIds = consoleDao.getAllConsoles().first().map { it.id }
        val files = ArrayList<DiskFile>()
        val unmatched = ArrayList<String>()
        val rootDisplay = FileParsingUtils.toUserReadablePath(root)
        // A per-console directory may sit inside the download directory: walk it only once (as
        // the custom one), or every file in it would show up as its own duplicate.
        val customDirs = custom.mapNotNull { (consoleId, uri) -> DiskScanner.rootOf(uri)?.let { consoleId to it } }
        val skip = customDirs.map { it.second.documentId }.toSet()

        DiskScanner.rootOf(root)?.let { rootDir ->
            for (child in DiskScanner.list(context, rootDir)) {
                if (child.name.startsWith(".") || child.documentId in skip) continue
                if (child.isDirectory) {
                    val consoleId = consoleIds.firstOrNull { ConsoleFolderAliases.matches(it, child.name) }
                    if (consoleId == null) unmatched += child.name
                    val scope = consoleId ?: "folder:${ConsoleFolderAliases.normalize(child.name)}"
                    walk(DiskScanner.dirOf(rootDir, child), scope, consoleId, "$rootDisplay/${child.name}", depth = 1, into = files, skip = skip)
                } else {
                    files += DiskFile("", null, rootDisplay, child.name, child.size, child.uri.toString())
                }
            }
        }
        customDirs.forEach { (consoleId, dir) ->
            walk(dir, consoleId, consoleId, FileParsingUtils.toUserReadablePath(custom.getValue(consoleId)), depth = 0, into = files, skip = skip - dir.documentId)
        }

        val freeBytes = (listOf(root) + custom.values).firstOrNull { it.isNotBlank() }
            ?.let { StorageHelper.getFreeBytes(context, it) }
        DiskSnapshot(files, unmatched.sortedBy { it.lowercase() }, rootDisplay, freeBytes)
    }

    /** Deletes every file of [entry]; returns how many were removed and refreshes the owned index. */
    suspend fun delete(entry: GameEntry): Int = withContext(Dispatchers.IO) {
        val removed = entry.files.count { DiskScanner.delete(context, it.uri.toUri()) }
        if (removed > 0) libraryIndexService.refresh()
        removed
    }

    private fun walk(dir: DiskDir, scope: String, consoleId: String?, display: String, depth: Int, into: MutableList<DiskFile>, skip: Set<String>) {
        for (child in DiskScanner.list(context, dir)) {
            if (child.name.startsWith(".") || child.documentId in skip) continue
            if (child.isDirectory) {
                if (depth < 2) walk(DiskScanner.dirOf(dir, child), scope, consoleId, "$display/${child.name}", depth + 1, into, skip)
            } else {
                into += DiskFile(scope, consoleId, display, child.name, child.size, child.uri.toString())
            }
        }
    }
}
