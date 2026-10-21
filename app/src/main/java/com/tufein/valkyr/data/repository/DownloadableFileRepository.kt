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
        val results = PerfLog.timed("db.search") { dao.queryFilesWithTags(
            query = searchPattern(query),
            manufacturer = manufacturer,
            consoleIds = consoleIds.toList(),
            consoleIdsCount = consoleIds.size,
            regions = kind(TagKind.REGION),
            regionsCount = kind(TagKind.REGION).size,
            languages = kind(TagKind.LANGUAGE),
            languagesCount = kind(TagKind.LANGUAGE).size,
            videoStandards = kind(TagKind.VIDEO_STANDARD),
            videoStandardsCount = kind(TagKind.VIDEO_STANDARD).size,
            contentTypes = kind(TagKind.CONTENT_TYPE),
            contentTypesCount = kind(TagKind.CONTENT_TYPE).size,
            fileTypes = kind(TagKind.FILE_TYPE),
            fileTypesCount = kind(TagKind.FILE_TYPE).size,
            favouritesOnly = favouritesOnly,
            newSince = newSince,
            collectionId = collectionId,
            hiddenConsoles = hidden.hiddenConsoles.toList(),
            hiddenConsolesCount = hidden.hiddenConsoles.size,
            hiddenTags = hidden.hiddenTags.toList(),
            hiddenTagsCount = hidden.hiddenTags.size,
            source = source.ordinal,
            sort = sort.ordinal,
            limit = limit,
            offset = offset
        ) }
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

    suspend fun backfillSearchKeys() = dao.backfillSearchKeys()

    /** '*' means "no text filter" in the DAO queries; anything else is a lenient LIKE pattern. */
    private fun searchPattern(query: String): String =
        SearchNormalizer.likePattern(query).ifEmpty { "*" }

    suspend fun getAvailableTags(
        query: String,
        manufacturer: String? = null,
        consoleIds: Set<String> = emptySet()
    ): List<String> =
        PerfLog.timed("db.tags") { dao.getAvailableTags(searchPattern(query), manufacturer, consoleIds.toList(), consoleIds.size) }

    suspend fun getCategorizedTags(
        query: String,
        manufacturer: String? = null,
        consoleIds: Set<String> = emptySet()
    ): CategorizedTags =
        TagCategorizer.categorizeTags(
            PerfLog.timed("db.tags") { dao.getAvailableTags(searchPattern(query), manufacturer, consoleIds.toList(), consoleIds.size) }
        )

    suspend fun getConsolesWithFiles(query: String, manufacturer: String? = null): List<ConsoleWithFileCount> {
        val hidden = profiles.current().hiddenConsoles
        return PerfLog.timed("db.consoles") { dao.getConsolesWithFiles(searchPattern(query), manufacturer) }.filterNot { it.id in hidden }
    }

    private companion object {
        /** Names per IN (...) query, well below SQLite's 999 variables. */
        const val LOOKUP_CHUNK = 400
    }
}
