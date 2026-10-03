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
import com.cortinadev.dogmatix.util.RommSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommScrapingService"

/**
 * Indexes a `romm://<slug>` source: every ROM RomM lists for that platform becomes a library
 * row whose download URL is the server's `/api/roms/{id}/content/…` endpoint.
 */
@Singleton
class RommScrapingService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val rommClient: RommClient,
    private val downloadableFileDao: DownloadableFileDao,
    private val rescanStateHolder: RescanStateHolder
) {
    suspend fun scrapeAndInsert(urlEntry: UrlEntry, console: Console): SourceIndexResult = withContext(Dispatchers.IO) {
        val slug = RommSource.slugOf(urlEntry.url) ?: throw Exception("Invalid RomM source '${urlEntry.url}' (expected romm://<platform>)")
        val base = rommClient.configuredBaseUrl().ifEmpty { throw Exception("RomM server not configured (Settings → RomM)") }
        rescanStateHolder.setTorrentFetchProgress(context.getString(R.string.scrape_listing_romm, slug))
        try {
            val platform = rommClient.platforms().firstOrNull { it.slug.equals(slug, true) || it.fsSlug.equals(slug, true) || it.id.toString() == slug }
                ?: throw Exception("RomM has no platform '$slug'")
            val roms = rommClient.roms(platform.id)
            if (roms.isEmpty()) {
                Log.i(TAG, "No ROMs on RomM for $slug")
                downloadableFileDao.replaceSource(console.id, urlEntry.url, emptyList(), emptyList(), System.currentTimeMillis())
                return@withContext SourceIndexResult(0, 0)
            }
            val files = ArrayList<DownloadableFileEntity>(roms.size)
            val tags = ArrayList<List<String>>(roms.size)
            val contentTypeTag = FileParsingUtils.normalizeTag(urlEntry.contentType.name)
            roms.forEach { rom ->
                val (cleanName, tagStrings) = FileParsingUtils.extractNameAndTags(rom.fsName.substringBeforeLast('.', rom.fsName))
                files += DownloadableFileEntity(
                    name = cleanName.ifBlank { rom.name.ifBlank { rom.fsName } },
                    fileName = rom.fsName,
                    consoleId = console.id,
                    downloadUrl = RommSource.downloadUrl(base, rom.id, rom.fsName),
                    fileSize = rom.fsSizeBytes,
                    fileExtension = rom.fsName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" },
                    expectedHash = rom.hash
                )
                tags += (tagStrings + contentTypeTag).distinct()
            }
            val write = downloadableFileDao.replaceSource(console.id, urlEntry.url, files, tags, System.currentTimeMillis())
            Log.i(TAG, "Indexed ${files.size} ROM(s) (${write.newFiles} new) from RomM platform $slug")
            SourceIndexResult(files.size, write.tags, write.newFiles)
        } finally {
            rescanStateHolder.setTorrentFetchProgress("")
        }
    }
}
