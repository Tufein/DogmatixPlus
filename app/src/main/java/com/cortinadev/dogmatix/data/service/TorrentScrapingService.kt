package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.R
import dagger.hilt.android.qualifiers.ApplicationContext
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.FileTagEntity
import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.FileParsingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Torrent equivalent of [DatabaseScrapingService].
 *
 * 1. Calls [TorrentMetadataFetcher] to get (or reuse) the TorrentInfo.
 * 2. Calls [TorrentFileIndexer] to list all files, filtered by [UrlEntry.folders] if specified.
 * 3. Maps each file to a [DownloadableFileEntity] with torrentFileIndex +
 *    torrentMagnet filled in, then batch-inserts — same pattern as HTTP scraper.
 * 4. Runs [FileParsingUtils.extractNameAndTags] on each file name so region /
 *    language / version tags work the same as for HTTP sources.
 */
@Singleton
class TorrentScrapingService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val metadataFetcher: TorrentMetadataFetcher,
    private val fileIndexer: TorrentFileIndexer,
    private val downloadableFileDao: DownloadableFileDao,
    private val rescanStateHolder: RescanStateHolder,
    private val registry: TorrentHandleRegistry
) {

    suspend fun scrapeAndInsert(urlEntry: UrlEntry, console: Console): SourceIndexResult =
        withContext(Dispatchers.IO) {
            val magnet = urlEntry.url

            rescanStateHolder.setTorrentFetchProgress(context.getString(R.string.scrape_fetching_torrent, console.name))

            val info = try {
                metadataFetcher.fetch(magnet)
            } catch (e: Exception) {
                rescanStateHolder.setTorrentFetchProgress("")
                throw e
            }

            rescanStateHolder.setTorrentFetchProgress(context.getString(R.string.scrape_indexing_torrent, console.name))

            val entries = fileIndexer.index(info, magnet, urlEntry.folders)
            if (entries.isEmpty()) {
                Log.w(TAG, "No files found in torrent")
                rescanStateHolder.setTorrentFetchProgress("")
                downloadableFileDao.replaceSource(console.id, magnet, emptyList(), emptyList(), System.currentTimeMillis())
                return@withContext SourceIndexResult(0, 0)
            }

            val allFiles = ArrayList<DownloadableFileEntity>(entries.size)
            val allTags = ArrayList<List<String>>(entries.size)
            val contentTypeTag = FileParsingUtils.normalizeTag(urlEntry.contentType.name)

            entries.forEach { entry ->
                val (cleanName, tagStrings) = FileParsingUtils.extractNameAndTags(entry.fileName)
                val entity = DownloadableFileEntity(
                    id = 0,
                    name = cleanName,
                    fileName = entry.fileName,
                    consoleId = console.id,
                    downloadUrl = magnet,
                    fileSize = entry.fileSize,
                    fileExtension = entry.fileName.substringAfterLast('.', ""),
                    torrentFileIndex = entry.fileIndex,
                    torrentMagnet = magnet
                )
                allFiles.add(entity)
                allTags.add(tagStrings + contentTypeTag)
            }

            // One transaction; tags follow their file by position (two files of the same name in
            // different torrent folders used to share one id).
            val write = downloadableFileDao.replaceSource(console.id, magnet, allFiles, allTags, System.currentTimeMillis())
            val tagCount = write.tags
            rescanStateHolder.setTorrentFetchProgress("")

            Log.i(TAG, "Inserted ${allFiles.size} files, $tagCount tags for ${console.name}")
            
            // NOTE: We no longer release the handle here.
            // Keeping it in the session allows immediate starting of downloads.

            SourceIndexResult(allFiles.size, tagCount, write.newFiles)
        }

    companion object { private const val TAG = "TorrentScrapingService" }
}
