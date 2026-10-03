package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import dagger.hilt.android.qualifiers.ApplicationContext
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.FileTagEntity
import com.cortinadev.dogmatix.data.model.Manufacturer
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.HttpHeadersUtils
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.ScrapingConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseScrapingService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadableFileDao: DownloadableFileDao,
    private val torrentScrapingService: TorrentScrapingService,
    private val rommScrapingService: RommScrapingService
) {

    /** Downloads the listing; parsing happens afterwards, outside the per-host slot. */
    private suspend fun makeRequest(url: String): org.jsoup.Connection.Response {
        var lastException: Exception? = null
        repeat(ScrapingConstants.MAX_RETRIES) { attempt ->
            try {
                if (attempt > 0) delay(ScrapingConstants.RETRY_DELAY_MS * attempt)
                val connection = Jsoup.connect(url)
                    .userAgent(ScrapingConstants.USER_AGENT)
                    .timeout(ScrapingConstants.CONNECTION_TIMEOUT_MS.toInt())
                HttpHeadersUtils.configureBrowserHeaders(connection)
                return connection.execute().also { it.bufferUp() }
            } catch (e: Exception) {
                lastException = e
                println("Request attempt ${attempt + 1} failed for $url: ${e.message}")
                if (attempt < ScrapingConstants.MAX_RETRIES - 1) delay(ScrapingConstants.RETRY_DELAY_MS)
            }
        }
        throw lastException ?: Exception("All retry attempts failed")
    }

    suspend fun scrapeAndInsertToDatabase(
        baseUrl: String,
        consoleId: String,
        contentType: com.cortinadev.dogmatix.data.model.ContentType = com.cortinadev.dogmatix.data.model.ContentType.GAME,
        visitedUrls: MutableSet<String> = mutableSetOf(),
        rootUrl: String = baseUrl
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (visitedUrls.contains(baseUrl)) return@withContext Pair(0, 0)
        if (!baseUrl.startsWith(rootUrl)) return@withContext Pair(0, 0)
        visitedUrls.add(baseUrl)

        // A few listings of one server at a time, with a short pause: polite, but no longer a
        // fixed second per request (and none at all for sub-folders, which are not read).
        val t0 = System.currentTimeMillis()
        val response = hostLimit(baseUrl).withPermit {
            delay(ScrapingConstants.REQUEST_DELAY_MS)
            makeRequest(baseUrl)
        }
        val t1 = System.currentTimeMillis()
        val doc = response.parse()
        val table = doc.select(ScrapingConstants.TABLE_SELECTOR).first()
            ?: throw Exception("Could not find file table at $baseUrl. The source might be down (like Myrient) or its structure has changed.")

        val contentTypeTag = FileParsingUtils.normalizeTag(contentType.name)
        val files = ArrayList<DownloadableFileEntity>()
        val tags = ArrayList<List<String>>()
        for (row in table.getElementsByTag("tr")) {
            val linkCell = FileParsingUtils.linkOf(row) ?: continue
            val href = linkCell.attr("href")
            val linkText = linkCell.text().trim()

            if (href == ScrapingConstants.PARENT_DIRECTORY ||
                href == ScrapingConstants.CURRENT_DIRECTORY ||
                linkText == "Parent directory/" ||
                linkText == "./" || linkText == "../") continue
            // Sub-folders are not walked into.
            if (href.endsWith("/")) continue

            val (fileEntity, tagEntities) = FileParsingUtils.parseFileFromRow(row, baseUrl, consoleId, linkCell)
            if (fileEntity != null) {
                files += fileEntity
                tags += (tagEntities.map { it.tag } + contentTypeTag)
            }
        }

        val t2 = System.currentTimeMillis()
        val tagCount = downloadableFileDao.insertSource(files, tags)
        android.util.Log.i("DatabaseScrapingService", "Indexed ${files.size} files from $baseUrl: fetch ${t1 - t0} ms, parse ${t2 - t1} ms, store ${System.currentTimeMillis() - t2} ms")
        Pair(files.size, tagCount)
    }

    /** Listings of one host at a time are capped, whatever the number of its sources. */
    private val hostLimits = ConcurrentHashMap<String, Semaphore>()
    private fun hostLimit(url: String): Semaphore =
        hostLimits.getOrPut(runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")) {
            Semaphore(ScrapingConstants.PARALLEL_PER_HOST)
        }

    /** Sources of one kind scanned at the same time: torrents wait on the swarm, HTTP on the server. */
    private val httpLimit = Semaphore(ScrapingConstants.PARALLEL_HTTP)
    private val torrentLimit = Semaphore(ScrapingConstants.PARALLEL_TORRENTS)
    private val rommLimit = Semaphore(ScrapingConstants.PARALLEL_ROMM)

    /**
     * Routes each URL entry to the right scraper:
     *   TORRENT → TorrentScrapingService
     *   romm:// → RommScrapingService (a platform of the RomM server set in Settings)
     *   HTTP    → scrapeAndInsertToDatabase
     *
     * The sources run side by side, a few of each kind at a time (see [ScrapingConstants]), so a
     * slow torrent no longer holds up the web directories behind it.
     *
     * [onScrapeError] is invoked on [Dispatchers.IO]. Implementations must be thread-safe
     * (e.g. updating a [kotlinx.coroutines.flow.MutableStateFlow] is fine; touching UI is not).
     * [onSourceDone] runs after every enabled source, failed or not (scan progress).
     */
    suspend fun scrapeManufacturer(
        manufacturer: Manufacturer,
        onScrapeError: (String) -> Unit = {},
        onSourceDone: () -> Unit = {}
    ): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            val totalFiles = AtomicInteger()
            val totalTags = AtomicInteger()
            coroutineScope {
                manufacturer.consoles.forEach { console ->
                    console.urls.filter { it.enabled }.forEach { urlEntry ->
                        launch {
                            val isTorrent = urlEntry.url.startsWith("magnet:") || urlEntry.url.endsWith(".torrent")
                            val isRomm = RommSource.isSource(urlEntry.url)
                            val limit = when { isTorrent -> torrentLimit; isRomm -> rommLimit; else -> httpLimit }
                            try {
                                val (files, tags) = limit.withPermit {
                                    when {
                                        isTorrent -> torrentScrapingService.scrapeAndInsert(urlEntry, console)
                                        isRomm -> rommScrapingService.scrapeAndInsert(urlEntry, console)
                                        else -> scrapeAndInsertToDatabase(urlEntry.url, console.id, urlEntry.contentType)
                                    }
                                }
                                totalFiles.addAndGet(files)
                                totalTags.addAndGet(tags)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                onScrapeError(context.getString(R.string.scrape_failed, console.name, e.message ?: ""))
                            } finally {
                                onSourceDone()
                            }
                        }
                    }
                }
            }
            Pair(totalFiles.get(), totalTags.get())
        }

    suspend fun clearAllData() = downloadableFileDao.clearAll()

    suspend fun clearConsoleData(consoleId: String) = downloadableFileDao.deleteFilesByConsoleId(consoleId)
}
