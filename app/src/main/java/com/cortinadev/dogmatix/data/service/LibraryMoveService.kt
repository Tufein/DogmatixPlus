package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.DiskDir
import com.cortinadev.dogmatix.util.DiskScanner
import com.cortinadev.dogmatix.util.LibraryMove
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LibraryMoveService"

enum class MoveProblem { NO_SOURCE, CANNOT_OPEN, OVERLAP, DOWNLOADS_ACTIVE, NO_ROOM }

data class MoveState(
    val running: Boolean = false,
    val scanning: Boolean = false,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val current: String = "",
    val failed: Int = 0,
    /** Set when the run ended: the library is where it was meant to go (all files moved and the setting switched). */
    val finished: Boolean = false,
    val problem: MoveProblem? = null,
    /** For [MoveProblem.NO_ROOM]: how much is needed and how much is free. */
    val needBytes: Long = 0,
    val freeBytes: Long = 0
)

/**
 * Moves the download folder to another storage: every file is copied to the same place below the
 * new folder, the copy is checked against the original's size, and only then is the original
 * deleted. A file that cannot be copied stays where it is. Run it again after an interruption and
 * it carries on (files already there are not copied twice). When everything is moved, Dogmatix
 * switches to the new folder; folders given to a console of its own are not touched.
 */
@Singleton
class LibraryMoveService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val downloadService: DownloadService,
    private val libraryIndex: LibraryIndexService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(MoveState())
    val state: StateFlow<MoveState> = _state.asStateFlow()

    fun start(destinationUri: String) {
        if (job?.isActive == true) return
        job = scope.launch { run(destinationUri) }
    }

    fun cancel() { job?.cancel() }

    fun dismiss() { if (job?.isActive != true) _state.value = MoveState() }

    private suspend fun run(destinationUri: String) {
        _state.value = MoveState(running = true, scanning = true)
        try {
            if (downloadService.hasActiveDownloads()) return stop(MoveProblem.DOWNLOADS_ACTIVE)
            val sourceUri = settingsRepository.downloadDirectory.first()
            val source = sourceUri.takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) } ?: return stop(MoveProblem.NO_SOURCE)
            val destination = DiskScanner.rootOf(destinationUri) ?: return stop(MoveProblem.CANNOT_OPEN)
            if (LibraryMove.overlaps(DiskScanner.canonicalKey(source), DiskScanner.canonicalKey(destination))) return stop(MoveProblem.OVERLAP)

            val files = ArrayList<Pair<LibraryMove.Item, Uri>>()
            walk(source, "", files)
            val total = LibraryMove.totalBytes(files.map { it.first })
            val free = StorageHelper.getFreeBytes(context, destinationUri)
            if (LibraryMove.fits(total, free) == false) {
                _state.value = MoveState(problem = MoveProblem.NO_ROOM, needBytes = total, freeBytes = free ?: 0)
                return
            }
            _state.value = MoveState(running = true, filesTotal = files.size, bytesTotal = total)

            var failed = 0
            var lastUpdate = 0L
            var bytesDone = 0L
            files.forEachIndexed { index, (item, uri) ->
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(current = item.name, filesDone = index) }
                val before = bytesDone
                val outcome = copyOne(destinationUri, item, uri) { delta ->
                    bytesDone += delta
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 500) { lastUpdate = now; _state.update { it.copy(bytesDone = bytesDone) } }
                }
                val copySize = copiedSize(destinationUri, item)
                if (LibraryMove.mayDeleteOriginal(outcome, item.size, copySize)) {
                    if (!DiskScanner.delete(context, uri)) Log.w(TAG, "Copied ${item.name} but could not delete the original")
                } else {
                    failed++
                    bytesDone = before + item.size
                }
                _state.update { it.copy(bytesDone = bytesDone, failed = failed) }
            }

            val moved = files.size - failed
            if (failed == 0) {
                settingsRepository.updateDownloadDirectory(destinationUri)
                libraryIndex.requestRefresh()
            }
            _state.value = MoveState(filesDone = files.size, filesTotal = files.size, bytesDone = total, bytesTotal = total, failed = failed, finished = true)
            val msg = if (failed == 0) context.getString(R.string.storage_move_done, moved) else context.getString(R.string.storage_move_done_failed, failed)
            withContext(Dispatchers.Main) { if (failed == 0) ToastUtil.showSuccess(context, msg) else ToastUtil.showError(context, msg) }
        } catch (e: CancellationException) {
            _state.update { it.copy(running = false, scanning = false) }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Move failed: ${e.message}", e)
            _state.update { it.copy(running = false, scanning = false, finished = true, failed = it.failed.coerceAtLeast(1)) }
        }
    }

    private fun stop(problem: MoveProblem) { _state.value = MoveState(problem = problem) }

    private fun walk(dir: DiskDir, path: String, out: MutableList<Pair<LibraryMove.Item, Uri>>) {
        for (entry in DiskScanner.list(context, dir)) {
            if (entry.isDirectory) walk(DiskScanner.dirOf(dir, entry), LibraryMove.join(path, entry.name), out)
            else out += LibraryMove.Item(path, entry.name, entry.size) to entry.uri
        }
    }

    private fun copiedSize(destinationUri: String, item: LibraryMove.Item): Long = runCatching {
        StorageHelper.createDirectory(context, destinationUri, item.dirPath)?.findFile(item.name)?.length() ?: -1L
    }.getOrDefault(-1L)

    /** Copies one file below the destination; a half-written copy is removed again. */
    private suspend fun copyOne(destinationUri: String, item: LibraryMove.Item, source: Uri, onBytes: (Long) -> Unit): LibraryMove.Outcome {
        val dir = StorageHelper.createDirectory(context, destinationUri, item.dirPath) ?: return LibraryMove.Outcome.FAILED
        val existing = runCatching { dir.findFile(item.name) }.getOrNull()
        if (existing != null && existing.isFile && LibraryMove.alreadyThere(existing.length(), item.size)) {
            onBytes(item.size)
            return LibraryMove.Outcome.ALREADY_THERE
        }
        runCatching { existing?.delete() }
        val target = runCatching { dir.createFile("application/octet-stream", item.name) }.getOrNull() ?: return LibraryMove.Outcome.FAILED
        return try {
            val input = context.contentResolver.openInputStream(source) ?: return LibraryMove.Outcome.FAILED
            val output = context.contentResolver.openOutputStream(target.uri) ?: run { input.close(); runCatching { target.delete() }; return LibraryMove.Outcome.FAILED }
            input.use { i ->
                output.use { o ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = i.read(buffer)
                        if (n < 0) break
                        o.write(buffer, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
            if (target.length() == item.size) LibraryMove.Outcome.COPIED
            else { runCatching { target.delete() }; LibraryMove.Outcome.FAILED }
        } catch (e: Exception) {
            runCatching { target.delete() }
            if (e is CancellationException) throw e
            Log.w(TAG, "Could not copy ${item.name}: ${e.message}")
            LibraryMove.Outcome.FAILED
        }
    }
}
