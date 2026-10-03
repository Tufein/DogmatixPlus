package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.FileTagEntity
import com.cortinadev.dogmatix.util.SearchNormalizer

@Dao
interface DownloadableFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<DownloadableFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<FileTagEntity>)

    /**
     * Tag filters: each category (regions, languages, …) is OR-ed within itself and AND-ed
     * with the others, e.g. (GBC ∨ GBA) ∧ (ES) ∧ (RETROACHIEVEMENTS). [source] narrows by origin:
     * RomM rows are recognised by their `/api/roms/{id}/content/` download URL (see RommSource).
     */
    @Query("""
        SELECT df.id, df.name, df.fileName, df.consoleId, df.downloadUrl, df.fileSize,
               df.fileExtension, df.torrentFileIndex, df.torrentMagnet, df.expectedHash, df.firstSeenAt,
               GROUP_CONCAT(t.tag, '|') as tags
        FROM downloadable_files df
        LEFT JOIN downloadable_file_tags t ON df.id = t.fileId
        JOIN consoles c ON df.consoleId = c.id
        JOIN manufacturers m ON c.manufacturerId = m.id
        WHERE (:query = '*' OR df.searchKey LIKE '%' || :query || '%')
          AND (:manufacturer IS NULL OR m.name = :manufacturer)
          AND (:consoleIdsCount = 0 OR df.consoleId IN (:consoleIds))
          AND (:regionsCount = 0 OR EXISTS (
                SELECT 1 FROM downloadable_file_tags t_regions WHERE t_regions.fileId = df.id AND t_regions.tag IN (:regions)
          ))
          AND (:languagesCount = 0 OR EXISTS (
                SELECT 1 FROM downloadable_file_tags t_languages WHERE t_languages.fileId = df.id AND t_languages.tag IN (:languages)
          ))
          AND (:videoStandardsCount = 0 OR EXISTS (
                SELECT 1 FROM downloadable_file_tags t_videoStandards WHERE t_videoStandards.fileId = df.id AND t_videoStandards.tag IN (:videoStandards)
          ))
          AND (:contentTypesCount = 0 OR EXISTS (
                SELECT 1 FROM downloadable_file_tags t_contentTypes WHERE t_contentTypes.fileId = df.id AND t_contentTypes.tag IN (:contentTypes)
          ))
          AND (:fileTypesCount = 0 OR EXISTS (
                SELECT 1 FROM downloadable_file_tags t_fileTypes WHERE t_fileTypes.fileId = df.id AND t_fileTypes.tag IN (:fileTypes)
          ))
          AND (:favouritesOnly = 0 OR EXISTS (
                SELECT 1 FROM favourites f WHERE f.consoleId = df.consoleId AND f.fileName = df.fileName
          ))
          AND (:newSince = 0 OR df.firstSeenAt >= :newSince)
          AND (:collectionId = 0 OR EXISTS (
                SELECT 1 FROM collection_items ci WHERE ci.collectionId = :collectionId AND ci.consoleId = df.consoleId AND ci.fileName = df.fileName
          ))
          AND (:source = 0
               OR (:source = 1 AND df.torrentFileIndex IS NOT NULL)
               OR (:source = 2 AND df.downloadUrl LIKE '%/api/roms/%/content/%')
               OR (:source = 3 AND df.torrentFileIndex IS NULL AND df.downloadUrl NOT LIKE '%/api/roms/%/content/%'))
        GROUP BY df.id, df.name, df.fileName, df.consoleId, df.downloadUrl, df.fileSize,
                 df.fileExtension, df.torrentFileIndex, df.torrentMagnet, df.expectedHash, df.firstSeenAt
        ORDER BY
            CASE WHEN :sort = 4 THEN df.firstSeenAt END DESC,
            CASE WHEN :sort = 0 THEN df.name END ASC,
            CASE WHEN :sort = 1 THEN df.name END DESC,
            CASE WHEN :sort = 2 THEN df.fileSize END DESC,
            CASE WHEN :sort = 3 THEN df.fileSize END ASC,
            df.name ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun queryFilesWithTags(
        query: String,
        manufacturer: String?,
        consoleIds: List<String>,
        consoleIdsCount: Int,
        regions: List<String>,
        regionsCount: Int,
        languages: List<String>,
        languagesCount: Int,
        videoStandards: List<String>,
        videoStandardsCount: Int,
        contentTypes: List<String>,
        contentTypesCount: Int,
        fileTypes: List<String>,
        fileTypesCount: Int,
        favouritesOnly: Boolean,
        /** Only files a rescan found at or after this time; 0 = no limit. */
        newSince: Long,
        /** Only games in this collection; 0 = any. */
        collectionId: Long,
        /** [com.cortinadev.dogmatix.data.model.SourceFilter] ordinal: 0 all, 1 torrent, 2 RomM, 3 direct HTTP. */
        source: Int,
        /** [com.cortinadev.dogmatix.data.model.SortOption] ordinal: 0 A→Z, 1 Z→A, 2 biggest first, 3 smallest first, 4 newest first. */
        sort: Int,
        limit: Int = 100,
        offset: Int = 0
    ): List<DownloadableFileWithTagsResult>

    @Query("SELECT COUNT(*) FROM downloadable_files")
    suspend fun getFilesCount(): Int

    /** Indexed games per console, for the library overview. */
    @Query("SELECT consoleId, COUNT(*) AS count FROM downloadable_files GROUP BY consoleId")
    suspend fun countsByConsole(): List<ConsoleFileCount>

    @Query("SELECT fileName FROM downloadable_files WHERE consoleId = :consoleId")
    suspend fun fileNamesFor(consoleId: String): List<String>

    /** The other versions of a game: same console, same cleaned title (see `searchKey`). */
    @Query("SELECT * FROM downloadable_files WHERE consoleId = :consoleId AND searchKey = :searchKey")
    suspend fun versionsOf(consoleId: String, searchKey: String): List<DownloadableFileEntity>

    @Query("SELECT t.tag FROM downloadable_file_tags t WHERE t.fileId = :fileId")
    suspend fun tagsOf(fileId: Long): List<String>

    /** Library rows whose title contains [key] (a [SearchNormalizer] key), optionally of one console. */
    @Query("SELECT COUNT(*) FROM downloadable_files WHERE searchKey LIKE '%' || :key || '%' AND (:consoleId IS NULL OR consoleId = :consoleId)")
    suspend fun countMatching(key: String, consoleId: String?): Int

    @Query("SELECT * FROM downloadable_files WHERE fileName = :fileName LIMIT 1")
    suspend fun getFileByFileName(fileName: String): DownloadableFileEntity?

    @Query("SELECT tag FROM downloadable_file_tags WHERE fileId = :fileId ORDER BY tag ASC")
    suspend fun getTagsForFile(fileId: Long): List<String>

    @Query("""
        SELECT DISTINCT t.tag
        FROM downloadable_file_tags t
        JOIN downloadable_files df ON t.fileId = df.id
        JOIN consoles c ON df.consoleId = c.id
        JOIN manufacturers m ON c.manufacturerId = m.id
        WHERE (:query = '*' OR df.searchKey LIKE '%' || :query || '%')
          AND (:manufacturer IS NULL OR m.name = :manufacturer)
          AND (:consoleIdsCount = 0 OR df.consoleId IN (:consoleIds))
        ORDER BY t.tag ASC
    """)
    suspend fun getAvailableTags(
        query: String,
        manufacturer: String?,
        consoleIds: List<String>,
        consoleIdsCount: Int
    ): List<String>

    @Query("""
        SELECT DISTINCT c.id, c.name, c.manufacturerId, c.urls, COUNT(df.id) as fileCount
        FROM consoles c
        JOIN downloadable_files df ON c.id = df.consoleId
        JOIN manufacturers m ON c.manufacturerId = m.id
        WHERE (:query = '*' OR df.searchKey LIKE '%' || :query || '%')
          AND (:manufacturer IS NULL OR m.name = :manufacturer)
        GROUP BY c.id, c.name, c.manufacturerId, c.urls
        HAVING fileCount > 0
        ORDER BY c.name ASC
    """)
    suspend fun getConsolesWithFiles(
        query: String,
        manufacturer: String?
    ): List<ConsoleWithFileCount>

    @Transaction
    suspend fun insertFilesWithTags(files: List<DownloadableFileEntity>, tags: List<FileTagEntity>) {
        val ids = insertAll(files)
        val tagsWithIds = tags.mapIndexed { index, tag ->
            tag.copy(fileId = ids.getOrNull(index) ?: 0L)
        }
        insertTags(tagsWithIds)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<DownloadableFileEntity>): List<Long>

    /**
     * The files of one source with their tags (`tags[i]` belongs to `files[i]`), written in one
     * transaction: one commit for the whole source instead of one per statement batch, and the
     * library never shows a source's files without their tags.
     */
    @Transaction
    suspend fun insertSource(files: List<DownloadableFileEntity>, tags: List<List<String>>): Int {
        if (files.isEmpty()) return 0
        val ids = insertAll(files)
        val rows = ArrayList<FileTagEntity>(tags.sumOf { it.size })
        ids.forEachIndexed { i, id -> tags.getOrNull(i)?.forEach { rows += FileTagEntity(fileId = id, tag = it) } }
        insertTags(rows)
        return rows.size
    }

    @Query("DELETE FROM downloadable_files")
    suspend fun clearAll()

    @Query("SELECT fileName, firstSeenAt FROM downloadable_files WHERE consoleId = :consoleId AND (sourceUrl = :sourceUrl OR sourceUrl = '')")
    suspend fun seenIn(consoleId: String, sourceUrl: String): List<FileSeen>

    @Query("SELECT COUNT(*) FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = :sourceUrl")
    suspend fun countSource(consoleId: String, sourceUrl: String): Int

    @Query("DELETE FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = :sourceUrl")
    suspend fun deleteSourceFiles(consoleId: String, sourceUrl: String)

    @Query("DELETE FROM downloadable_file_tags WHERE fileId IN (SELECT id FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = :sourceUrl)")
    suspend fun deleteSourceTags(consoleId: String, sourceUrl: String)

    @Transaction
    suspend fun deleteSource(consoleId: String, sourceUrl: String) {
        deleteSourceTags(consoleId, sourceUrl)
        deleteSourceFiles(consoleId, sourceUrl)
    }

    /** Every (console, source) pair that has rows; '' is a row indexed before 2.0. */
    @Query("SELECT DISTINCT consoleId, sourceUrl FROM downloadable_files")
    suspend fun indexedSources(): List<IndexedSource>

    /** How many files rescans found since [since] (widget, notification). */
    @Query("SELECT COUNT(*) FROM downloadable_files WHERE firstSeenAt >= :since AND firstSeenAt > 0")
    suspend fun countNewSince(since: Long): Int

    @Query("SELECT * FROM downloadable_files WHERE firstSeenAt >= :since AND firstSeenAt > 0 ORDER BY firstSeenAt DESC LIMIT :limit")
    suspend fun newestSince(since: Long, limit: Int): List<DownloadableFileEntity>

    /** All rows of one console (Switch updates / DLC). */
    @Query("SELECT * FROM downloadable_files WHERE consoleId = :consoleId")
    suspend fun filesOf(consoleId: String): List<DownloadableFileEntity>

    /**
     * Replaces the rows of one source (and, the first time after the update to 2.0, the console's
     * rows from before sources were remembered) with [files] in one transaction. A file the source
     * listed before keeps when it was first seen; a file it did not list before gets [now] — unless
     * the source had no rows yet, then nothing counts as new (its first scan).
     */
    @Transaction
    suspend fun replaceSource(consoleId: String, sourceUrl: String, files: List<DownloadableFileEntity>, tags: List<List<String>>, now: Long): SourceWrite {
        val seen = HashMap<String, Long>()
        seenIn(consoleId, sourceUrl).forEach { seen[it.fileName] = maxOf(seen[it.fileName] ?: 0L, it.firstSeenAt) }
        val known = seen.isNotEmpty()
        deleteSource(consoleId, sourceUrl)
        deleteSource(consoleId, "")
        val stamped = files.map { it.copy(consoleId = consoleId, sourceUrl = sourceUrl, firstSeenAt = seen[it.fileName] ?: if (known) now else 0L) }
        return SourceWrite(insertSource(stamped, tags), stamped.count { it.firstSeenAt == now })
    }

    @Query("SELECT id, name FROM downloadable_files WHERE searchKey = ''")
    suspend fun getFilesMissingSearchKey(): List<FileIdName>

    @Query("UPDATE downloadable_files SET searchKey = :searchKey WHERE id = :id")
    suspend fun updateSearchKey(id: Long, searchKey: String)

    /** Fills [DownloadableFileEntity.searchKey] for rows indexed before the column existed. */
    @Transaction
    suspend fun backfillSearchKeys() {
        getFilesMissingSearchKey().forEach { updateSearchKey(it.id, SearchNormalizer.key(it.name)) }
    }

    @Query("DELETE FROM downloadable_file_tags WHERE fileId IN (SELECT id FROM downloadable_files WHERE consoleId = :consoleId)")
    suspend fun deleteTagsByConsoleId(consoleId: String)

    @Query("DELETE FROM downloadable_files WHERE consoleId = :consoleId")
    suspend fun deleteFilesByConsoleIdInternal(consoleId: String)

    @Transaction
    suspend fun deleteFilesByConsoleId(consoleId: String) {
        deleteTagsByConsoleId(consoleId)
        deleteFilesByConsoleIdInternal(consoleId)
    }
}

data class DownloadableFileWithTagsResult(
    val id: Long,
    val name: String,
    val fileName: String,
    val consoleId: String,
    val downloadUrl: String,
    val fileSize: Long,
    val fileExtension: String,
    val torrentFileIndex: Int?,
    val torrentMagnet: String?,
    val expectedHash: String?,
    val firstSeenAt: Long,
    val tags: String?
)

data class ConsoleWithFileCount(
    val id: String,
    val name: String,
    val manufacturerId: String,
    val urls: String,
    val fileCount: Int
)

data class FileIdName(val id: Long, val name: String)

/** What [DownloadableFileDao.replaceSource] wrote: tag rows, and files the source did not list before. */
data class SourceWrite(val tags: Int, val newFiles: Int)

data class FileSeen(val fileName: String, val firstSeenAt: Long)

data class IndexedSource(val consoleId: String, val sourceUrl: String)

data class ConsoleFileCount(val consoleId: String, val count: Int)
