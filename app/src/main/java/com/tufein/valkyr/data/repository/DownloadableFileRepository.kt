package com.tufein.valkyr.data.repository

import com.tufein.valkyr.data.local.dao.ConsoleWithFileCount
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.dao.DownloadableFileRef
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.model.CategorizedTags
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.data.model.TagCategorizer
import com.tufein.valkyr.data.model.SortOption
import com.tufein.valkyr.data.model.SourceFilter
import com.tufein.valkyr.data.model.TagKind
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.util.SearchNormalizer
import android.util.Log
import com.tufein.valkyr.data.local.dao.toSupportQuery
import com.tufein.valkyr.data.local.queries.LibraryQueryBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import com.tufein.valkyr.util.PerfLog
import javax.inject.Singleton

@Singleton
class DownloadableFileRepository @Inject constructor(
    private val dao: DownloadableFileDao,
    private val profiles: ProfileService
) {
    suspend fun searchFilesWithTags(
        query: String,
        manufacturer: String? = null,
        consoleIds: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        favouritesOnly: Boolean = false,
        /** Only files a rescan found at or after this time; 0 = all. */
        newSince: Long = 0L,
        /** Only games in this collection; 0 = all. */
        collectionId: Long = 0L,
        source: SourceFilter = SourceFilter.ALL,
        sort: SortOption = SortOption.NAME_ASC,
        limit: Int = 100,
        offset: Int = 0
    ): List<DownloadableFileWithTags> {
        // Same-kind tags are OR-ed, different kinds are AND-ed (see the DAO query).
        val byKind = TagCategorizer.groupByKind(tags)
        val hidden = profiles.current()
        fun kind(k: TagKind) = byKind[k].orEmpty().toList()
        val results = PerfLog.timed("db.search") {
            withText(query) { text ->
                dao.queryFilesWithTags(LibraryQueryBuilder.files(LibraryQueryBuilder.FileFilter(
                    text = text,
                    manufacturer = manufacturer,
                    consoleIds = consoleIds.toList(),
                    regions = kind(TagKind.REGION),
                    languages = kind(TagKind.LANGUAGE),
                    videoStandards = kind(TagKind.VIDEO_STANDARD),
                    contentTypes = kind(TagKind.CONTENT_TYPE),
                    fileTypes = kind(TagKind.FILE_TYPE),
                    favouritesOnly = favouritesOnly,
                    newSince = newSince,
                    collectionId = collectionId,
                    hiddenConsoles = hidden.hiddenConsoles.toList(),
                    hiddenTags = hidden.hiddenTags.toList(),
                    source = source.ordinal,
                    sort = sort.ordinal,
                    limit = limit,
                    offset = offset
                )).toSupportQuery())
            }
        }
        return results.map { result ->
            DownloadableFileWithTags(
                file = DownloadableFileEntity(
                    id = result.id,
                    name = result.name,
                    fileName = result.fileName,
                    consoleId = result.consoleId,
                    downloadUrl = result.downloadUrl,
                    fileSize = result.fileSize,
                    fileExtension = result.fileExtension,
                    torrentFileIndex = result.torrentFileIndex,
                    torrentMagnet = result.torrentMagnet,
                    expectedHash = result.expectedHash,
                    firstSeenAt = result.firstSeenAt
                ),
                tags = result.tags?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            )
        }
    }

    /** The indexed file (with its tags) behind a download, or null if it was re-indexed away. */
    suspend fun findByFileName(fileName: String): DownloadableFileWithTags? {
        val file = dao.getFileByFileName(fileName) ?: return null
        return DownloadableFileWithTags(file = file, tags = dao.getTagsForFile(file.id))
    }

    /** All exact source rows matter when enforcing restrictions for a shared game journal. */
    suspend fun exactMatches(consoleId: String, fileName: String): List<DownloadableFileWithTags> {
        val files = dao.filesByFileNames(listOf(fileName)).filter { it.consoleId == consoleId && it.fileName == fileName }
        val tags = files.map { it.id }.chunked(LOOKUP_CHUNK).flatMap { dao.tagsOfFiles(it) }.groupBy({ it.fileId }, { it.tag })
        return files.map { DownloadableFileWithTags(it, tags[it.id].orEmpty()) }
    }

    /** Preserve all source rows and tags while matching many packages with bounded SQL batches. */
    suspend fun exactMatchesBatch(keys: Collection<Pair<String, String>>): Map<Pair<String, String>, List<DownloadableFileWithTags>> {
        val wanted = keys.toHashSet()
        val rows = wanted.map { it.second }.distinct().chunked(LOOKUP_CHUNK).flatMap { names ->
            dao.filesByFileNames(names).filter { (it.consoleId to it.fileName) in wanted }
        }
        val tags = rows.map { it.id }.chunked(LOOKUP_CHUNK).flatMap { dao.tagsOfFiles(it) }.groupBy({ it.fileId }, { it.tag })
        return rows.map { DownloadableFileWithTags(it, tags[it.id].orEmpty()) }.groupBy { it.file.consoleId to it.file.fileName }
    }

    /**
     * [findByFileName] for many downloads: two queries per [LOOKUP_CHUNK] names instead of two per
     * name (each of those scanned the whole library table). When two consoles list the same file
     * name, [preferConsole] picks the row.
     */
    suspend fun findByFileNames(
        names: Collection<String>,
        preferConsole: (String) -> String? = { null }
    ): Map<String, DownloadableFileWithTags> {
        val found = HashMap<String, DownloadableFileWithTags>()
        for (chunk in names.distinct().chunked(LOOKUP_CHUNK)) {
            val files = dao.filesByFileNames(chunk).groupBy { it.fileName }.mapValues { (name, rows) ->
                val console = preferConsole(name)
                rows.firstOrNull { it.consoleId == console } ?: rows.first()
            }
            val tags = files.values.map { it.id }.chunked(LOOKUP_CHUNK).flatMap { dao.tagsOfFiles(it) }
                .groupBy({ it.fileId }, { it.tag })
            files.forEach { (name, file) -> found[name] = DownloadableFileWithTags(file = file, tags = tags[file.id].orEmpty()) }
        }
        return found
    }

    /** Every version of a game the library lists for its console (same cleaned title), with tags. */
    suspend fun versionsOf(file: DownloadableFileEntity): List<DownloadableFileWithTags> {
        val key = file.searchKey.ifEmpty { SearchNormalizer.key(file.name) }
        val rows = PerfLog.timed("db.versions") { dao.versionsOf(file.consoleId, key) }
        if (rows.isEmpty()) return emptyList()
        // One query for all tags instead of one per version.
        val tags = dao.tagsOfFiles(rows.map { it.id }).groupBy({ it.fileId }, { it.tag })
        return rows.map { DownloadableFileWithTags(it, tags[it.id].orEmpty()) }
    }

    suspend fun clearAll() = dao.clearAll()

    /** Every row of one console (Switch updates / DLC). */
    suspend fun filesOf(consoleId: String): List<DownloadableFileEntity> = PerfLog.timed("db.console_rows") { dao.filesOf(consoleId) }

    /** Id and names of every row of one console: for callers that do not need the URLs and torrent fields. */
    suspend fun fileRefsOf(consoleId: String): List<DownloadableFileRef> = PerfLog.timed("db.console_refs") { dao.fileRefsOf(consoleId) }

    suspend fun filesByIds(ids: List<Long>): List<DownloadableFileEntity> =
        ids.chunked(400).flatMap { dao.filesByIds(it) }

    /**
     * Start-up repair of old rows: the search key (2.x rows) and, since 4.6, the words behind the FTS index.
     * Searches answer from the LIKE scan until the last row has its words; a failure here only leaves
     * that scan in place (the next start tries again).
     */
    suspend fun backfillSearchKeys() {
        dao.backfillSearchKeys()
        var after = 0L
        while (true) {
            after = dao.backfillSearchWordsPage(after, WORDS_PAGE) ?: break
            yield()
        }
        ftsState.set(if (dao.searchWordsComplete()) FTS_READY else FTS_PENDING)
    }

    // --- 4.6 full-text search -------------------------------------------------------------------------

    private val ftsState = AtomicInteger(FTS_UNKNOWN)
    private val ftsRepairStarted = AtomicBoolean(false)
    private val repairScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun ftsReady(): Boolean {
        if (ftsState.get() == FTS_UNKNOWN) {
            // First search of the process: complete when no row still waits for its words (the start-up backfill sets it too).
            val complete = try { dao.searchWordsComplete() } catch (e: Exception) { if (e is CancellationException) throw e; false }
            ftsState.compareAndSet(FTS_UNKNOWN, if (complete) FTS_READY else FTS_PENDING)
        }
        return ftsState.get() == FTS_READY
    }

    /**
     * Runs [block] with the text filter for [query]: FTS when the index is complete, the LIKE scan otherwise.
     * If an FTS query throws (a damaged index), the same query is answered by the LIKE scan, FTS stays off
     * for this process, and the index is rebuilt once in the background.
     */
    private suspend fun <T> withText(query: String, block: suspend (LibraryQueryBuilder.Text) -> T): T {
        val text = LibraryQueryBuilder.text(query, ftsReady())
        if (text !is LibraryQueryBuilder.Text.Fts) return block(text)
        return try {
            block(text)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Full-text search failed, using the plain scan: ${e.javaClass.simpleName}")
            ftsState.set(FTS_PENDING)
            repairFts()
            block(LibraryQueryBuilder.text(query, false))
        }
    }

    private fun repairFts() {
        if (!ftsRepairStarted.compareAndSet(false, true)) return
        repairScope.launch {
            try {
                dao.rebuildFts()
                ftsState.set(FTS_READY)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Full-text index rebuild failed: ${e.javaClass.simpleName}")
            }
        }
    }

    suspend fun getAvailableTags(
        query: String,
        manufacturer: String? = null,
        consoleIds: Set<String> = emptySet()
    ): List<String> =
        PerfLog.timed("db.tags") { tagsFor(query, manufacturer, consoleIds) }

    private suspend fun tagsFor(query: String, manufacturer: String?, consoleIds: Set<String>): List<String> =
        withText(query) { text -> dao.rawTags(LibraryQueryBuilder.tags(text, manufacturer, consoleIds.toList()).toSupportQuery()) }

    suspend fun getCategorizedTags(
        query: String,
        manufacturer: String? = null,
        consoleIds: Set<String> = emptySet()
    ): CategorizedTags =
        TagCategorizer.categorizeTags(PerfLog.timed("db.tags") { tagsFor(query, manufacturer, consoleIds) })

    suspend fun getConsolesWithFiles(query: String, manufacturer: String? = null): List<ConsoleWithFileCount> {
        val hidden = profiles.current().hiddenConsoles
        return PerfLog.timed("db.consoles") {
            withText(query) { text -> dao.rawConsolesWithFiles(LibraryQueryBuilder.consoles(text, manufacturer).toSupportQuery()) }
        }.filterNot { it.id in hidden }
    }

    private companion object {
        /** Names per IN (...) query, well below SQLite's 999 variables. */
        const val LOOKUP_CHUNK = 400
        /** Rows per transaction while filling the words of old rows (each also updates the FTS index). */
        const val WORDS_PAGE = 1000
        const val TAG = "DownloadableFileRepo"
        const val FTS_UNKNOWN = 0
        const val FTS_READY = 1
        /** Rows still wait for their words, or the index failed: searches use the LIKE scan. */
        const val FTS_PENDING = 2
    }
}
