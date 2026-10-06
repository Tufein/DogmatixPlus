package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.ContinuePlaying
import com.cortinadev.dogmatix.util.GameTitleCleaner
import com.cortinadev.dogmatix.util.RecapDownload
import com.cortinadev.dogmatix.util.RecapInput
import com.cortinadev.dogmatix.util.RecapPlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Recap"

/**
 * Gathers what "Your year in games" (7.0) is built from: the Downloads history, the statistics
 * log and, when ES-DE is set up, the games it recorded as played (once: [PlayHistoryService] maps
 * the same plays but drops the play counts, which the recap needs for "most played"). Nothing is
 * cached; the recap screen asks once per visit.
 */
@Singleton
class RecapService @Inject constructor(
    private val downloadHistory: DownloadHistoryDao,
    private val downloadLog: DownloadLog,
    private val fileDao: DownloadableFileDao,
    private val esdePlays: EsdePlayService,
    private val consoleRepository: ConsoleRepository
) {
    suspend fun load(): RecapInput = withContext(Dispatchers.IO) {
        RecapInput(downloads = downloads(), log = downloadLog.entries(), plays = plays())
    }

    private suspend fun downloads(): List<RecapDownload> = runCatching {
        downloadHistory.getAll()
            .filter { it.status == DownloadStatus.COMPLETED.name && (it.finishedAt ?: 0L) > 0L }
            .map { RecapDownload(it.finishedAt ?: 0L, it.consoleId, it.fileName, it.name.ifBlank { it.fileName }, it.fileSize) }
    }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Downloads not read: ${it.message}") }
        .getOrDefault(emptyList())

    /** ES-DE's plays as recap entries; null when ES-DE is not set up (or could not be read). */
    private suspend fun plays(): List<RecapPlay>? {
        val all = runCatching { esdePlays.plays() }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "ES-DE plays not read: ${it.message}") }
            .getOrNull() ?: return null
        val played = ContinuePlaying.recentPlays(all, Int.MAX_VALUE)
        val rows = played.map { ContinuePlaying.playFileName(it) }.distinct().chunked(400)
            .flatMap { runCatching { fileDao.filesByFileNames(it) }.getOrDefault(emptyList()) }
        val consoleIds = runCatching { consoleRepository.getAllConsoles().first().map { it.id } }.getOrDefault(emptyList())
        return played.map { play ->
            val name = ContinuePlaying.playFileName(play)
            val candidates = rows.filter { it.fileName.equals(name, ignoreCase = true) }
            val row = ContinuePlaying.rowForPlay(play, candidates, { it.consoleId }, { it.fileName }, ConsoleFolderAliases::matches)
            val at = play.lastPlayed ?: 0L
            if (row != null) {
                RecapPlay(at, row.consoleId, row.fileName, row.name.ifBlank { name }, play.playCount)
            } else {
                // Not in the library (any more): it still counts, filed under the console its ES-DE system stands for.
                val console = consoleIds.firstOrNull { ConsoleFolderAliases.matches(it, play.system) } ?: play.system
                val title = play.name.ifBlank { GameTitleCleaner.clean(name) }.ifBlank { name }
                RecapPlay(at, console, name, title, play.playCount)
            }
        }
    }
}
