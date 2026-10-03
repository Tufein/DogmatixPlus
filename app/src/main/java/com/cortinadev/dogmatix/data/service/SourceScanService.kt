package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.Manufacturer
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.repository.SourcesRepository
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.data.state.SourceScanResult
import com.cortinadev.dogmatix.data.state.SourceScanResults
import com.cortinadev.dogmatix.util.ListingCheck
import com.cortinadev.dogmatix.util.ScanFailure
import com.cortinadev.dogmatix.util.SourcesJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** How a finished scan went, for the notification after a background scan and the widget. */
data class ScanSummary(
    val sources: Int,
    val failed: Int,
    val unchanged: Int,
    val newFiles: Int,
    val background: Boolean,
    val at: Long = System.currentTimeMillis()
)

/**
 * Runs source scans for the whole app: the Sources screen, the library overview, a restored backup
 * and the background scan all go through here, one scan at a time. It lives in the application
 * scope, so a scan keeps going when the screen that started it is left.
 */
@Singleton
class SourceScanService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sources: SourcesRepository,
    private val scraping: DatabaseScrapingService,
    private val defaultSourcesLoader: DefaultSourcesLoader,
    private val state: RescanStateHolder,
    private val settingsRepository: SettingsRepository,
    private val scanResults: SourceScanResults
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _lastSummary = MutableStateFlow<ScanSummary?>(null)
    val lastSummary: StateFlow<ScanSummary?> = _lastSummary.asStateFlow()

    private class Tally {
        val sources = AtomicInteger()
        val failed = AtomicInteger()
        val unchanged = AtomicInteger()
        val newFiles = AtomicInteger()
    }

    /** First run: seed from the bundled list and scan it all. Later runs: scan only sources a new app version added. */
    fun initialize(): Job = scope.launch {
        if (state.isRescanning.value) return@launch
        if (sources.isEmpty()) {
            defaultSourcesLoader.loadDefaultSourcesToDatabase()
            scanAllNow(force = false, background = false, startMessage = R.string.sources_scrape_start)
        } else {
            val added = defaultSourcesLoader.syncNewDefaults()
            if (added.isEmpty()) return@launch
            runScan(background = false) { tally ->
                state.startProgress(added.sumOf { (_, urls) -> scanWeight(urls) })
                val started = AtomicInteger()
                coroutineScope {
                    added.forEach { (entity, newUrls) ->
                        launch {
                            state.setProgressMessage(context.getString(R.string.sources_processing_console, started.incrementAndGet(), added.size, entity.name))
                            scanConsole(Console(entity.id, entity.name, newUrls), entity.manufacturerId, force = false, tally = tally)
                        }
                    }
                }
            }
        }
    }

    /**
     * Every source. Unless [force], a source whose listing did not change keeps its rows (see
     * [ListingCheck]); a source that fails keeps what it gave last time.
     */
    fun scanAll(force: Boolean = false): Job = scope.launch {
        if (state.isRescanning.value) return@launch
        scanAllNow(force, background = false, startMessage = R.string.sources_rescan_start)
    }

    /** The same as [scanAll], waited for; used by the background job. Returns null when a scan was already running. */
    suspend fun scanAllInBackground(): ScanSummary? {
        if (state.isRescanning.value) return null
        return scanAllNow(force = false, background = true, startMessage = R.string.sources_rescan_start)
    }

    /** One console, reading every listing again (the user asked for exactly this console). */
    fun scanConsole(consoleId: String): Job = scope.launch {
        if (state.isRescanning.value) return@launch
        val entity = sources.getConsoleEntity(consoleId) ?: return@launch
        val urls = SourcesJson.parseUrlEntries(entity.urls)
        runScan(background = false) { tally ->
            state.setProgressMessage(context.getString(R.string.sources_refreshing_console, entity.name))
            state.startProgress(scanWeight(urls))
            scanConsole(Console(entity.id, entity.name, urls), entity.manufacturerId, force = true, tally = tally)
            scraping.removeStaleRows(mapOf(entity.id to urls.filter { it.enabled }.map { it.url }.toSet()), setOf(entity.id))
        }
    }

    /**
     * Scans again only [failed] (nothing else is touched): servers often recover within a minute,
     * and a second, calmer pass usually gets the rest.
     */
    fun retryFailed(failed: List<ScanFailure>): Job = scope.launch {
        if (failed.isEmpty() || state.isRescanning.value) return@launch
        runScan(background = false) { tally ->
            state.startProgress(failed.size)
            coroutineScope {
                failed.groupBy { it.consoleId }.forEach { (consoleId, list) ->
                    val entity = sources.getConsoleEntity(consoleId) ?: return@forEach
                    val urls = SourcesJson.parseUrlEntries(entity.urls).filter { u -> u.enabled && list.any { it.url == u.url } }
                    if (urls.isEmpty()) return@forEach
                    launch { scanConsole(Console(entity.id, entity.name, urls), entity.manufacturerId, force = true, tally = tally) }
                }
            }
        }
    }

    private suspend fun scanAllNow(force: Boolean, background: Boolean, startMessage: Int): ScanSummary? = runScan(background) { tally ->
        val current = sources.manufacturers.first()
        val total = current.sumOf { it.consoles.size }
        state.setProgressMessage(context.getString(startMessage, total))
        state.startProgress(current.sumOf { m -> m.consoles.sumOf { scanWeight(it.urls) } })
        // All consoles at once: the scraping service caps how many sources of each kind run
        // together, so a slow torrent no longer holds up every console behind it.
        val started = AtomicInteger()
        coroutineScope {
            current.forEach { manufacturer ->
                manufacturer.consoles.forEach { console ->
                    launch {
                        state.setProgressMessage(context.getString(R.string.sources_processing_console, started.incrementAndGet(), total, console.name))
                        scanConsole(console, manufacturer.id, force, tally)
                    }
                }
            }
        }
        // Sources that were deleted or switched off, and rows from before 2.0 nobody claimed.
        scraping.removeStaleRows(current.flatMap { it.consoles }.associate { c -> c.id to c.urls.filter { it.enabled }.map { it.url }.toSet() })
    }

    private suspend fun scanConsole(console: Console, manufacturerId: String, force: Boolean, tally: Tally) {
        scraping.scrapeManufacturer(
            Manufacturer(manufacturerId, manufacturerId, listOf(console)),
            // Failures are collected and reported once at the end, not one dialog each.
            onScrapeError = state::addFailure,
            onSourceDone = state::advanceProgress,
            onSourceResult = { c, entry, result, failure -> record(c, entry, result, failure, tally) },
            previousOf = scanResults::get,
            force = force
        )
        // A console with nothing enabled still counts as one step (see scanWeight).
        if (console.urls.none { it.enabled }) state.advanceProgress()
        // Shown per console in the library overview.
        runCatching { settingsRepository.markConsoleScanned(console.id, System.currentTimeMillis()) }
    }

    private fun record(console: Console, entry: UrlEntry, result: SourceIndexResult?, failure: ScanFailure?, tally: Tally) {
        tally.sources.incrementAndGet()
        val previous = scanResults.get(console.id, entry.url)
        val stored = if (result != null) {
            if (result.unchanged) tally.unchanged.incrementAndGet()
            tally.newFiles.addAndGet(result.newFiles)
            SourceScanResult(
                files = result.files, unchanged = result.unchanged, newFiles = result.newFiles, servedBy = result.servedBy,
                etag = result.etag, lastModified = result.lastModified, bodyHash = result.bodyHash,
                fingerprint = ListingCheck.fingerprint(entry)
            )
        } else {
            tally.failed.incrementAndGet()
            // Keep what is needed to recognise the listing when it is back, but mark the failure.
            SourceScanResult(
                files = null, failure = failure?.kind, httpCode = failure?.httpCode,
                etag = previous?.etag, lastModified = previous?.lastModified, bodyHash = previous?.bodyHash, fingerprint = null
            )
        }
        scanResults.record(console.id, entry.url, stored)
    }

    /** Steps a console adds to the scan progress: one per enabled source, at least one. */
    private fun scanWeight(urls: List<UrlEntry>): Int = urls.count { it.enabled }.coerceAtLeast(1)

    private suspend fun runScan(background: Boolean, block: suspend (Tally) -> Unit): ScanSummary? = mutex.withLock {
        val tally = Tally()
        state.setRescanning(true)
        state.beginScanReport()
        scraping.resetForScan()
        try {
            block(tally)
            // A background scan tells the user through its notification instead of a dialog.
            if (!background) state.publishScanReport()
            ScanSummary(tally.sources.get(), tally.failed.get(), tally.unchanged.get(), tally.newFiles.get(), background)
                .also { _lastSummary.value = it }
        } finally {
            state.setRescanning(false)
            state.clearProgressMessage()
            state.clearTorrentFetchProgress()
        }
    }
}
