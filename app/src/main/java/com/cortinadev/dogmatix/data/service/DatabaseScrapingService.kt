package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import dagger.hilt.android.qualifiers.ApplicationContext
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.FileTagEntity
import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.Manufacturer
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.util.ListingCheck
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

/**
 * What indexing one source gave: [files] games (with [tags] tag rows), [newFiles] of them not listed
 * before; [unchanged] when the listing was the same as last time and the rows were kept. The
 * validators are remembered for the next scan (see [ListingCheck]).
 */
data class SourceIndexResult(
    val files: Int,
    val tags: Int,
    val newFiles: Int = 0,
    val unchanged: Boolean = false,
    val servedBy: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val bodyHash: String? = null
)

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
     * request at a time. 404 / 403 are final. With [conditional] headers a 304 counts as an answer.
     */
    private suspend fun makeRequest(url: String, conditional: Map<String, String> = emptyMap()): org.jsoup.Connection.Response {
        val gate = hostGate(url)
        var attempt = 0
        while (true) {
            try {
                val response = gate.run {
                    val connection = Jsoup.connect(url)
                        .userAgent(ScrapingConstants.USER_AGENT)
                        .timeout(ScrapingConstants.CONNECTION_TIMEOUT_MS.toInt())
                    HttpHeadersUtils.configureBrowserHeaders(connection)
                    conditional.forEach { (k, v) -> connection.header(k, v) }
                    connection.ignoreHttpErrors(true).execute().also { it.bufferUp() }
                }
                val code = response.statusCode()
                if (code in 200..299 || (code == 304 && conditional.isNotEmpty())) return response
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

    /**
     * Indexes a web directory: the source's own address first, then its reserve addresses in order
     * when it fails. An unchanged listing (see [ListingCheck]) keeps the rows it gave last time.
     * The rows are always filed under the source's own address, whichever address answered.
     */
    private suspend fun scrapeHttpSource(entry: UrlEntry, console: Console, previous: ListingCheck.Previous?, force: Boolean): SourceIndexResult =
        withContext(Dispatchers.IO) {
            val targets = (listOf(entry.url) + entry.mirrors).distinct()
            var lastError: Exception? = null
            for (target in targets) {
                try {
                    return@withContext scrapeListing(entry, console, target, previous, force)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (target != targets.last()) android.util.Log.w("DatabaseScrapingService", "$target failed (${e.message}); trying the next address")
                }
            }
            throw lastError ?: IllegalStateException("No address for ${entry.url}")
        }

    private suspend fun scrapeListing(entry: UrlEntry, console: Console, target: String, previous: ListingCheck.Previous?, force: Boolean): SourceIndexResult {
        val servedBy = target.takeIf { it != entry.url }
        // A few listings of one server at a time, with a short pause: polite, but no longer a
        // fixed second per request (and none at all for sub-folders, which are not read).
        val t0 = System.currentTimeMillis()
        val response = makeRequest(target, ListingCheck.conditionalHeaders(entry, previous, force, target))
        val t1 = System.currentTimeMillis()
        val etag = response.header("ETag")
        val lastModified = response.header("Last-Modified")
        if (response.statusCode() == 304) {
            android.util.Log.i("DatabaseScrapingService", "$target unchanged (304) in ${t1 - t0} ms")
            return SourceIndexResult(previous!!.files, 0, unchanged = true, servedBy = servedBy,
                etag = etag ?: previous.etag, lastModified = lastModified ?: previous.lastModified, bodyHash = previous.bodyHash)
        }
        val body = response.bodyAsBytes()
        val bodyHash = ListingCheck.hash(body)
        if (ListingCheck.sameBody(entry, previous, force, bodyHash)) {
            android.util.Log.i("DatabaseScrapingService", "$target unchanged (same listing) in ${t1 - t0} ms")
            return SourceIndexResult(previous!!.files, 0, unchanged = true, servedBy = servedBy, etag = etag, lastModified = lastModified, bodyHash = bodyHash)
        }
        val doc = Jsoup.parse(java.io.ByteArrayInputStream(body), response.charset(), target)
        val table = doc.select(ScrapingConstants.TABLE_SELECTOR).first()
            ?: throw NoFileTableException(target)

        val contentTypeTag = FileParsingUtils.normalizeTag(entry.contentType.name)
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

            val (fileEntity, tagEntities) = FileParsingUtils.parseFileFromRow(row, target, console.id, linkCell)
            if (fileEntity != null) {
                files += fileEntity
                tags += (tagEntities.map { it.tag } + contentTypeTag)
            }
        }

        val t2 = System.currentTimeMillis()
        val write = downloadableFileDao.replaceSource(console.id, entry.url, files, tags, System.currentTimeMillis())
        android.util.Log.i("DatabaseScrapingService", "Indexed ${files.size} files (${write.newFiles} new) from $target: fetch ${t1 - t0} ms, parse ${t2 - t1} ms, store ${System.currentTimeMillis() - t2} ms")
        return SourceIndexResult(files.size, write.tags, write.newFiles, servedBy = servedBy, etag = etag, lastModified = lastModified, bodyHash = bodyHash)
    }

    /** Sources of one kind scanned at the same time: torrents wait on the swarm, HTTP on the server. */
    private val httpLimit = Semaphore(ScrapingConstants.PARALLEL_HTTP)
    private val torrentLimit = Semaphore(ScrapingConstants.PARALLEL_TORRENTS)
    private val rommLimit = Semaphore(ScrapingConstants.PARALLEL_ROMM)

    /**
     * Routes each URL entry to the right scraper:
     *   TORRENT → TorrentScrapingService
     *   romm:// → RommScrapingService (a platform of the RomM server set in Settings)
     *   HTTP    → [scrapeHttpSource] (with its reserve addresses)
     *
     * The sources run side by side, a few of each kind at a time (see [ScrapingConstants]), so a
     * slow torrent no longer holds up the web directories behind it. Each source replaces only its
     * own rows, so one that fails keeps what it gave last time. Unless [force], a source whose
     * listing did not change keeps its rows untouched (see [ListingCheck]).
     *
     * [onScrapeError] is invoked on [Dispatchers.IO]. Implementations must be thread-safe
     * (e.g. updating a [kotlinx.coroutines.flow.MutableStateFlow] is fine; touching UI is not).
     * [onSourceDone] runs after every enabled source, failed or not (scan progress).
     */
    suspend fun scrapeManufacturer(
        manufacturer: Manufacturer,
        onScrapeError: (ScanFailure) -> Unit = {},
        onSourceDone: () -> Unit = {},
        /** Every source's outcome: what it gave, or null and the failure. */
        onSourceResult: (console: Console, entry: UrlEntry, result: SourceIndexResult?, failure: ScanFailure?) -> Unit = { _, _, _, _ -> },
        /** What the last scan of a source left behind, if anything. */
        previousOf: (consoleId: String, url: String) -> com.cortinadev.dogmatix.data.state.SourceScanResult? = { _, _ -> null },
        force: Boolean = false
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
                                val previous = previousOf(console.id, urlEntry.url)?.let { p ->
                                    ListingCheck.Previous(
                                        ok = p.failure == null && p.files != null, fingerprint = p.fingerprint, etag = p.etag,
                                        lastModified = p.lastModified, bodyHash = p.bodyHash, servedBy = p.servedBy,
                                        rows = downloadableFileDao.countSource(console.id, urlEntry.url), files = p.files ?: 0
                                    )
                                }
                                val result = if (isTorrent && ListingCheck.canSkipMagnet(urlEntry, previous, force)) {
                                    SourceIndexResult(previous!!.files, 0, unchanged = true)
                                } else limit.withPermit {
                                    when {
                                        isTorrent -> scrapeTorrent(urlEntry, console)
                                        isRomm -> rommScrapingService.scrapeAndInsert(urlEntry, console)
                                        else -> scrapeHttpSource(urlEntry, console, previous, force)
                                    }
                                }
                                totalFiles.addAndGet(result.files)
                                totalTags.addAndGet(result.tags)
                                onSourceResult(console, urlEntry, result, null)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w("DatabaseScrapingService", "Scan of ${urlEntry.url} for ${console.id} failed", e)
                                val failure = ScanFailure(
                                    console.id, console.name, urlEntry.url, ScanFailures.kindOf(e), ScanFailures.httpCodeOf(e),
                                    (e.message ?: e.javaClass.simpleName).take(300)
                                )
                                onScrapeError(failure)
                                onSourceResult(console, urlEntry, null, failure)
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
    private suspend fun scrapeTorrent(urlEntry: UrlEntry, console: Console): SourceIndexResult =
        try {
            torrentScrapingService.scrapeAndInsert(urlEntry, console)
        } catch (e: TorrentMetadataTimeoutException) {
            android.util.Log.w("DatabaseScrapingService", "Metadata timed out for ${console.id}; one more try")
            torrentScrapingService.scrapeAndInsert(urlEntry, console)
        }

    /**
     * Removes rows of sources that are no longer set up (deleted or switched off) and rows indexed
     * before 2.0 that no source claimed, for [consoleIds] (null = all consoles). [configured] maps a
     * console to the URLs of its enabled sources.
     */
    suspend fun removeStaleRows(configured: Map<String, Set<String>>, consoleIds: Set<String>? = null) = withContext(Dispatchers.IO) {
        downloadableFileDao.indexedSources()
            .filter { consoleIds == null || it.consoleId in consoleIds }
            .filter { it.sourceUrl !in configured[it.consoleId].orEmpty() }
            .forEach { downloadableFileDao.deleteSource(it.consoleId, it.sourceUrl) }
    }

    suspend fun clearAllData() = downloadableFileDao.clearAll()

    suspend fun clearConsoleData(consoleId: String) = downloadableFileDao.deleteFilesByConsoleId(consoleId)
}
