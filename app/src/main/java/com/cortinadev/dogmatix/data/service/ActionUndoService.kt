package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.util.ActionEntry
import com.cortinadev.dogmatix.util.SearchNormalizer
import com.cortinadev.dogmatix.util.UndoAction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** How a way back ended. [PARTIAL]: some of the files are back, the rest is still in the trash. */
enum class UndoResult { DONE, PARTIAL, FAILED }

/**
 * The ways back of the action history ([UndoAction]). Restore goes through the recovery journal and
 * [TrashService.restore], exactly like Tools -> Trash and recovery; Download again queues the
 * library's own row of the game. Nothing here deletes anything.
 */
@Singleton
class ActionUndoService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val journal: OperationHistoryService,
    private val trash: TrashService,
    private val fileDao: DownloadableFileDao,
    private val downloadService: DownloadService,
    private val sourceTrack: SourceTrackService,
    private val actionLog: ActionLogService
) {
    /** Whether [action] of [entry] works right now (the files are still in the trash, the game is still in the library). */
    suspend fun available(entry: ActionEntry, action: UndoAction): Boolean = withContext(Dispatchers.IO) {
        try {
            when (action) {
                UndoAction.RESTORE -> restorable(entry.opId)
                UndoAction.DOWNLOAD_AGAIN -> libraryRow(entry)?.let { !queued(it.fileName) } ?: false
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }

    /** Runs [action] of [entry]. [UndoResult.DONE] only when the whole game is back (or queued). */
    suspend fun undo(entry: ActionEntry, action: UndoAction): UndoResult = withContext(Dispatchers.IO) {
        when (action) {
            UndoAction.RESTORE -> restore(entry)
            UndoAction.DOWNLOAD_AGAIN -> {
                try {
                    val row = libraryRow(entry)
                    if (row == null) UndoResult.FAILED else {
                        downloadService.startDownload(sourceTrack.pickBest(row, sameNameOnly = true))
                        actionLog.markUndone(entry.id)
                        UndoResult.DONE
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { UndoResult.FAILED }
            }
        }
    }

    private suspend fun restore(entry: ActionEntry): UndoResult {
        val id = entry.opId ?: return UndoResult.FAILED
        if (!restorable(id)) return UndoResult.FAILED
        val failed = try {
            trash.restore(id)
            false
        } catch (e: CancellationException) { throw e } catch (_: Exception) { true }
        val after = journal.get(id)
        // Back as a whole only when the journal says so; TrashService then wrote the "restored" line.
        if (!failed && after?.phase == "done") return UndoResult.DONE
        // Restore moves a file back and only then deletes its trash copy: a missing copy is a file that came back.
        val someBack = after?.files?.any { receipt ->
            runCatching { DocumentFile.fromSingleUri(context, Uri.parse(receipt.target))?.exists() == false }.getOrDefault(false)
        } ?: false
        return if (someBack) UndoResult.PARTIAL else UndoResult.FAILED
    }

    private fun restorable(opId: String?): Boolean {
        val op = opId?.let(journal::get) ?: return false
        return op.kind == "trash" && op.phase !in setOf("done", "purging")
    }

    private fun queued(fileName: String): Boolean = downloadService.downloads.value.any {
        it.fileName == fileName && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COPYING, DownloadStatus.UNZIPPING, DownloadStatus.PAUSED)
    }

    /**
     * The library row of the game [entry] names: by its library file name; a removal only knows the
     * names on disk, so failing that, the one row of the same console whose name (or file name
     * without extension) is the same. Never a guess between several versions.
     */
    private suspend fun libraryRow(entry: ActionEntry): DownloadableFileEntity? {
        val file = entry.fileName?.takeIf { it.isNotBlank() } ?: return null
        fileDao.getFileByFileName(file)?.takeIf { entry.consoleId.isNullOrBlank() || it.consoleId == entry.consoleId }?.let { return it }
        val console = entry.consoleId?.takeIf { it.isNotBlank() } ?: return null
        val title = SearchNormalizer.key(entry.title.ifBlank { file.substringBeforeLast('.') })
        if (title.isEmpty()) return null
        val stem = file.substringBeforeLast('.')
        val same = fileDao.filesMatching(title, console, 20).filter {
            it.fileName.substringBeforeLast('.').equals(stem, ignoreCase = true) || SearchNormalizer.key(it.name) == title
        }
        return same.singleOrNull()
    }
}
