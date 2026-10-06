package com.cortinadev.dogmatix.data.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.DogmatixApplication
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.SmartStorageSettings
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.FavouriteDao
import com.cortinadev.dogmatix.data.model.ResolvedDownloadPath
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ApkAssets
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DiskDir
import com.cortinadev.dogmatix.util.DiskScanner
import com.cortinadev.dogmatix.util.LibraryMove
import com.cortinadev.dogmatix.util.SmartStorage
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SmartStorageService"
private const val NOTIFICATION_ID = 8412
private const val ESDE_BUNDLED_SYSTEMS = "assets/systems/android/es_systems.xml"

/** Why smart storage cannot plan right now. */
enum class SmartProblem { NO_SD, NO_LIBRARY, SAME_STORAGE, BUSY }

/** A dry run: what a run would do now. */
data class SmartPreview(val plan: SmartStorage.Plan? = null, val problem: SmartProblem? = null)

/** A run's progress and, when it is over, its outcome. */
data class SmartRunState(
    val running: Boolean = false,
    val consoleId: String? = null,
    val consoleIndex: Int = 0,
    val consoleCount: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val finished: Boolean = false,
    val moved: Int = 0,
    val failed: Int = 0,
    val problem: SmartProblem? = null
)

/**
 * 8.0 smart storage (see [SmartStorage] for why it moves whole consoles). Runs on demand from the
 * Storage tool and weekly while charging ([SmartStorageScheduler]).
 *
 * A console moves like this: every file is copied into a folder of the same name on the other side;
 * the copy is verified (every file there, every size equal); only then does the console switch to
 * the new folder (a folder of its own on the SD card, or back to its folder in the download folder),
 * and only then are the originals removed. Files that arrived in the old folder in the meantime are
 * moved after it the same way. A console whose copy cannot be verified stays where it was, untouched;
 * what was copied is kept on the other side, so the next run carries on instead of starting over.
 * When ES-DE is set up in Dogmatix+, its system path follows the console (custom_systems).
 */
@Singleton
class SmartStorageService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val settings: SmartStorageSettings,
    private val consoleDao: ConsoleDao,
    private val favouriteDao: FavouriteDao,
    private val pathResolver: ConsoleDownloadPathResolver,
    private val downloadService: DownloadService,
    private val libraryIndex: LibraryIndexService,
    private val libraryMove: LibraryMoveService,
    private val frontendMetadata: FrontendMetadataService,
    private val esdePlay: EsdePlayService
) {
    enum class Trigger { MANUAL, AUTO }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runLock = Mutex()
    private var job: Job? = null

    private val _state = MutableStateFlow(SmartRunState())
    val state: StateFlow<SmartRunState> = _state.asStateFlow()

    /** One file of a console folder: [path] below the folder. */
    private data class FileItem(val dirPath: String, val name: String, val size: Long, val uri: Uri) {
        val path: String get() = LibraryMove.join(dirPath, name)
    }

    private data class Folder(val dir: DiskDir, val files: List<FileItem>)

    private data class Snapshot(
        val consoles: List<SmartStorage.Console>,
        val folders: Map<String, Folder>,
        val root: String,
        val sdUri: String,
        val internalFree: Long?,
        val sdFree: Long?
    )

    // ---- Planning -------------------------------------------------------------------------------

    /** What a run would do now (nothing is changed). */
    suspend fun preview(): SmartPreview = withContext(Dispatchers.IO) {
        when (val s = snapshot()) {
            is SmartProblem -> SmartPreview(problem = s)
            is Snapshot -> SmartPreview(plan(s, budget = null))
            else -> SmartPreview(problem = SmartProblem.NO_LIBRARY)
        }
    }

    private suspend fun plan(s: Snapshot, budget: Long?): SmartStorage.Plan =
        SmartStorage.plan(s.consoles, System.currentTimeMillis(), settings.recentDays.first(), s.internalFree, s.sdFree, budget)

    /** A [Snapshot], or the [SmartProblem] that stops planning. */
    private suspend fun snapshot(): Any {
        if (libraryMove.state.value.running || _state.value.running) return SmartProblem.BUSY
        val sdUri = settings.sdUri.first()
        val sdRoot = sdUri.takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) } ?: return SmartProblem.NO_SD
        if (StorageHelper.getDocumentFile(context, sdUri)?.canWrite() != true) return SmartProblem.NO_SD
        val root = settingsRepository.downloadDirectory.first()
        val libRoot = root.takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) } ?: return SmartProblem.NO_LIBRARY
        val separate = settingsRepository.separateByConsole.first()
        if (!separate) return SmartProblem.NO_LIBRARY
        if (SmartStorage.volumeOf(sdRoot.documentId) == SmartStorage.volumeOf(libRoot.documentId) ||
            LibraryMove.overlaps(DiskScanner.canonicalKey(sdRoot), DiskScanner.canonicalKey(libRoot))
        ) return SmartProblem.SAME_STORAGE

        val ids = consoleDao.getAllConsoles().first().map { it.id }
        val custom = settingsRepository.consoleDownloadDirectories.first()
        val resolved = pathResolver.resolveAll(ids, root, true, custom)
        val records = settings.recordsNow()
        // A console smart storage put on the SD card whose folder was changed by hand since is the user's again.
        settings.dropRecords(records.values.filter { it.place == SmartStorage.Place.SD && custom[it.consoleId] != it.uri }.map { it.consoleId })

        // Folders shared by two consoles stay put: one folder cannot be in two places.
        val detected = ids.mapNotNull { id ->
            resolved[id]?.takeIf { it.source == ResolvedDownloadPath.Source.DETECTED && it.alternatives.isEmpty() && custom[id] == null }?.let { id to it.subPath }
        }
        val shared = detected.groupBy { it.second.lowercase() }.filterValues { it.size > 1 }.keys
        val rootChildren = DiskScanner.list(context, libRoot).filter { it.isDirectory }.associateBy { it.name }

        val folders = LinkedHashMap<String, Folder>()
        val located = ArrayList<Triple<String, String, SmartStorage.Place>>()
        for ((id, sub) in detected) {
            if (sub.lowercase() in shared) continue
            val entry = rootChildren[sub] ?: continue
            folders[id] = Folder(DiskScanner.dirOf(libRoot, entry), emptyList())
            located += Triple(id, sub, SmartStorage.Place.INTERNAL)
        }
        for (record in records.values) {
            if (record.place != SmartStorage.Place.SD || custom[record.consoleId] != record.uri || record.consoleId !in ids) continue
            val dir = DiskScanner.rootOf(record.uri) ?: continue
            folders[record.consoleId] = Folder(dir, emptyList())
            located += Triple(record.consoleId, record.folder, SmartStorage.Place.SD)
        }

        val plays = runCatching { esdePlay.plays() }.getOrNull().orEmpty().map { it.system to it.lastPlayed }
        val favourites = favouriteDao.getAll().groupBy({ it.consoleId }, { it.fileName })
        val busy = busyConsoles()
        val consoles = ArrayList<SmartStorage.Console>()
        for ((id, folderName, place) in located) {
            currentCoroutineContext().ensureActive()
            val folder = folders.getValue(id)
            val files = ArrayList<FileItem>()
            walk(folder.dir, "", files)
            folders[id] = folder.copy(files = files)
            consoles += SmartStorage.Console(
                id = id, folder = folderName, place = place,
                bytes = files.sumOf { it.size.coerceAtLeast(0) }, files = files.size,
                lastPlayed = SmartStorage.lastPlayed(plays) { system -> system.equals(folderName, true) || ConsoleFolderAliases.matches(id, system) },
                favourite = SmartStorage.hasFavouriteOnDisk(favourites[id].orEmpty(), files.map { it.name }),
                busy = id in busy || ALL in busy,
                lastMovedAt = records[id]?.movedAt
            )
        }
        return Snapshot(consoles, folders, root, sdUri, StorageHelper.getFreeBytes(context, root), StorageHelper.getFreeBytes(context, sdUri))
    }

    /** Consoles a download (queued or running) or a frontend metadata run is working on; "*" = all. */
    private fun busyConsoles(): Set<String> {
        val out = HashSet<String>()
        downloadService.downloads.value.filterNot { it.isFinished }.forEach { item ->
            downloadService.entityFor(item.fileName)?.consoleId?.let(out::add)
        }
        val meta = frontendMetadata.state.value
        if (meta.running) {
            // A run over every console makes every console busy.
            out += meta.scope ?: ALL
            meta.consoleId?.let(out::add)
        }
        return out
    }

    private fun isBusy(id: String): Boolean = busyConsoles().let { ALL in it || id in it }

    // ---- Running --------------------------------------------------------------------------------

    /** Starts a run on the service's own scope (the screen may close meanwhile). */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run(Trigger.MANUAL) }
    }

    fun cancel() { job?.cancel() }

    fun dismiss() { if (job?.isActive != true) _state.value = SmartRunState() }

    /** The weekly run: only while smart storage is on and its weekly run too. Waits until done. */
    suspend fun runIfEnabled() {
        if (!settings.enabled.first() || !settings.weekly.first()) return
        run(Trigger.AUTO)
    }

    private suspend fun run(trigger: Trigger) {
        if (!runLock.tryLock()) return
        try {
            val snap = snapshot()
            if (snap !is Snapshot) {
                _state.value = SmartRunState(finished = true, problem = snap as? SmartProblem ?: SmartProblem.NO_LIBRARY)
                return
            }
            val plan = plan(snap, if (trigger == Trigger.AUTO) SmartStorage.AUTO_RUN_BUDGET_BYTES else null)
            val busyAll = ALL in busyConsoles()
            val moves = if (busyAll) emptyList() else plan.moves
            _state.value = SmartRunState(running = true, consoleCount = moves.size, bytesTotal = moves.sumOf { it.console.bytes })
            var moved = 0
            var failed = 0
            var bytes = 0L
            moves.forEachIndexed { index, move ->
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(consoleId = move.console.id, consoleIndex = index) }
                notifyProgress(move.console.id, index, moves.size)
                val ok = runCatching { moveConsole(snap, move) }
                    .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Moving ${move.console.id} failed: ${it.message}") }
                    .getOrDefault(false)
                if (ok) { moved++; bytes += move.console.bytes } else failed++
                _state.update { it.copy(moved = moved, failed = failed) }
            }
            if (moved > 0) libraryIndex.requestRefresh()
            if (moves.isNotEmpty()) settings.setLastRun(SmartStorage.RunInfo(System.currentTimeMillis(), moved, failed, bytes))
            _state.value = SmartRunState(finished = true, moved = moved, failed = failed, consoleCount = moves.size, bytesTotal = bytes, bytesDone = bytes)
            notifyDone(moved, failed, bytes)
        } catch (e: CancellationException) {
            _state.update { it.copy(running = false, finished = true) }
            cancelNotification()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Smart storage run failed: ${e.message}", e)
            _state.update { it.copy(running = false, finished = true, failed = it.failed.coerceAtLeast(1)) }
            cancelNotification()
        } finally {
            runLock.unlock()
        }
    }

    /** Moves one console; true when it now lives on the other side. */
    private suspend fun moveConsole(snap: Snapshot, move: SmartStorage.Move): Boolean {
        val c = move.console
        val source = snap.folders[c.id] ?: return false
        if (isBusy(c.id)) return false
        val targetBase = if (move.to == SmartStorage.Place.SD) snap.sdUri else snap.root
        val target = StorageHelper.createDirectory(context, targetBase, c.folder) ?: return false
        val targetDir = DiskScanner.dirOf(target.uri) ?: return false

        // 1. Copy everything (files already there with the right size count as copied).
        for (file in source.files) {
            currentCoroutineContext().ensureActive()
            if (!copy(file, target)) Log.w(TAG, "Could not copy ${file.path}")
        }
        // 2. Verify: every file there, every size equal. Otherwise nothing changes on the source side.
        val found = HashMap<String, Long>()
        sizes(targetDir, "", found)
        if (!SmartStorage.verified(source.files.associate { it.path to it.size }, found)) return false
        if (isBusy(c.id)) return false

        // 3. Switch the console to its new folder, then remember it. From here on the run finishes
        // the console even when it is stopped, so no console is left with its games on both sides.
        withContext(NonCancellable) { switchAndClean(c, move.to, source, target) }
        return true
    }

    private suspend fun switchAndClean(c: SmartStorage.Console, to: SmartStorage.Place, source: Folder, target: DocumentFile) {
        val now = System.currentTimeMillis()
        if (to == SmartStorage.Place.SD) {
            settingsRepository.updateConsoleDownloadDirectory(c.id, target.uri.toString())
            settings.putRecord(SmartStorage.Record(c.id, SmartStorage.Place.SD, now, c.folder, target.uri.toString()))
        } else {
            settingsRepository.updateConsoleDownloadDirectory(c.id, "")
            settings.putRecord(SmartStorage.Record(c.id, SmartStorage.Place.INTERNAL, now, c.folder, ""))
        }

        // 4. Only now the originals go, then whatever arrived meanwhile follows, then empty folders.
        for (file in source.files) if (!DiskScanner.delete(context, file.uri)) Log.w(TAG, "Copied ${file.path} but could not delete the original")
        val left = ArrayList<FileItem>()
        walk(source.dir, "", left)
        for (file in left) {
            if (copy(file, target) && copiedSize(target, file) == file.size) DiskScanner.delete(context, file.uri)
        }
        pruneEmpty(source.dir)

        // 5. ES-DE follows (when it is set up here).
        runCatching { updateEsde(c.folder, to, target.uri) }.onFailure { Log.w(TAG, "ES-DE path not updated: ${it.message}") }
    }

    private fun walk(dir: DiskDir, path: String, out: MutableList<FileItem>) {
        for (entry in DiskScanner.list(context, dir)) {
            if (entry.isDirectory) walk(DiskScanner.dirOf(dir, entry), LibraryMove.join(path, entry.name), out)
            else out += FileItem(path, entry.name, entry.size, entry.uri)
        }
    }

    private fun sizes(dir: DiskDir, path: String, out: MutableMap<String, Long>) {
        for (entry in DiskScanner.list(context, dir)) {
            if (entry.isDirectory) sizes(DiskScanner.dirOf(dir, entry), LibraryMove.join(path, entry.name), out)
            else out[LibraryMove.join(path, entry.name)] = entry.size
        }
    }

    /** Removes the folders left empty below [dir] (and [dir] itself), deepest first; only folders that list as empty. */
    private fun pruneEmpty(dir: DiskDir) {
        val children = DiskScanner.listOrNull(context, dir) ?: return
        for (child in children) if (child.isDirectory) pruneEmpty(DiskScanner.dirOf(dir, child))
        if (DiskScanner.listOrNull(context, dir)?.isEmpty() == true) DiskScanner.delete(context, DiskScanner.uriOf(dir))
    }

    private fun copiedSize(target: DocumentFile, file: FileItem): Long = runCatching {
        StorageHelper.createDirectory(target, file.dirPath)?.findFile(file.name)?.length() ?: -1L
    }.getOrDefault(-1L)

    /** Copies one file below [target]; a half-written copy is removed again. True when the copy is complete. */
    private suspend fun copy(file: FileItem, target: DocumentFile): Boolean {
        val dir = StorageHelper.createDirectory(target, file.dirPath) ?: return false
        val existing = runCatching { dir.findFile(file.name) }.getOrNull()
        if (existing != null && existing.isFile && LibraryMove.alreadyThere(existing.length(), file.size)) {
            _state.update { it.copy(bytesDone = it.bytesDone + file.size) }
            return true
        }
        runCatching { existing?.delete() }
        val out = runCatching { dir.createFile("application/octet-stream", file.name) }.getOrNull() ?: return false
        var lastUpdate = 0L
        var pending = 0L
        return try {
            val input = context.contentResolver.openInputStream(file.uri) ?: run { runCatching { out.delete() }; return false }
            val output = context.contentResolver.openOutputStream(out.uri) ?: run { input.close(); runCatching { out.delete() }; return false }
            input.use { i ->
                output.use { o ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = i.read(buffer)
                        if (n < 0) break
                        o.write(buffer, 0, n)
                        pending += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500) {
                            lastUpdate = now
                            val add = pending
                            pending = 0
                            _state.update { it.copy(bytesDone = it.bytesDone + add) }
                        }
                    }
                }
            }
            _state.update { it.copy(bytesDone = it.bytesDone + pending) }
            if (out.length() == file.size) true else { runCatching { out.delete() }; false }
        } catch (e: Exception) {
            runCatching { out.delete() }
            if (e is CancellationException) throw e
            Log.w(TAG, "Could not copy ${file.name}: ${e.message}")
            false
        }
    }

    /**
     * ES-DE reads each system from its ROM folder; a console on the SD card gets the absolute path
     * of its new folder in `custom_systems/es_systems.xml`, and the ROM folder again when it comes back.
     */
    private suspend fun updateEsde(folder: String, to: SmartStorage.Place, target: Uri) {
        val esdeUri = settingsRepository.esdeDirectory.first().takeIf { it.isNotBlank() } ?: return
        val esdeDir = StorageHelper.getDocumentFile(context, esdeUri)?.takeIf { it.isDirectory && it.canWrite() } ?: return
        val path = if (to == SmartStorage.Place.SD) {
            DiskScanner.canonicalPath(target.authority, DocumentsContract.getDocumentId(target)) ?: return
        } else SmartStorage.esdeRomPath(folder)
        val existing = StorageHelper.createDirectory(esdeDir, "custom_systems")?.findFile("es_systems.xml")
            ?.let { f -> runCatching { context.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }
        val bundled = if (to == SmartStorage.Place.SD) ApkAssets.readText(context, EsdeConfigService.ESDE_PACKAGE, ESDE_BUNDLED_SYSTEMS) else null
        val content = SmartStorage.esdeSystemsWithPath(existing, bundled, folder, path) ?: return
        StorageHelper.writeTextSafely(context, esdeDir, "custom_systems", "es_systems.xml", content)
    }

    // ---- Notification ---------------------------------------------------------------------------

    private fun canNotify(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 8,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notifyProgress(consoleId: String, index: Int, count: Int) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, DogmatixApplication.SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_drive_file_move)
            .setContentTitle(context.getString(R.string.store8_notify_title))
            .setContentText(context.getString(R.string.store8_notify_progress, ConsoleFormatter.getConsoleDisplayName(consoleId), index + 1, count))
            .setProgress(count, index, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    private fun notifyDone(moved: Int, failed: Int, bytes: Long) {
        if (!canNotify()) return
        if (moved == 0 && failed == 0) { cancelNotification(); return }
        val text = if (failed == 0) context.resources.getQuantityString(R.plurals.store8_notify_done, moved, moved, android.text.format.Formatter.formatShortFileSize(context, bytes))
            else context.resources.getQuantityString(R.plurals.store8_notify_failed, failed, failed)
        val notification = NotificationCompat.Builder(context, DogmatixApplication.SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_drive_file_move)
            .setContentTitle(context.getString(R.string.store8_notify_title))
            .setContentText(text)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelNotification() { runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) } }

    private companion object {
        const val ALL = "*"
    }
}
