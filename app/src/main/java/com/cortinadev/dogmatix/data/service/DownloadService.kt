package com.cortinadev.dogmatix.data.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.DogmatixApplication
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.data.model.DownloadFailure
import com.cortinadev.dogmatix.data.model.DownloadFailureCategory
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ArchiveUtils
import com.cortinadev.dogmatix.util.AutoRetry
import com.cortinadev.dogmatix.util.DatStatus
import com.cortinadev.dogmatix.util.ArchiveExtractionUtils
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.DownloadCondition
import com.cortinadev.dogmatix.util.DownloadQueue
import com.cortinadev.dogmatix.util.DownloadStreams
import com.cortinadev.dogmatix.util.DownloadFailures
import com.cortinadev.dogmatix.util.DownloadExtractionException
import com.cortinadev.dogmatix.util.WaitInfo
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.MirrorUrls
import com.cortinadev.dogmatix.util.ResumePlan
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.SourceRanking
import com.cortinadev.dogmatix.util.DebridMatcher
import com.cortinadev.dogmatix.util.Checksums
import com.cortinadev.dogmatix.util.HoldParking
import com.cortinadev.dogmatix.util.PartialOwner
import com.cortinadev.dogmatix.util.SourceFailures
import com.cortinadev.dogmatix.util.StorageException
import com.cortinadev.dogmatix.util.StorageAccessException
import com.cortinadev.dogmatix.util.ExpectedHash
import com.cortinadev.dogmatix.util.SourcesJson
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.VerifyState
import java.net.URI
import java.net.HttpURLConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import dagger.hilt.android.qualifiers.ApplicationContext
import com.cortinadev.dogmatix.util.QueueActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

private const val TAG = "DownloadService"
/** Upper bound for an uncached torrent to be fetched by the debrid service before we give up. */
private const val DEBRID_MAX_WAIT_MS = 6 * 60 * 60 * 1000L
private val DEVICE_FAILURES = setOf(
    DownloadFailureCategory.STORAGE_FULL,
    DownloadFailureCategory.STORAGE_PERMISSION,
    DownloadFailureCategory.STORAGE_WRITE,
    DownloadFailureCategory.EXTRACTION
)

/** A download stopped because free space fell below the limit set in Settings; it can be retried. */
class LowStorageException(fileName: String) : Exception("Not enough free space for $fileName")

@Singleton
class DownloadService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val archiveExtractorService: ArchiveExtractorService,
    private val downloadSpeedController: DownloadSpeedController,
    private val bandwidthLimiter: BandwidthLimiter,
    private val downloadHttpClient: DownloadHttpClient,
    private val downloadProgressTracker: DownloadProgressTracker,
    private val downloadFileManager: DownloadFileManager,
    private val torrentDownloadService: TorrentDownloadService,
    private val torrentHandleRegistry: TorrentHandleRegistry,
    private val historyDao: DownloadHistoryDao,
    private val torBoxClient: TorBoxClient,
    private val realDebridClient: RealDebridClient,
    private val rommClient: RommClient,
    private val downloadGate: DownloadGate,
    private val conditionGate: ItemConditionGate,
    private val consoleDao: ConsoleDao,
    private val appSettings: AppSettings,
    private val partials: PartialDownloads,
    private val datService: DatService,
    private val sourceTrack: SourceTrackService,
    private val moveGate: StorageMoveGate
) {
    val downloads: StateFlow<List<DownloadItemModel>> = downloadProgressTracker.downloads

    /**
     * File names whose download is really finished and on disk (after copy / extraction). The
     * torrent bridge flips a row to COMPLETED as soon as libtorrent is done, before the file is
     * moved into place, so status watchers cannot tell "done" from "about to be copied".
     */
    // Room for a whole batch: tryEmit drops the name when the buffer is full, and the after-download
    // steps (covers, playlists, RomM upload, the log) are slower than small games finish.
    private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 10_000)
    val finished: SharedFlow<String> = _finished.asSharedFlow()

    private val _waiting = MutableStateFlow<Set<String>>(emptySet())
    /** Downloads held back by the schedule (Wi-Fi / charger / night); see [DownloadGate]. */
    val waitingFiles: StateFlow<Set<String>> = _waiting.asStateFlow()
    val gate: DownloadGate get() = downloadGate

    /** Per-download conditions ("Download when...") set right now, by file name. */
    val itemConditions: StateFlow<Map<String, DownloadCondition>> get() = conditionGate.conditions
    /** The per-download conditions that are not met yet and what is missing. */
    val itemWaits: StateFlow<Map<String, WaitInfo>> get() = conditionGate.unmet

    /** Downloads that passed their condition and are about to run or running: a new condition would be meaningless. */
    private val proceeding = ConcurrentHashMap.newKeySet<String>()

    /**
     * Gives downloads that have not started yet (waiting for a slot, for the global rules or for a
     * condition) their own condition, or with null removes it ("Start now": a waiting download goes
     * on at once). Downloads already transferring are left alone. Cheap enough for the UI thread;
     * the persisting happens in the background.
     */
    fun setCondition(fileNames: Collection<String>, condition: DownloadCondition?) {
        val eligible = fileNames.filter { downloadEntities.containsKey(it) && it !in proceeding }
        conditionGate.setAll(eligible, condition)
    }

    private val _verification = MutableStateFlow<Map<String, VerifyState>>(emptyMap())
    /** Result of checking a finished download against the hash its source published. */
    val verification: StateFlow<Map<String, VerifyState>> = _verification.asStateFlow()
    /** Hash the server announced in the response headers of the transfer in progress, per file. */
    private val headerHashes = ConcurrentHashMap<String, String>()

    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val cleanupJobs = ConcurrentHashMap<String, Job>()
    private val sourceSelections = ConcurrentHashMap<String, CompletableDeferred<DownloadableFileEntity>>()
    /** The download slots and the order of what waits for one (the user can reorder it). */
    private val queue = DownloadQueue(3)

    /** Downloads waiting for a free slot, first to start first. */
    val queued: StateFlow<List<String>> = queue.waiting

    fun moveUp(fileName: String) = queue.moveUp(fileName)
    fun moveDown(fileName: String) = queue.moveDown(fileName)
    fun moveToFront(fileName: String) = queue.moveToFront(fileName)
    private val downloadEntities = ConcurrentHashMap<String, DownloadableFileEntity>()
    private val extractedFilesMap = ConcurrentHashMap<String, List<String>>()
    /** Debrid client + torrent id per file being fetched through the debrid route (see [performDebridDownload]). */
    private val debridTorrents = ConcurrentHashMap<String, Pair<DebridClient, String>>()

    /** True while [fileName] goes through a debrid service (7.5 power rules let those finish instead of parking them). */
    fun isDebrid(fileName: String): Boolean = fileName in viaDebrid || debridTorrents.containsKey(fileName)
    /** Downloads that took the debrid route (set before the torrent is added there). */
    private val viaDebrid = ConcurrentHashMap.newKeySet<String>()
    /** Files whose job is being cancelled by a pause (they land on PAUSED instead of STOPPED). */
    private val pausingFiles = ConcurrentHashMap.newKeySet<String>()

    /** True while a pause of [fileName] has not landed yet. */
    fun isPausing(fileName: String): Boolean = fileName in pausingFiles

    /**
     * Downloads that are really moving data (or a torrent fetching it): past their slot, the schedule
     * and their condition. Rows that only wait are DOWNLOADING too, but are not in here.
     */
    private val transferring = ConcurrentHashMap.newKeySet<String>()

    /** True while [fileName] is transferring (see [transferring]). */
    fun isTransferring(fileName: String): Boolean = fileName in transferring

    /** Web transfers whose partial file is kept and can be continued with a `Range` request (see [HoldParking.webResumable]). */
    private val parkable = ConcurrentHashMap.newKeySet<String>()

    /**
     * True when pausing [fileName] now loses nothing: a torrent (cache and handle stay), or a web
     * download whose partial is noted and continued by its server. Debrid downloads are left to finish.
     */
    fun canParkSafely(fileName: String): Boolean {
        val entity = downloadEntities[fileName] ?: return false
        if (isDebrid(fileName)) return false
        return entity.isTorrent || fileName in parkable
    }

    /** Torrents whose files could not be moved into place: a local problem, not the source's. */
    private val localFailures = ConcurrentHashMap.newKeySet<String>()

    private val userActionListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** [listener] hears the file name whenever the user pauses, resumes, stops or removes a download. */
    fun addUserActionListener(listener: (String) -> Unit) { userActionListeners += listener }

    private fun userActed(fileName: String) = userActionListeners.forEach { it(fileName) }

    // Single supervised scope for all internal coroutines — tied to this singleton's lifetime
    // so jobs are not orphaned if the service is destroyed.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Downloads interrupted by process death, oldest first (filled by [restoreHistory]). */
    private val interrupted = CopyOnWriteArrayList<String>()
    private val restored = CompletableDeferred<Unit>()
    private val requeueDone = AtomicBoolean(false)
    // Hashing must not grow without a bound as small files finish during a bulk download.
    private val verificationSlots = Semaphore(1)

    /** Operational storage/network failures in housekeeping must not reach Android's crash handler. */
    private fun launchBackground(operation: String, block: suspend () -> Unit): Job = serviceScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$operation failed: ${e.message}", e)
        }
    }

    init {
        launchBackground("Download slot settings") {
            settingsRepository.concurrentDownloads.collect { max -> queue.setSlots(max) }
        }
        launchBackground("Per-server settings") { appSettings.perServerLimit.collect { queue.setPerHost(it) } }
        launchBackground("Download history restore") { restoreHistory() }
    }

    /**
     * Puts the downloads that were interrupted by the app closing back in the queue (once per
     * run, when the user opens the app and *Settings → Continue the queue after a restart* is on).
     * Returns how many.
     */
    suspend fun requeueInterrupted(): Int {
        restored.await()
        if (!requeueDone.compareAndSet(false, true) || !appSettings.requeueAfterRestart.first()) return 0
        val names = interrupted.toList().also { interrupted.clear() }
        // Off the caller's (UI) thread, and as one batch: hundreds of single retries on the main
        // thread made Android report the app as not responding, and the queue came back after
        // every forced close.
        return withContext(Dispatchers.Default) { retryDownloads(names) }
    }

    /**
     * Brings back the Downloads list from the previous run. Anything that was still in
     * flight when the process died comes back as STOPPED so the user can retry it.
     */
    private suspend fun restoreHistory() {
        try {
            val rows = historyDao.getAll()
            rows.forEach { row -> downloadEntities.putIfAbsent(row.fileName, row.toEntity()) }
            val items = rows.map { it.toItem() }
            // Queued or running when the process ended (not paused: that was the user's choice).
            interrupted += rows.filter { it.status in setOf(DownloadStatus.QUEUED.name, DownloadStatus.DOWNLOADING.name, DownloadStatus.COPYING.name, DownloadStatus.UNZIPPING.name) }
                .sortedBy { it.startedAt }.map { it.fileName }
            downloadProgressTracker.restore(items)
            items.filter { it.status == DownloadStatus.STOPPED }.forEach { item ->
                val row = rows.first { it.fileName == item.fileName }
                if (row.status != item.status.name || row.finishedAt != item.finishedAt) {
                    historyDao.updateStatus(item.fileName, item.status.name, item.finishedAt)
                }
            }
            // Conditions of downloads that are gone (removed while the app was closed) are dropped.
            conditionGate.prune { downloadEntities.containsKey(it) }
            Log.d(TAG, "Restored ${rows.size} download(s) from history")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not restore download history: ${e.message}")
        } finally {
            restored.complete(Unit)
        }
    }

    private val startLock = Any()

    fun startDownload(file: DownloadableFileEntity, condition: DownloadCondition? = null) = startDownloads(listOf(file), condition)

    /**
     * Queues [files] in one go: one list update, one history write and one service start, then a
     * job per file. A bulk start of hundreds of games used to do all of that once per game on the
     * UI thread, long enough for Android to report the app as not responding. Files already
     * queued or running are skipped (repeated taps, or a bulk start racing a tap).
     *
     * [condition] (optional) is the "Download when..." of the whole batch: each file waits for it
     * without holding a slot, so the rest of the queue is never blocked. Example, tonight for a
     * whole console: `downloadService.startDownloads(files, DownloadCondition(ConditionKind.TONIGHT))`;
     * at 14:30: `DownloadConditions.atTime(14 * 60 + 30, System.currentTimeMillis())`.
     */
    fun startDownloads(files: List<DownloadableFileEntity>, condition: DownloadCondition? = null) {
        lateinit var sources: List<CompletableDeferred<DownloadableFileEntity>>
        lateinit var jobs: List<Job>
        lateinit var priorCleanups: List<Job>
        val fresh = synchronized(startLock) {
            // One set of the active names instead of a walk over the whole list per file.
            val active = downloadProgressTracker.activeNames()
            val items = files.distinctBy { it.fileName }
                .filterNot { it.fileName in active }
                .map { it to downloadFileManager.createDownloadItem(it) }
            if (items.isEmpty()) return
            val names = items.mapTo(HashSet()) { it.first.fileName }
            _verification.update { it - names }
            _switchedTo.update { it - names }
            switchedOnce.removeAll(names)
            items.forEach { (file, _) -> downloadEntities[file.fileName] = file }
            if (condition != null) conditionGate.setAll(names, condition)
            priorCleanups = names.mapNotNull { cleanupJobs[it] }.distinct()
            sources = items.map { CompletableDeferred<DownloadableFileEntity>() }
            items.forEachIndexed { i, (file, _) -> sourceSelections[file.fileName] = sources[i] }
            jobs = items.mapIndexed { i, (file, _) -> launchJob(file, sources[i], startImmediately = false) }
            // Publish only after every accepted row has an entity and a cancellable job. Stop/
            // pause uses this same lock, so it cannot miss the later rows in a bulk registration.
            downloadProgressTracker.addDownloads(items.map { it.second })
            items
        }
        launchBackground("Storing new downloads") {
            // Insert history before any transfer starts: a fast download's status UPDATE used
            // to arrive before its row existed, then the late batch insert restored DOWNLOADING.
            val chosen = try {
                priorCleanups.forEach { it.join() }
                fresh.mapIndexed { i, (file, _) ->
                    try {
                        chooseSource(file, sources[i])
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not choose a source for ${file.fileName}: ${e.message}")
                        file
                    }
                }
            } catch (e: CancellationException) {
                sources.forEach { it.cancel(e) }
                throw e
            }
            try {
                // A pause/stop/delete during source selection wins over the original batch row.
                val latest = downloadProgressTracker.getDownloads().associateBy { it.fileName }
                val rows = chosen.mapIndexedNotNull { i, entity ->
                    latest[entity.fileName]?.takeIf { downloadEntities[entity.fileName] == entity && sourceSelections[entity.fileName] === sources[i] }
                        ?.let { DownloadHistoryEntity.from(entity, it) }
                }
                historyDao.upsertAll(rows)
                // Changes while Room was inserting may already have been updated against a
                // nonexistent row. Re-persist the canonical statuses through the tracker actor.
                downloadProgressTracker.persistCurrentStatuses(rows.map { it.fileName })
                chosen.filter { !downloadEntities.containsKey(it.fileName) }.forEach { historyDao.delete(it.fileName) }
            } catch (e: CancellationException) {
                sources.forEach { it.cancel(e) }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not store the new downloads: ${e.message}", e)
            } finally {
                // A recoverable persistence failure must not strand every download in the batch.
                chosen.forEachIndexed { i, file ->
                    sources[i].complete(file)
                    sourceSelections.remove(file.fileName, sources[i])
                }
            }
        }
        startForegroundService()
        jobs.forEach { it.start() }
    }

    /** [source]: a new download waits for the source picked for it (see [chooseSource]). */
    private fun launchJob(file: DownloadableFileEntity, source: CompletableDeferred<DownloadableFileEntity>? = null, startImmediately: Boolean = true): Job = synchronized(startLock) {
        val previous = downloadJobs[file.fileName]
        val cleanup = cleanupJobs[file.fileName]
        val selection = source ?: sourceSelections[file.fileName]
        // Registered before it starts, so a download that ends at once cannot leave a stale entry.
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            // The row actually downloaded: the same file name, possibly from another source.
            var current = file
            val owner = coroutineContext[Job]!!
            try {
                previous?.join()
                cleanup?.join()
                selection?.await()
                currentCoroutineContext().ensureActive()
                current = downloadEntities[file.fileName] ?: return@launch
                synchronized(startLock) {
                    if (downloadJobs[file.fileName] !== owner || !owner.isActive) return@launch
                    // A previous native preflight may have reported failure while this retry
                    // waited for its cleanup. This generation now owns the row and its state.
                    downloadProgressTracker.updateDownloadStatus(file.fileName, DownloadStatus.DOWNLOADING)
                }
                val chosen = current
                var ran = false
                while (!ran) {
                    // The download's own condition is waited for outside a slot: it never blocks others.
                    conditionGate.awaitReady(file.fileName)
                    withSlot(chosen) {
                        // A condition set while it stood in line: give the slot back and wait outside.
                        if (conditionGate.isBlocked(file.fileName)) return@withSlot
                        proceeding += file.fileName
                        conditionGate.clear(file.fileName)
                        awaitSchedule(file.fileName)
                        // Brief delay to allow the foreground service and initial UI state to settle
                        // before network/torrent activity begins.
                        delay(1000L)
                        currentCoroutineContext().ensureActive()
                        if (downloadJobs[file.fileName] !== owner) return@withSlot
                        transferring += file.fileName
                        perform(chosen)
                        ran = true
                    }
                }
                autoRetries.remove(file.fileName)
                // Torrents end on COMPLETED or FAILED without throwing; web downloads throw on failure.
                when (downloadProgressTracker.getDownloads().firstOrNull { it.fileName == file.fileName }?.status) {
                    DownloadStatus.COMPLETED -> transferSamples.remove(file.fileName).let { s ->
                        sourceTrack.recordSuccess(chosen, s?.first ?: 0L, s?.second ?: 0L)
                    }
                    // A torrent that could not be moved into place failed here, not at its source.
                    DownloadStatus.FAILED -> if (!localFailures.remove(file.fileName) &&
                        downloadProgressTracker.getDownloads().firstOrNull { it.fileName == file.fileName }?.failure?.category !in DEVICE_FAILURES) {
                        sourceTrack.recordFailure(chosen, null)
                        launchBackground("Switching source for ${file.fileName}") { switchSourceOnce(file.fileName) }
                    }
                    else -> Unit
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                synchronized(startLock) {
                    if (downloadJobs[file.fileName] === owner) {
                        downloadProgressTracker.updateDownloadStatus(file.fileName, if (file.fileName in pausingFiles) DownloadStatus.PAUSED else DownloadStatus.STOPPED)
                    }
                }
                throw e
            } catch (e: LowStorageException) {
                synchronized(startLock) { if (downloadJobs[file.fileName] === owner) downloadProgressTracker.updateDownloadStatus(file.fileName, DownloadStatus.STOPPED, failure = DownloadFailures.classify(e)) }
                notifyLowStorage()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${file.fileName}: ${e.message}")
                synchronized(startLock) { if (downloadJobs[file.fileName] === owner) downloadProgressTracker.updateDownloadStatus(file.fileName, DownloadStatus.FAILED, failure = DownloadFailures.classify(e)) }
                // A missing folder or a full disk says nothing about the source.
                if (SourceFailures.isSourceSide(e)) sourceTrack.recordFailure(current, e)
                scheduleAutoRetry(file.fileName, e)
            } finally {
                finishJob(file.fileName, owner)
            }
        }
        job.invokeOnCompletion {
            // A LAZY job may be canceled before its body/finally is entered.
            finishJob(file.fileName, job)
        }
        downloadJobs[file.fileName] = job
        if (startImmediately) job.start()
        job
    }

    private fun finishJob(fileName: String, owner: Job) = synchronized(startLock) {
        if (downloadJobs.remove(fileName, owner)) {
            transferring.remove(fileName)
            viaDebrid.remove(fileName)
            parkable.remove(fileName)
            localFailures.remove(fileName)
            proceeding.remove(fileName)
            conditionGate.clear(fileName)
        }
    }

    /** Replacements wait for both the old IO worker and its serialized native/account cleanup. */
    private fun launchCleanup(fileName: String, operation: String, transfer: Job?, block: suspend () -> Unit): Job {
        val previous = cleanupJobs[fileName]
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                transfer?.join()
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$operation failed: ${e.message}", e)
            } finally {
                synchronized(startLock) {
                    if (cleanupJobs.remove(fileName, coroutineContext[Job]!!)) pausingFiles.remove(fileName)
                }
            }
        }
        cleanupJobs[fileName] = job
        job.start()
        return job
    }

    /** Automatic retries done so far per download; a retry by the user starts the count again. */
    private val autoRetries = ConcurrentHashMap<String, Int>()

    /**
     * A failure that may pass (see [AutoRetry]) is started again after a growing wait, unless the
     * user did something with the row in the meantime or switched this off.
     */
    private fun scheduleAutoRetry(fileName: String, error: Exception) {
        val done = autoRetries[fileName] ?: 0
        val wait = if (AutoRetry.isTemporary(error)) AutoRetry.waitBeforeRetry(done) else null
        launchBackground("Automatic retry for $fileName") {
            // No (more) retries: the download failed for good here; another source may have it
            // (not when the device was the problem: no folder, the file could not be written).
            if (wait == null || !appSettings.autoRetryFailed.first()) {
                if (SourceFailures.isSourceSide(error)) switchSourceOnce(fileName)
                return@launchBackground
            }
            autoRetries[fileName] = done + 1
            Log.i(TAG, "Retrying $fileName by itself in ${wait / 1000}s (retry ${done + 1} of ${AutoRetry.WAITS_MS.size})")
            delay(wait)
            val stillFailed = downloadProgressTracker.getDownloads().any { it.fileName == fileName && it.status == DownloadStatus.FAILED }
            if (stillFailed && downloadEntities.containsKey(fileName)) restartDownload(fileName)
        }
    }

    /** Bytes moved and milliseconds taken by the last finished web transfer per file, for the source's speed. */
    private val transferSamples = ConcurrentHashMap<String, Pair<Long, Long>>()
    /** Downloads that already moved to another source after failing (once per download). */
    private val switchedOnce = ConcurrentHashMap.newKeySet<String>()
    private val _switchedTo = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 7.5: file name -> short name of the source a failed download moved to ("Switched to <source>"). */
    val switchedSources: StateFlow<Map<String, String>> = _switchedTo.asStateFlow()

    /**
     * With "Pick the best source" on, a new download takes the copy of the same file from the source
     * with the best track record (see [SourceTrackService.ranked]); [file] itself otherwise.
     */
    private suspend fun chooseSource(file: DownloadableFileEntity, selection: CompletableDeferred<DownloadableFileEntity>): DownloadableFileEntity {
        val best = sourceTrack.pickBest(file)
        if (best.downloadUrl == file.downloadUrl || best.fileName != file.fileName) return file
        synchronized(startLock) {
            if (sourceSelections[file.fileName] !== selection || !downloadEntities.replace(file.fileName, file, best)) return file
        }
        Log.i(TAG, "Taking ${file.fileName} from ${SourceRanking.label(best.sourceUrl)}")
        // A part left by the other server is not continued (the caller stores the new history row).
        partials.remove(file.fileName)
        return best
    }

    /**
     * A download that failed for good (no automatic retry left) starts again once from the next-best
     * source of the same file, with "Switched to <source>" on its row.
     */
    private suspend fun switchSourceOnce(fileName: String) {
        if (fileName in switchedOnce) return
        val current = downloadEntities[fileName] ?: return
        val next = sourceTrack.nextBest(current)?.takeIf { it.fileName == fileName } ?: return
        val stillFailed = downloadProgressTracker.getDownloads().any { it.fileName == fileName && it.status == DownloadStatus.FAILED }
        if (!stillFailed || !downloadEntities.replace(fileName, current, next) || !switchedOnce.add(fileName)) return
        Log.i(TAG, "Switching $fileName to ${SourceRanking.label(next.sourceUrl)}")
        // A part written from the other server is not continued.
        partials.remove(fileName)
        _switchedTo.update { it + (fileName to SourceRanking.label(next.sourceUrl)) }
        rewriteHistory(next)
        autoRetries.remove(fileName)
        restartDownload(fileName)
    }

    /** The history row follows the source actually used, so a restart picks it up again. */
    private suspend fun rewriteHistory(entity: DownloadableFileEntity) {
        val item = downloadProgressTracker.getDownloads().firstOrNull { it.fileName == entity.fileName } ?: return
        runCatching { historyDao.upsertAll(listOf(DownloadHistoryEntity.from(entity, item))) }
    }

    private suspend fun withSlot(file: DownloadableFileEntity, block: suspend () -> Unit) {
        // Torrents have no single server; web downloads count against their host's limit.
        val host = if (file.isTorrent) "" else runCatching { URI(file.downloadUrl).host.orEmpty() }.getOrDefault("")
        queue.acquire(file.fileName, host)
        try { block() } finally { queue.release(host) }
    }

    /** Tells the user (once per minute at most) that downloads stopped for lack of space. */
    @Volatile private var lastLowStorageNotice = 0L
    private fun notifyLowStorage() {
        val now = System.currentTimeMillis()
        if (now - lastLowStorageNotice < 60_000) return
        lastLowStorageNotice = now
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, DogmatixApplication.SCAN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle(context.getString(R.string.storage_low_title))
            .setContentText(context.getString(R.string.storage_low_text))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(4221, notification)
    }

    /** Waits while the user's schedule (Wi-Fi only, charging only, night only) says no. */
    private suspend fun awaitSchedule(fileName: String) {
        if (downloadGate.waiting.value.isEmpty()) return
        _waiting.update { it + fileName }
        try {
            downloadGate.awaitGo()
        } finally {
            _waiting.update { it - fileName }
        }
    }

    /** Routes a file to the debrid, torrent or plain HTTP path (decided at start and on every retry). */
    private suspend fun perform(file: DownloadableFileEntity) {
        // 8.0: while smart storage moves this console's folder, wait; the file then goes to the new folder.
        moveGate.awaitFree(file.consoleId)
        val debrid = if (file.isTorrent) debridClient(settingsRepository.debridProvider.first()) else null
        if (debrid != null) viaDebrid += file.fileName
        when {
            debrid != null -> performDebridDownload(file, debrid)
            file.isTorrent -> performTorrentDownload(file)
            // Plain HTTP / RomM keep their partial and continue with a Range request on retry.
            else -> performHttpDownload(file, resumable = true)
        }
    }

    private fun debridClient(provider: DebridProvider): DebridClient? = when (provider) {
        DebridProvider.NONE -> null
        DebridProvider.TORBOX -> torBoxClient
        DebridProvider.REAL_DEBRID -> realDebridClient
    }

    fun cancelDownload(fileName: String) {
        userActed(fileName)
        synchronized(startLock) {
            pausingFiles.remove(fileName)
            val entity = downloadEntities[fileName] ?: return
            // The old worker's finally may be superseded by an immediate retry. A stop must
            // still remove its time/charging condition before that replacement is registered.
            conditionGate.clear(fileName)
            val transfer = downloadJobs[fileName]
            val debrid = debridTorrents.remove(fileName)
            downloadProgressTracker.updateDownloadStatus(fileName, DownloadStatus.STOPPED)
            launchCleanup(fileName, "Stopping $fileName", transfer) {
                when {
                    debrid != null -> {
                        historyDao.setDebrid(fileName, null, null, null)
                        debrid.first.delete(debrid.second)
                    }
                    entity.isTorrent -> torrentDownloadService.cancelDownload(entity, reportStatus = false)
                }
            }
            transfer?.cancel()
        }
    }

    /**
     * Parks a download. A torrent keeps its cache and handle, so retry resumes from disk. A web
     * download (queued or running) keeps its partial file, and *Resume* continues it with a `Range`
     * request (see [PartialDownloads]); a paused download is not put back in the queue after a restart.
     *
     * [byUser]: false for the power rules' own parking (see [PowerHoldService]). A second pause before
     * the first landed is ignored, so the download still ends on PAUSED.
     */
    fun pauseDownload(fileName: String, byUser: Boolean = true) {
        if (byUser) userActed(fileName)
        synchronized(startLock) {
            val entity = downloadEntities[fileName] ?: return
            val status = downloadProgressTracker.getDownloads().firstOrNull { it.fileName == fileName }?.status ?: return
            if (!QueueActions.canPause(status, entity.isTorrent)) return
            pausingFiles.add(fileName)
            val transfer = downloadJobs[fileName]
            downloadProgressTracker.updateDownloadStatus(fileName, DownloadStatus.PAUSED)
            launchCleanup(fileName, "Pausing $fileName", transfer) {
                if (entity.isTorrent) torrentDownloadService.pauseDownload(entity)
            }
            transfer?.cancel()
        }
    }

    fun retryDownload(fileName: String) {
        userActed(fileName)
        autoRetries.remove(fileName)
        switchedOnce.remove(fileName)
        restartDownload(fileName)
    }

    private fun restartDownload(fileName: String) {
        val job = synchronized(startLock) {
            if (!downloadProgressTracker.canRetryDownload(fileName)) return
            val entity = downloadEntities[fileName] ?: return
            // Reset the existing list entry in place — calling startDownload would add a duplicate.
            downloadProgressTracker.resetDownloadForRetry(fileName)
            launchJob(entity, startImmediately = false)
        }
        _verification.update { it - fileName }
        launchBackground("Storing restart for $fileName") {
            historyDao.markRestarted(fileName, DownloadStatus.DOWNLOADING.name, System.currentTimeMillis())
            downloadProgressTracker.persistCurrentStatuses(listOf(fileName))
        }
        startForegroundService()
        job.start()
    }

    /**
     * [retryDownload] for many downloads at once: one list update, one database write, one service
     * start. Returns how many were restarted. [byUser]: false when the power rules requeue what they parked.
     */
    fun retryDownloads(fileNames: List<String>, byUser: Boolean = true): Int {
        if (byUser) fileNames.forEach(::userActed)
        fileNames.forEach { autoRetries.remove(it); switchedOnce.remove(it) }
        lateinit var jobs: List<Job>
        val entities = synchronized(startLock) {
            downloadProgressTracker.resetDownloadsForRetry(fileNames).mapNotNull { downloadEntities[it] }.also { entities ->
                jobs = entities.map { launchJob(it, startImmediately = false) }
            }
        }
        if (entities.isEmpty()) return 0
        _verification.update { it - entities.mapTo(HashSet()) { e -> e.fileName } }
        val now = System.currentTimeMillis()
        launchBackground("Storing bulk restart") {
            entities.map { it.fileName }.chunked(500).forEach { historyDao.markRestartedAll(it, DownloadStatus.DOWNLOADING.name, now) }
            downloadProgressTracker.persistCurrentStatuses(entities.map { it.fileName })
        }
        startForegroundService()
        jobs.forEach { it.start() }
        return entities.size
    }

    fun deleteDownload(fileName: String, deleteFile: Boolean = false) {
        userActed(fileName)
        synchronized(startLock) {
            pausingFiles.remove(fileName)
            val transfer = downloadJobs[fileName]
            autoRetries.remove(fileName)
            switchedOnce.remove(fileName)
            _switchedTo.update { it - fileName }
            conditionGate.clear(fileName)
            val entity = downloadEntities.remove(fileName)
            val extracted = extractedFilesMap.remove(fileName) ?: emptyList()
            val debrid = debridTorrents.remove(fileName)
            _verification.update { it - fileName }
            downloadProgressTracker.removeDownload(fileName)
            launchCleanup(fileName, "Removing $fileName", transfer) {
                if (debrid != null) debrid.first.delete(debrid.second)
                else if (entity?.isTorrent == true) torrentDownloadService.cancelDownload(entity, reportStatus = false)
                if (deleteFile && entity != null) downloadFileManager.deleteFileByName(entity, true, extracted)
                historyDao.delete(fileName)
            }
            transfer?.cancel()
        }
    }

    fun cancelAllDownloads() {
        getDownloads().filterNot { it.isFinished }.forEach { cancelDownload(it.fileName) }
    }

    fun getDownloads(): List<DownloadItemModel> = downloadProgressTracker.getDownloads()

    /** The indexed file behind a download in this process (restored history included). */
    fun entityFor(fileName: String): DownloadableFileEntity? = downloadEntities[fileName]

    /** Names on disk for a finished download: the extracted files, or the file itself. */
    fun uploadCandidates(fileName: String): List<String> =
        extractedFilesMap[fileName]?.takeIf { it.isNotEmpty() }
            ?: listOf(FileParsingUtils.decodeUrlEncodedFileName(fileName))

    private suspend fun performTorrentDownload(file: DownloadableFileEntity) {
        Log.d(TAG, "Starting torrent download for ${file.fileName}")
        torrentDownloadService.startDownload(file)

        // Collect just this file's status as a distinct flow instead of polling the full
        // downloads list on every tick — O(1) vs O(n) and no busy-wait sleep.
        val finalStatus = downloadProgressTracker.downloads
            .map { list -> list.find { it.fileName == file.fileName }?.status }
            .distinctUntilChanged()
            .first { it == DownloadStatus.COMPLETED || it == DownloadStatus.FAILED || it == DownloadStatus.STOPPED }

        when (finalStatus) {
            DownloadStatus.FAILED  -> { Log.e(TAG, "Torrent FAILED: ${file.fileName}"); return }
            DownloadStatus.STOPPED -> { Log.i(TAG, "Torrent STOPPED: ${file.fileName}"); return }
            else -> moveTorrentFile(file)
        }
    }

    private suspend fun moveTorrentFile(file: DownloadableFileEntity) {
        moveGate.awaitFree(file.consoleId)
        try {
            val downloadDirUri = downloadFileManager.getDownloadDirectoryUri(file)
            if (downloadDirUri == Uri.EMPTY)
                throw StorageAccessException()

            // Use the info cached at download-start time so this works even if the handle was
            // invalidated (e.g. session stopped during app shutdown before the copy finishes).
            val fileInfo = torrentDownloadService.getFileInfo(file.fileName)
                ?: throw Exception("Could not get torrent file info for ${file.fileName}")
            val relativePath = fileInfo.relativePath
            val expectedSize = fileInfo.expectedSize
            val fileExtension = relativePath.substringAfterLast(".", "")

            val internalFile = File(context.cacheDir, "torrent_data/$relativePath")
            Log.d(TAG, "Internal torrent file: ${internalFile.absolutePath}, exists: ${internalFile.exists()}")

            if (!internalFile.exists())
                throw StorageException("Internal torrent file not found")

            // libtorrent marks a file complete (via fileProgress) after hash-verification,
            // but its disk thread flushes writes asynchronously. Poll until the OS-visible
            // file size matches the torrent metadata size before we copy.
            // Also bail early if the session has been stopped (e.g. app shutdown) — the file
            // will never grow any further and the job should fail fast rather than wait 15s.
            var waitedMs = 0
            while (internalFile.length() < expectedSize && waitedMs < 15_000 && torrentHandleRegistry.isRunning) {
                Log.d(TAG, "Waiting for disk flush for ${file.fileName}: ${internalFile.length()}/$expectedSize bytes")
                delay(500)
                waitedMs += 500
            }
            if (internalFile.length() < expectedSize) {
                // Copying a truncated file to the ROMs folder would mark a broken download as
                // completed (seen with ENOSPC on the cache partition: 0 bytes were "flushed").
                throw StorageException(
                    "Incomplete torrent data for ${file.fileName}: " +
                    "${internalFile.length()}/$expectedSize bytes (disk full or write error)"
                )
            }

            val subPath = downloadFileManager.getSubPath(file)

            if (ArchiveUtils.isExtractable(fileExtension) && settingsRepository.autoUnzip.first()) {
                // Extract directly from cache — skips writing the compressed archive to SAF entirely.
                // Flow: cacheDir/torrent_data/ → extraction_temp/ → SAF destination
                Log.d(TAG, "Extracting torrent archive directly from cache: ${internalFile.name}")
                updateStatus(file.fileName, DownloadStatus.UNZIPPING)
                val extracted = archiveExtractorService.extractArchiveFile(
                    context, internalFile, downloadDirUri, subPath, failOnError = true
                )
                if (extracted.isNotEmpty()) {
                    extractedFilesMap[file.fileName] = extracted
                } else {
                    throw DownloadExtractionException()
                }
            } else {
                // Non-archive or auto-unzip disabled: copy directly from cache to SAF
                val documentFile = downloadFileManager.createDocumentFile(file, downloadDirUri.toString(), subPath)
                    ?: throw StorageException("Failed to create destination file in storage.")
                Log.d(TAG, "Copying torrent file to SAF: ${documentFile.uri}")
                updateStatus(file.fileName, DownloadStatus.COPYING)
                try {
                    context.contentResolver.openOutputStream(documentFile.uri)?.use { out ->
                        BufferedOutputStream(out, Constants.EXTRACTION_BUFFER_SIZE).use { buffOut ->
                            internalFile.inputStream().use { input -> DownloadStreams.copy(input, buffOut, Constants.EXTRACTION_BUFFER_SIZE) {} }
                        }
                    }
                        ?: throw StorageException("Could not open destination output stream")
                } catch (e: java.io.IOException) {
                    throw StorageException("Could not copy downloaded torrent to storage", e)
                }
            }

            // Siblings can share torrent pieces with this file. Keep the native session's
            // backing files until the last tracked sibling releases the handle and its cache.
            torrentDownloadService.finishDownload(file)

            Log.i(TAG, "Torrent processed successfully: ${file.fileName}")
            updateStatus(file.fileName, DownloadStatus.COMPLETED)
            _finished.tryEmit(file.fileName)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error processing torrent file for ${file.fileName}: ${e.message}", e)
            localFailures += file.fileName
            updateStatus(file.fileName, DownloadStatus.FAILED, DownloadFailures.classify(e))
            // Untrack and, if nothing else uses the torrent, release it (deletes the cached data);
            // a retry re-fetches the metadata and starts clean instead of leaving a partial behind.
            runCatching { torrentDownloadService.finishDownload(file) }
        }
    }

    /**
     * Debrid route: the service (TorBox, Real-Debrid) fetches the torrent server-side, then the
     * file comes down over plain HTTP through [performHttpDownload] (same throttling, SAF write
     * and extraction).
     */
    private suspend fun performDebridDownload(file: DownloadableFileEntity, client: DebridClient) {
        val magnet = file.torrentMagnet ?: throw Exception("Missing magnet for ${file.fileName}")
        Log.d(TAG, "Starting ${client.provider.label} download for ${file.fileName}")
        updateStatus(file.fileName, DownloadStatus.QUEUED)

        try {
            performDebridDownloadInner(file, client, magnet)
        } catch (e: Exception) {
            // Leave nothing behind on the account when the debrid route gives up.
            releaseDebrid(file.fileName)
            throw e
        }
    }

    /** Removes the torrent from the debrid account, detached from the (cancellable) download job. */
    private fun releaseDebrid(fileName: String) {
        val (client, id) = debridTorrents.remove(fileName) ?: return
        launchBackground("Removing remote torrent for $fileName") { client.delete(id) }
    }

    private suspend fun performDebridDownloadInner(file: DownloadableFileEntity, client: DebridClient, magnet: String) {
        val label = client.provider.label
        // A previous run that died mid-transfer left the ids in the history: pick the same
        // torrent up again and resume the HTTP transfer from what is already on disk.
        val previous = historyDao.getByFileName(file.fileName)
        val resumed = previous?.debridTorrentId?.takeIf { previous.debridProvider == client.provider.name }?.let { id ->
            runCatching { client.getTorrent(id) }.getOrNull()
                ?.takeIf { it.downloadFinished }
                ?.let { t -> t.files.firstOrNull { it.id == previous.debridFileId }?.let { f -> t to f } }
        }
        if (resumed != null) {
            val (torrent, remote) = resumed
            debridTorrents[file.fileName] = client to torrent.id
            Log.d(TAG, "Resuming $label download of ${file.fileName} (torrent ${torrent.id}, file ${remote.id})")
            updateStatus(file.fileName, DownloadStatus.DOWNLOADING)
            performHttpDownload(file, resumable = true) { client.requestDownload(torrent.id, remote.id) }
            historyDao.setDebrid(file.fileName, null, null, null)
            releaseDebrid(file.fileName)
            return
        }

        val hash = DebridMatcher.infoHashFromMagnet(magnet)
        val cached = hash?.let { client.isCached(it) } == true
        var torrentId = client.createTorrent(magnet)
        debridTorrents[file.fileName] = client to torrentId

        /** Polls until [done] holds; cached torrents get there on the first check. */
        suspend fun await(id: String, done: (DebridTorrent) -> Boolean): DebridTorrent {
            val started = System.currentTimeMillis()
            var torrent = client.getTorrent(id)
            var pollErrors = 0
            while (!done(torrent)) {
                torrent.failure?.let { throw Exception(it) }
                if (System.currentTimeMillis() - started > DEBRID_MAX_WAIT_MS) throw java.net.SocketTimeoutException("Debrid fetch timed out")
                downloadProgressTracker.updateDownloadProgress(file.fileName, torrent.progress, 0f, (torrent.progress * file.fileSize).toLong())
                val elapsed = System.currentTimeMillis() - started
                delay(when { cached || elapsed < 30_000L -> 3_000L; elapsed < 5 * 60_000L -> 10_000L; else -> 30_000L })
                // Services answer 5xx now and then while a torrent is being fetched: keep polling.
                torrent = runCatching { client.getTorrent(id) }.getOrElse { e ->
                    if (e is DebridAuthException || ++pollErrors > 5) throw e
                    Log.w(TAG, "$label poll error (${pollErrors}/5): ${e.message}")
                    torrent
                }
            }
            return torrent
        }
        suspend fun awaitListed(id: String) = await(id) { it.filesKnown || it.downloadFinished }
        suspend fun awaitFinished(id: String) = await(id) { it.downloadFinished }.also { t ->
            Log.d(TAG, "$label torrent ${t.id} '${t.name}' files=${t.files.size}: " + t.files.take(5).joinToString { "${it.id}:${it.name}(${it.size})" })
        }

        var torrent = awaitListed(torrentId)
        var remote = DebridMatcher.pickFile(torrent.files, file.fileName, file.fileSize)
        if (remote == null && torrent.files.size == 1 && torrent.files[0].name.endsWith(".zip", ignoreCase = true)) {
            // TorBox zipped the whole torrent (an earlier add without allow_zip=false); re-add it unzipped.
            Log.w(TAG, "$label holds ${torrent.name} as a single zip; re-adding it unzipped")
            client.delete(torrentId)
            torrentId = client.createTorrent(magnet)
            debridTorrents[file.fileName] = client to torrentId
            torrent = awaitListed(torrentId)
            remote = DebridMatcher.pickFile(torrent.files, file.fileName, file.fileSize)
        }
        if (remote == null) {
            // The service only holds a zip of the whole torrent (it cannot serve single files
            // from it): give the torrent back and fetch this file directly instead.
            Log.w(TAG, "$label lists ${torrent.files.size} file(s) for ${torrent.name} but not ${file.fileName}; falling back to direct torrent download")
            releaseDebrid(file.fileName)
            updateStatus(file.fileName, DownloadStatus.DOWNLOADING)
            performTorrentDownload(file)
            return
        }

        client.selectFile(torrentId, remote.id)
        historyDao.setDebrid(file.fileName, client.provider.name, torrentId, remote.id)
        awaitFinished(torrentId)

        updateStatus(file.fileName, DownloadStatus.DOWNLOADING)
        downloadProgressTracker.updateDownloadProgress(file.fileName, 0f, 0f, 0L)
        // Links expire, so each HTTP attempt asks for a fresh one; attempts resume from the partial file.
        performHttpDownload(file, resumable = true) { client.requestDownload(torrentId, remote.id) }
        historyDao.setDebrid(file.fileName, null, null, null)
        // COMPLETED may already have stopped the foreground service, whose onDestroy cancels every
        // download job: the remote clean-up must not run inside this job.
        releaseDebrid(file.fileName)
    }

    /**
     * [resumable]: keep the partial file when an attempt stops and continue it with a `Range`
     * request next time (debrid links serve ranges; plain HTTP sources are not trusted to).
     */
    private suspend fun performHttpDownload(
        file: DownloadableFileEntity,
        resumable: Boolean = false,
        urlProvider: (suspend () -> String)? = null
    ) {
        // Debrid links change on every request: their partial record belongs to the download, not to one address.
        val trustRecord = urlProvider != null
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                if (attempt > 0) delay(2000L * attempt)
                performHttpDownloadAttempt(file, urlProvider?.invoke() ?: file.downloadUrl, resumable, trustRecord)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: LowStorageException) {
                throw e
            } catch (e: StorageException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1} failed for ${file.fileName}: ${e.message}")
                lastError = e
            }
        }
        // A file of a web source with reserve addresses: the same file there, in their order.
        for (url in if (trustRecord) emptyList() else mirrorsOf(file)) {
            try {
                Log.i(TAG, "Trying ${file.fileName} from a reserve address")
                performHttpDownloadAttempt(file, url, resumable = false)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: LowStorageException) {
                throw e
            } catch (e: StorageException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reserve address failed for ${file.fileName}: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: Exception("Download failed")
    }

    /** The file under the other addresses of the web source it came from (none for torrents, RomM, debrid). */
    private suspend fun mirrorsOf(file: DownloadableFileEntity): List<String> = runCatching {
        if (file.isTorrent) return emptyList()
        val entries = SourcesJson.parseUrlEntries(consoleDao.getConsoleById(file.consoleId)?.urls ?: return emptyList())
        entries.filter { it.enabled && it.mirrors.isNotEmpty() }.firstNotNullOfOrNull { entry ->
            MirrorUrls.alternatives(file.downloadUrl, entry.url, entry.mirrors).takeIf { it.isNotEmpty() }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** [trustRecord]: continue a noted partial whatever address it came from (debrid links change every time). */
    private suspend fun performHttpDownloadAttempt(file: DownloadableFileEntity, downloadUrl: String, resumable: Boolean = false, trustRecord: Boolean = false) {
        val downloadDirUri = downloadFileManager.getDownloadDirectoryUri(file)
        if (downloadDirUri == Uri.EMPTY)
            throw StorageAccessException()
        updateStatus(file.fileName, DownloadStatus.DOWNLOADING)
        parkable.remove(file.fileName)

        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        var documentFile: DocumentFile? = null
        var activeConnection: HttpURLConnection? = null

        try {
            val subPath = downloadFileManager.getSubPath(file)
            // Only a partial file this app wrote (and noted) is continued; see PartialDownloads.
            val resume = resumable && appSettings.resumeDownloads.first()
            // A part written from another source (the download moved) is not continued; a reserve address of the same source is.
            val record = if (resume) partials.get(file.fileName)?.takeIf { r ->
                trustRecord || PartialOwner.sameSource(r.url, downloadUrl, mirrorsOf(file) + file.downloadUrl)
            } else null
            val partial = if (record != null) downloadFileManager.findExistingFile(file, downloadDirUri.toString(), subPath) else null
            val partialBytes = partial?.length() ?: 0L
            // Files served by the RomM library need the account's credentials.
            val auth = if (RommSource.isDownloadFrom(rommClient.configuredBaseUrl(), downloadUrl)) rommClient.downloadHeaders() else emptyMap()
            val headers = if (partialBytes > 0 && record?.validator != null) auth + ("If-Range" to record.validator) else auth
            val connection = downloadHttpClient.createConnection(downloadUrl, rangeStart = partialBytes, headers = headers)
                .also { activeConnection = it }
            inputStream = connection.inputStream

            val startOffset: Long
            val action = ResumePlan.decide(partialBytes, connection.responseCode, connection.getHeaderField("Content-Range"), file.fileSize)
            if (partial != null && action == ResumePlan.Action.APPEND) {
                documentFile = partial
                outputStream = downloadFileManager.getAppendOutputStream(partial)
                    ?: throw StorageException("Failed to open output stream for ${partial.uri}")
                startOffset = partialBytes
                Log.d(TAG, "Resuming ${file.fileName} from $partialBytes bytes")
            } else {
                documentFile = downloadFileManager.createDocumentFile(file, downloadDirUri.toString(), subPath)
                    ?: throw StorageException("Failed to create file in storage.")
                outputStream = downloadFileManager.getOutputStream(documentFile)
                    ?: throw StorageException("Failed to open output stream for ${documentFile.uri}")
                startOffset = 0L
                if (resume) partials.put(file.fileName, PartialDownloads.Record(downloadUrl,
                    ResumePlan.validator(connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))))
            }
            // A hold may park this transfer only when its part is kept and continued (7.5 power rules, Pause all).
            if (HoldParking.webResumable(resume, partials.get(file.fileName) != null, connection.getHeaderField("Accept-Ranges"),
                    partialBytes, partial != null && action == ResumePlan.Action.APPEND)) parkable += file.fileName
            val contentLength = connection.contentLengthLong.let { if (it > 0) it + startOffset else it }
            if (file.fileSize <= 0) downloadProgressTracker.learnFileSize(file.fileName, contentLength)
            // A server that announces the hash of the whole body lets the finished file be checked.
            if (startOffset == 0L) {
                (Checksums.fromContentMd5(connection.getHeaderField("Content-MD5")) ?: Checksums.fromDigestHeader(connection.getHeaderField("Digest")))
                    ?.let { headerHashes[file.fileName] = it } ?: headerHashes.remove(file.fileName)
            }

            streamWithProgress(inputStream, outputStream, file, contentLength, startOffset)
            // SAF providers can finalize a write only on close. Extraction/checksums must see a
            // finished file, and a canceled transfer must never run the completion steps.
            currentCoroutineContext().ensureActive()
            try { outputStream.close() } catch (e: java.io.IOException) { throw StorageException("Could not finish ${file.fileName}: ${e.message}", e) }
            outputStream = null
            currentCoroutineContext().ensureActive()
            partials.remove(file.fileName)
            handlePostDownload(file, documentFile, subPath)

        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!resumable) documentFile?.let { downloadFileManager.deleteFile(it) }
            updateStatus(file.fileName, DownloadStatus.STOPPED)
            throw e
        } catch (e: LowStorageException) {
            // Out of room: keep what is there (when resuming is on) so *Retry* carries on from it.
            if (!resumable || partials.get(file.fileName) == null) { documentFile?.let { downloadFileManager.deleteFile(it) }; partials.remove(file.fileName) }
            throw e
        } catch (e: Exception) {
            if (!resumable) documentFile?.let { downloadFileManager.deleteFile(it) }
            updateStatus(file.fileName, DownloadStatus.FAILED, DownloadFailures.classify(e))
            throw e
        } finally {
            parkable.remove(file.fileName)
            // Close independently: one provider error must not leak the other descriptor or
            // replace the CancellationException which makes a pause land on PAUSED.
            try { inputStream?.close() } catch (e: java.io.IOException) { Log.w(TAG, "Could not close input for ${file.fileName}: ${e.message}") }
            try { outputStream?.close() } catch (e: java.io.IOException) { Log.w(TAG, "Could not close output for ${file.fileName}: ${e.message}") }
            try { activeConnection?.disconnect() } catch (e: Exception) { Log.w(TAG, "Could not disconnect ${file.fileName}: ${e.message}") }
        }
    }

    private suspend fun streamWithProgress(
        input: InputStream,
        output: OutputStream,
        file: DownloadableFileEntity,
        contentLength: Long,
        startOffset: Long = 0L
    ) {
        var sinceSpaceCheck = 0L
        var downloaded = startOffset
        val startTime = System.nanoTime() / 1_000_000L
        var lastUpdateTime = startTime
        var lastDownloaded = startOffset

        DownloadStreams.copy(input, output, Constants.BUFFER_SIZE) { bytesRead ->
            downloaded += bytesRead
            // One limit for all downloads together (and it follows the setting while downloading).
            bandwidthLimiter.acquire(bytesRead)
            // Every 32 MB: stop (keeping the part) when free space drops below the limit of Settings.
            sinceSpaceCheck += bytesRead
            if (sinceSpaceCheck >= 32L * 1024 * 1024) {
                sinceSpaceCheck = 0
                if (downloadGate.lowOnSpace()) throw LowStorageException(file.fileName)
            }

            val now = System.nanoTime() / 1_000_000L

            val progress = if (contentLength > 0)
                ArchiveExtractionUtils.calculateProgress(downloaded, contentLength) else 0f

            if (downloadProgressTracker.shouldUpdateProgress(progress, lastUpdateTime, now)) {
                val elapsed = (now - lastUpdateTime) / 1000f
                val speedMBs = downloadSpeedController.calculateSpeed(downloaded - lastDownloaded, elapsed)
                downloadProgressTracker.updateDownloadProgress(file.fileName, progress, speedMBs, downloaded)
                lastUpdateTime = now
                lastDownloaded = downloaded
            }
        }
        transferSamples[file.fileName] = (downloaded - startOffset) to (System.nanoTime() / 1_000_000L - startTime)
    }

    private suspend fun handlePostDownload(
        file: DownloadableFileEntity,
        documentFile: DocumentFile,
        subPath: String
    ) {
        currentCoroutineContext().ensureActive()
        if (!ArchiveUtils.isExtractable(file.fileExtension) || !settingsRepository.autoUnzip.first()) {
            updateStatus(file.fileName, DownloadStatus.COMPLETED)
            _finished.tryEmit(file.fileName)
            verifyInBackground(file, documentFile)
            return
        }

        updateStatus(file.fileName, DownloadStatus.UNZIPPING)
        try {
            val extracted = archiveExtractorService.extractArchive(
                context, documentFile.uri, downloadFileManager.getDownloadDirectoryUri(file), subPath, failOnError = true)
            if (extracted.isNotEmpty()) {
                downloadFileManager.deleteFile(documentFile)
                extractedFilesMap[file.fileName] = extracted
            } else {
                throw DownloadExtractionException()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for ${file.fileName}: ${e.message}")
            throw if (e is StorageException || e is DownloadExtractionException || e is SecurityException) e else DownloadExtractionException(e)
        }
        currentCoroutineContext().ensureActive()
        updateStatus(file.fileName, DownloadStatus.COMPLETED)
        _finished.tryEmit(file.fileName)
    }

    /**
     * Compares the finished file with the hash its source published (RomM lists one per game; some
     * servers send `Content-MD5` / `Digest`). Nothing to compare = nothing shown. Runs on its own so
     * the download is already usable while a big file is read through.
     */
    private fun verifyInBackground(file: DownloadableFileEntity, documentFile: DocumentFile) {
        val expected: ExpectedHash = Checksums.parse(file.expectedHash) ?: Checksums.parse(headerHashes.remove(file.fileName))
            ?: run { checkAgainstDat(file, documentFile); return }
        launchBackground("Checking ${file.fileName}") {
            verificationSlots.withPermit {
                _verification.update { it + (file.fileName to VerifyState.CHECKING) }
                val state = try {
                    val actual = context.contentResolver.openInputStream(documentFile.uri)?.use { Checksums.hexOf(it, expected.algo) }
                    if (actual != null && Checksums.matches(expected, actual)) VerifyState.VERIFIED else VerifyState.MISMATCH
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not check ${file.fileName}: ${e.message}")
                    null
                }
                _verification.update { if (state == null) it - file.fileName else it + (file.fileName to state) }
                if (state == VerifyState.MISMATCH) Log.w(TAG, "Checksum of ${file.fileName} differs from the one the source published")
            }
        }
    }

    /**
     * No hash from the source: when the user imported a DAT for this console, the finished file is
     * looked up in it (see [DatService.checkDownloaded]). No DAT, or a format DATs do not cover = nothing shown.
     */
    private fun checkAgainstDat(file: DownloadableFileEntity, documentFile: DocumentFile) {
        launchBackground("Checking ${file.fileName} against the DAT") {
            verificationSlots.withPermit {
                val check = try {
                    datService.checkDownloaded(file.consoleId, documentFile.name ?: file.fileName, documentFile.uri, documentFile.length(), documentFile.lastModified())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not check ${file.fileName} against the DAT: ${e.message}")
                    null
                } ?: return@withPermit
                val state = if (check.status == DatStatus.UNKNOWN) VerifyState.DAT_UNKNOWN else VerifyState.DAT_OK
                _verification.update { it + (file.fileName to state) }
            }
        }
    }

    /**
     * An intent that opens the finished download in whichever app handles the file (an emulator,
     * a file manager), or null when the file cannot be found. For a download that was unpacked, the
     * game file is picked from what was extracted (a sheet or image rather than its tracks).
     */
    suspend fun openIntentFor(fileName: String): Intent? {
        val entity = downloadEntities[fileName] ?: return null
        val dirUri = downloadFileManager.getDownloadDirectoryUri(entity)
        if (dirUri == Uri.EMPTY) return null
        val dir = StorageHelper.createDirectory(context, dirUri.toString(), downloadFileManager.getSubPath(entity)) ?: return null
        val priority = listOf("m3u", "cue", "gdi", "chd", "iso", "pbp", "ccd", "mds")
        val names = uploadCandidates(fileName).sortedBy { n -> priority.indexOf(n.substringAfterLast('.').lowercase()).let { if (it < 0) priority.size else it } }
        val doc = names.firstNotNullOfOrNull { dir.findFile(it)?.takeIf { f -> f.isFile } } ?: return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(doc.uri, "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private suspend fun updateStatus(fileName: String, status: DownloadStatus, failure: DownloadFailure? = null) {
        val owner = currentCoroutineContext()[Job]
        synchronized(startLock) {
            if (downloadJobs[fileName] === owner) downloadProgressTracker.updateDownloadStatus(fileName, status, failure = failure)
        }
    }

    /**
     * Starts the foreground service if it is not running; it stops itself once nothing is active
     * (see [DownloadForegroundService]). Android 12+ refuses this from the background; the download
     * then runs without the notification instead of taking the app down.
     */
    @Volatile private var lastServiceRequest = 0L

    private fun startForegroundService() {
        if (DownloadForegroundService.running) return
        // A retry of many rows asks many times before the service is up; one request is enough.
        val now = SystemClock.elapsedRealtime()
        if (now - lastServiceRequest < 5_000) return
        lastServiceRequest = now
        try {
            context.startForegroundService(Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_START_SERVICE
            })
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service not started: ${e.message}")
        }
    }

    /** True while [fileName] is queued, downloading, copying or unpacking. */
    fun isActive(fileName: String): Boolean = downloadProgressTracker.isActive(fileName)

    /** True while any download is queued, running, copying or unpacking. */
    fun hasActiveDownloads(): Boolean = downloadProgressTracker.hasActiveDownloads()

    /** [hasActiveDownloads] as a flow, for the foreground service. */
    val anyActive: kotlinx.coroutines.flow.Flow<Boolean> = downloadProgressTracker.downloads.map { downloadProgressTracker.hasActiveDownloads() }
}
