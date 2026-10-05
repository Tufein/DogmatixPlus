package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.ContinuePlaying
import com.cortinadev.dogmatix.util.GameTitleCleaner
import com.cortinadev.dogmatix.util.HistoryEvent
import com.cortinadev.dogmatix.util.HistoryKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlayHistory"

/** At most this many ES-DE plays (newest first) are mapped to library games. */
private const val MAX_PLAYS = 600

/**
 * The raw events of the Play history tool: finished downloads (the downloads list's history),
 * games ES-DE says were played (read by [EsdePlayService], the same source Statistics and the
 * "Continue playing" shelf use: ES-DE only keeps the LAST time a game was played, so a game is one
 * "Played" entry), and, with RomM set up, the saves the "Continue playing" shelf knows.
 */
@Singleton
class PlayHistoryService @Inject constructor(
    private val downloadHistory: DownloadHistoryDao,
    private val fileDao: DownloadableFileDao,
    private val esdePlays: EsdePlayService,
    private val continuePlaying: ContinuePlayingService,
    private val rommClient: RommClient,
    private val settings: SettingsRepository
) {
    /** Finished downloads, newest first. */
    suspend fun downloads(): List<HistoryEvent> = withContext(Dispatchers.IO) {
        runCatching {
            downloadHistory.getAll()
                .filter { it.status == DownloadStatus.COMPLETED.name && (it.finishedAt ?: 0L) > 0L }
                .map { HistoryEvent(HistoryKind.DOWNLOADED, it.finishedAt ?: 0L, it.consoleId, it.fileName, it.name.ifBlank { it.fileName }) }
        }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Downloads not read: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** Games ES-DE played, newest first; null when ES-DE is not set up. */
    suspend fun plays(): List<HistoryEvent>? = withContext(Dispatchers.IO) {
        val all = runCatching { esdePlays.plays() }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "ES-DE plays not read: ${it.message}") }
            .getOrNull() ?: return@withContext null
        val recent = ContinuePlaying.recentPlays(all, MAX_PLAYS)
        val rows = recent.map { ContinuePlaying.playFileName(it) }.distinct().chunked(400)
            .flatMap { runCatching { fileDao.filesByFileNames(it) }.getOrDefault(emptyList()) }
        recent.map { play ->
            val name = ContinuePlaying.playFileName(play)
            val candidates = rows.filter { it.fileName.equals(name, ignoreCase = true) }
            val row = ContinuePlaying.rowForPlay(play, candidates, { it.consoleId }, { it.fileName }, ConsoleFolderAliases::matches)
            if (row != null) {
                HistoryEvent(HistoryKind.PLAYED, play.lastPlayed ?: 0L, row.consoleId, row.fileName, row.name.ifBlank { name })
            } else {
                // Not in the library (any more): still part of the story, but there are no details to open.
                val title = play.name.ifBlank { GameTitleCleaner.clean(name) }.ifBlank { name }
                HistoryEvent(HistoryKind.PLAYED, play.lastPlayed ?: 0L, play.system, name, title, openable = false)
            }
        }
    }

    /** Whether RomM is set up (its saves then count as "Saved to RomM"). */
    suspend fun rommReady(): Boolean = runCatching {
        rommClient.configuredBaseUrl().isNotEmpty() && settings.rommToken.first().isNotBlank()
    }.getOrDefault(false)

    /** The saves of the "Continue playing" shelf as events; starts the shelf's own refresh. */
    fun saves(): Flow<List<HistoryEvent>> {
        continuePlaying.onShown()
        return continuePlaying.items.map { items ->
            items.map { HistoryEvent(HistoryKind.SAVED, it.at, it.row.file.consoleId, it.row.file.fileName, it.row.file.name.ifBlank { it.row.file.fileName }, it.via) }
        }
    }
}
