package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.local.SourcePickSettings
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.util.FailureClass
import com.cortinadev.dogmatix.util.SourceRanking
import com.cortinadev.dogmatix.util.SourceRecord
import com.cortinadev.dogmatix.util.SourcesJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SourceTrack"

/**
 * 7.5: the track record of every source (speed, how many downloads worked or failed and why), kept
 * in `files/source_track.json`, and the choice of the best copy when several sources list a game
 * (see [SourceRanking]). The download pipeline reports each finished, failed or retried download here.
 */
@Singleton
class SourceTrackService @Inject constructor(
    @param:ApplicationContext context: Context,
    private val fileDao: DownloadableFileDao,
    private val consoleDao: ConsoleDao,
    private val settings: SourcePickSettings
) {
    private val file = File(context.filesDir, "source_track.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _records = MutableStateFlow<Map<String, SourceRecord>>(emptyMap())
    /** Track record per source, by its address as configured in Sources. */
    val records: StateFlow<Map<String, SourceRecord>> = _records.asStateFlow()

    /** Whether "Pick the best source" is on. */
    val enabled get() = settings.pickBest

    init {
        scope.launch {
            val stored = runCatching { if (file.exists()) SourceRanking.fromJson(file.readText()) else emptyMap() }.getOrDefault(emptyMap())
            // Anything recorded before the file was read stays on top of it.
            _records.update { stored + it }
        }
    }

    /** A download of [entity] finished; [bytes] moved in [millis] (0 when unknown, e.g. a torrent). */
    fun recordSuccess(entity: DownloadableFileEntity, bytes: Long, millis: Long) = record(entity) { r, now ->
        SourceRanking.withSuccess(r, bytes, millis, now)
    }

    /** A download (or one of its automatic retries) of [entity] failed with [error] (null = no detail). */
    fun recordFailure(entity: DownloadableFileEntity, error: Throwable?) = record(entity) { r, now ->
        val code = (error as? HttpStatusException)?.code
        SourceRanking.withFailure(r, FailureClass.of(code, error is IOException), now)
    }

    private fun record(entity: DownloadableFileEntity, change: (SourceRecord, Long) -> SourceRecord) {
        scope.launch {
            val source = runCatching { sourceOf(entity) }.getOrNull() ?: return@launch
            _records.update { it + (source to change(it[source] ?: SourceRecord(), System.currentTimeMillis())) }
            save()
        }
    }

    @Synchronized
    private fun save() {
        runCatching { file.writeText(SourceRanking.toJson(_records.value)) }
            .onFailure { Log.w(TAG, "Could not save the source track record: ${it.message}") }
    }

    /**
     * The source [entity] came from. Downloads restored from the history of an earlier run carry
     * no source: it is looked up by the download address.
     */
    suspend fun sourceOf(entity: DownloadableFileEntity): String? =
        entity.sourceUrl.ifEmpty {
            fileDao.filesByFileNames(listOf(entity.fileName))
                .firstOrNull { it.consoleId == entity.consoleId && it.downloadUrl == entity.downloadUrl }?.sourceUrl.orEmpty()
        }.ifEmpty { null }

    /**
     * Copies of [entity] in the enabled sources of its console (itself included), best first. With
     * [sameNameOnly] only copies under the same file name count (the Downloads list knows a
     * download by its file name); otherwise also the same cleaned title with the same tags.
     */
    suspend fun ranked(entity: DownloadableFileEntity, sameNameOnly: Boolean = true): List<DownloadableFileEntity> {
        val order = sourceOrder(entity.consoleId)
        val self = entity.copy(sourceUrl = sourceOf(entity).orEmpty())
        val rows = fileDao.filesByFileNames(listOf(entity.fileName)).filter { it.consoleId == entity.consoleId } +
            (if (sameNameOnly) emptyList() else fileDao.versionsOf(entity.consoleId, entity.searchKey))
        val others = rows.filter { it.downloadUrl != self.downloadUrl && it.sourceUrl in order }.distinctBy { it.downloadUrl }
        if (others.isEmpty()) return listOf(self)
        val tags = if (sameNameOnly) emptyMap() else
            fileDao.tagsOfFiles((others.map { it.id } + self.id).distinct()).groupBy({ it.fileId }, { it.tag })
        fun copyOf(f: DownloadableFileEntity) = SourceRanking.Copy(f.consoleId, f.fileName, f.name, tags[f.id].orEmpty(), f.fileSize)
        val target = copyOf(self)
        val same = others.filter { SourceRanking.sameGame(target, copyOf(it)) }
        return SourceRanking.rank(listOf(self) + same, { it.sourceUrl }, _records.value, order, System.currentTimeMillis())
    }

    /** The best copy of [entity] when "Pick the best source" is on; [entity] itself otherwise or on any trouble. */
    suspend fun pickBest(entity: DownloadableFileEntity, sameNameOnly: Boolean = true): DownloadableFileEntity {
        if (!settings.pickBest.first()) return entity
        return runCatching { ranked(entity, sameNameOnly).firstOrNull() }
            .onFailure { Log.w(TAG, "Could not rank the sources of ${entity.fileName}: ${it.message}") }
            .getOrNull() ?: entity
    }

    /**
     * After [entity] failed for good: the best copy of it in another source (same file name), or
     * null when there is none or "Pick the best source" is off.
     */
    suspend fun nextBest(entity: DownloadableFileEntity): DownloadableFileEntity? {
        if (!settings.pickBest.first()) return null
        return runCatching { ranked(entity, sameNameOnly = true).firstOrNull { it.downloadUrl != entity.downloadUrl } }.getOrNull()
    }

    /** The enabled sources of [consoleId] in the order of Sources. */
    suspend fun sourceOrder(consoleId: String): List<String> =
        consoleDao.getConsoleById(consoleId)?.urls?.let { SourcesJson.parseUrlEntries(it) }?.filter { it.enabled }?.map { it.url }.orEmpty()
}
