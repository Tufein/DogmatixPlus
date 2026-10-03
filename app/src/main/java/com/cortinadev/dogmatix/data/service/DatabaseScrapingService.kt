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
import com.cortinadev.dogmatix.util.FailureKind
import com.cortinadev.dogmatix.util.HostGate
import com.cortinadev.dogmatix.util.NoFileTableException
import com.cortinadev.dogmatix.util.ScanFailure
import com.cortinadev.dogmatix.util.ScanFailures
import com.cortinadev.dogmatix.util.ScrapeHttpException
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

    /** Per server, for the scan that is running (see [resetForScan]). */
    private val hostGates = ConcurrentHashMap<String, HostGate>()

    /** A new scan starts with every server at full speed again. */
    fun resetForScan() = hostGates.clear()

    private fun hostGate(url: String): HostGate =
        hostGates.getOrPut(runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")) {
            HostGate(ScrapingConstants.PARALLEL_PER_HOST, ScrapingConstants.REQUEST_DELAY_MS, ScrapingConstants.SLOW_REQUEST_DELAY_MS)
        }

    /**
     * Downloads a listing (parsing happens afterwards, outside the per-server slot). A server that
     * asks to slow down (429), is overloaded (5xx) or does not answer in time is tried again with a
     * growing pause — what its `Retry-After` asks, else 3, 6, 12, 24 s — and from then on gets one
     * request at a time. 404 / 403 are final.
     */
    private suspend fun makeRequest(url: String): org.jsoup.Connection.Response {
        val gate = hostGate(url)
        var attempt = 0
        while (true) {
            try {
                val response = gate.run {
                    val connection = Jsoup.connect(url)
                        .userAgent(ScrapingConstants.USER_AGENT)
                        .timeout(ScrapingConstants.CONNECTION_TIMEOUT_MS.toInt())
                    HttpHeadersUtils.configureBrowserHeaders(connection)
                    connection.ignoreHttpErrors(true).execute().also { it.bufferUp() }
                }
                val code = response.statusCode()
                if (code in 200..299) return response
                throw ScrapeHttpException(code, ScanFailures.parseRetryAfter(response.header("Retry-After")), url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                val kind = ScanFailures.kindOf(e)
                if (!ScanFailures.isRetryable(kind) || attempt >= ScrapingConstants.MAX_ATTEMPTS) throw e
                val wait = ScanFailures.backoffMillis(attempt, (e as? ScrapeHttpException)?.retryAfterSeconds)
                android.util.Log.w("DatabaseScrapingService", "$url: ${e.message} ($kind), try ${attempt + 1} in ${wait / 1000} s")
                if (kind == FailureKind.RATE_LIMITED || kind == FailureKind.SERVER_ERROR) gate.pushBack(wait) else delay(wait)
            }
        }
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
        val response = makeRequest(baseUrl)
        val t1 = System.currentTimeMillis()
        val doc = response.parse()
        val table = doc.select(ScrapingConstants.TABLE_SELECTOR).first()
            ?: throw NoFileTableException(baseUrl)

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
        onScrapeError: (ScanFailure) -> Unit = {},
        onSourceDone: () -> Unit = {},
        /** Every source's outcome: the games it gave, or null and the failure. */
        onSourceResult: (console: com.cortinadev.dogmatix.data.model.Console, url: String, files: Int?, failure: ScanFailure?) -> Unit = { _, _, _, _ -> }
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
                                        isTorrent -> scrapeTorrent(urlEntry, console)
                                        isRomm -> rommScrapingService.scrapeAndInsert(urlEntry, console)
                                        else -> scrapeAndInsertToDatabase(urlEntry.url, console.id, urlEntry.contentType)
                                    }
                                }
                                totalFiles.addAndGet(files)
                                totalTags.addAndGet(tags)
                                onSourceResult(console, urlEntry.url, files, null)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w("DatabaseScrapingService", "Scan of ${urlEntry.url} for ${console.id} failed", e)
                                val failure = ScanFailure(
                                    console.id, console.name, urlEntry.url, ScanFailures.kindOf(e), ScanFailures.httpCodeOf(e),
                                    (e.message ?: e.javaClass.simpleName).take(300)
                                )
                                onScrapeError(failure)
                                onSourceResult(console, urlEntry.url, null, failure)
                            } finally {
                                onSourceDone()
                            }
                        }
                    }
                }
            }
            Pair(totalFiles.get(), totalTags.get())
        }

    /** A torrent whose file list did not arrive in time gets one more try: the swarm is often found by then. */
    private suspend fun scrapeTorrent(urlEntry: com.cortinadev.dogmatix.data.model.UrlEntry, console: com.cortinadev.dogmatix.data.model.Console): Pair<Int, Int> =
        try {
            torrentScrapingService.scrapeAndInsert(urlEntry, console)
        } catch (e: TorrentMetadataTimeoutException) {
            android.util.Log.w("DatabaseScrapingService", "Metadata timed out for ${console.id}; one more try")
            torrentScrapingService.scrapeAndInsert(urlEntry, console)
        }

    suspend fun clearAllData() = downloadableFileDao.clearAll()

    suspend fun clearConsoleData(consoleId: String) = downloadableFileDao.deleteFilesByConsoleId(consoleId)
}
