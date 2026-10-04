package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ArchiveUtils
import com.cortinadev.dogmatix.util.ArchiveExtractionUtils
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.DebridMatcher
import com.cortinadev.dogmatix.util.Checksums
import com.cortinadev.dogmatix.util.ExpectedHash
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.VerifyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

private const val TAG = "DownloadService"
/** Upper bound for an uncached torrent to be fetched by the debrid service before we give up. */
private const val DEBRID_MAX_WAIT_MS = 6 * 60 * 60 * 1000L

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
    private val consoleDao: com.cortinadev.dogmatix.data.local.dao.ConsoleDao
) {
    val downloads: StateFlow<List<DownloadItemModel>> = downloadProgressTracker.downloads

    /**
     * File names whose download is really finished and on disk (after copy / extraction). The
     * torrent bridge flips a row to COMPLETED as soon as libtorrent is done, before the file is
     * moved into place, so status watchers cannot tell "done" from "about to be copied".
     */
    private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val finished: SharedFlow<String> = _finished.asSharedFlow()

    private val _waiting = MutableStateFlow<Set<String>>(emptySet())
    /** Downloads held back by the schedule (Wi-Fi / charger / night); see [DownloadGate]. */
    val waitingFiles: StateFlow<Set<String>> = _waiting.asStateFlow()
    val gate: DownloadGate get() = downloadGate

    private val _verification = MutableStateFlow<Map<String, VerifyState>>(emptyMap())
    /** Result of checking a finished download against the hash its source published. */
    val verification: StateFlow<Map<String, VerifyState>> = _verification.asStateFlow()
    /** Hash the server announced in the response headers of the transfer in progress, per file. */
    private val headerHashes = ConcurrentHashMap<String, String>()

    private val downloadJobs = ConcurrentHashMap<String, Job>()
    /** The download slots and the order of what waits for one (the user can reorder it). */
    private val queue = com.cortinadev.dogmatix.util.DownloadQueue(3)

    /** Downloads waiting for a free slot, first to start first. */
    val queued: StateFlow<List<String>> = queue.waiting

    fun moveUp(fileName: String) = queue.moveUp(fileName)
    fun moveDown(fileName: String) = queue.moveDown(fileName)
    fun moveToFront(fileName: String) = queue.moveToFront(fileName)
    private val downloadEntities = ConcurrentHashMap<String, DownloadableFileEntity>()
    private val extractedFilesMap = ConcurrentHashMap<String, List<String>>()
    /** Debrid client + torrent id per file being fetched through the debrid route (see [performDebridDownload]). */
    private val debridTorrents = ConcurrentHashMap<String, Pair<DebridClient, String>>()
    /** Files whose job is being cancelled by a pause (they land on PAUSED instead of STOPPED). */
    private val pausingFiles = ConcurrentHashMap.newKeySet<String>()

    // Single supervised scope for all internal coroutines — tied to this singleton's lifetime
    // so jobs are not orphaned if the service is destroyed.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        serviceScope.launch {
            settingsRepository.concurrentDownloads.collect { max -> queue.setSlots(max) }
        }
        serviceScope.launch { restoreHistory() }
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
            downloadProgressTracker.restore(items)
            items.filter { it.status == DownloadStatus.STOPPED }.forEach { item ->
                val row = rows.first { it.fileName == item.fileName }
                if (row.status != item.status.name || row.finishedAt != item.finishedAt) {
                    historyDao.updateStatus(item.fileName, item.status.name, item.finishedAt)
                }
            }
            Log.d(TAG, "Restored ${rows.size} download(s) from history")
        } catch (e: Exception) {
            Log.e(TAG, "Could not restore download history: ${e.message}")
        }
    }

    private val startLock = Any()

    fun startDownload(file: DownloadableFileEntity) = startDownloads(listOf(file))

    /**
     * Queues [files] in one go: one list update, one history write and one service start, then a
     * job per file. A bulk start of hundreds of games used to do all of that once per game on the
     * UI thread, long enough for Android to report the app as not responding. Files already
     * queued or running are skipped (repeated taps, or a bulk start racing a tap).
     */
    fun startDownloads(files: List<DownloadableFileEntity>) {
        val fresh = synchronized(startLock) {
            val items = files.distinctBy { it.fileName }
                .filterNot { downloadProgressTracker.isActive(it.fileName) }
                .map { it to downloadFileManager.createDownloadItem(it) }
            downloadProgressTracker.addDownloads(items.map { it.second })
            items
        }
        if (fresh.isEmpty()) return
        fresh.forEach { (file, _) -> downloadEntities[file.fileName] = file }
        serviceScope.launch { historyDao.upsertAll(fresh.map { (file, item) -> DownloadHistoryEntity.from(file, item) }) }
        startForegroundService()
        fresh.forEach { (file, _) -> launchJob(file) }
    }

    private fun launchJob(file: DownloadableFileEntity) {
        // Registered before it starts, so a download that ends at once cannot leave a stale entry.
        val job = serviceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                withSlot(file.fileName) {
                    awaitSchedule(file.fileName)
                    // Brief delay to allow the foreground service and initial UI state to settle
                    // before network/torrent activity begins.
                    delay(1000L)
                    perform(file)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                updateStatus(file.fileName, if (pausingFiles.remove(file.fileName)) DownloadStatus.PAUSED else DownloadStatus.STOPPED)
                throw e
            } catch (e: LowStorageException) {
                updateStatus(file.fileName, DownloadStatus.STOPPED)
                notifyLowStorage()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${file.fileName}: ${e.message}")
                updateStatus(file.fileName, DownloadStatus.FAILED)
            } finally {
                downloadJobs.remove(file.fileName, coroutineContext[kotlinx.coroutines.Job]!!)
            }
        }
        downloadJobs[file.fileName] = job
        job.start()
    }

    private suspend fun withSlot(fileName: String, block: suspend () -> Unit) {
        queue.acquire(fileName)
        try { block() } finally { queue.release() }
    }

    /** Tells the user (once per minute at most) that downloads stopped for lack of space. */
    @Volatile private var lastLowStorageNotice = 0L
    private fun notifyLowStorage() {
        val now = System.currentTimeMillis()
        if (now - lastLowStorageNotice < 60_000) return
        lastLowStorageNotice = now
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = androidx.core.app.NotificationCompat.Builder(context, com.cortinadev.dogmatix.DogmatixApplication.SCAN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle(context.getString(R.string.storage_low_title))
            .setContentText(context.getString(R.string.storage_low_text))
            .setAutoCancel(true)
            .build()
        context.getSystemService(android.app.NotificationManager::class.java).notify(4221, notification)
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
        val debrid = if (file.isTorrent) debridClient(settingsRepository.debridProvider.first()) else null
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
        downloadJobs.remove(fileName)?.cancel()
        val entity = downloadEntities[fileName] ?: return
        serviceScope.launch {
            val debrid = debridTorrents.remove(fileName)
            when {
                debrid != null -> {
                    updateStatus(fileName, DownloadStatus.STOPPED)
                    historyDao.setDebrid(fileName, null, null, null)
                    debrid.first.delete(debrid.second)
                }
                entity.isTorrent -> torrentDownloadService.cancelDownload(entity)
                else -> updateStatus(fileName, DownloadStatus.STOPPED)
            }
        }
    }

    /** Parks a torrent download: cache and handle survive, so retry resumes from disk. */
    fun pauseDownload(fileName: String) {
        val entity = downloadEntities[fileName] ?: return
        if (!entity.isTorrent || !downloadProgressTracker.isActive(fileName)) return
        pausingFiles.add(fileName)
        downloadJobs.remove(fileName)?.cancel()
        serviceScope.launch {
            torrentDownloadService.pauseDownload(entity)
            updateStatus(fileName, DownloadStatus.PAUSED)
        }
    }

    fun retryDownload(fileName: String) {
        val entity = synchronized(startLock) {
            if (!downloadProgressTracker.canRetryDownload(fileName)) return
            val entity = downloadEntities[fileName] ?: return
            // Reset the existing list entry in place — calling startDownload would add a duplicate.
            downloadProgressTracker.resetDownloadForRetry(fileName)
            entity
        }
        _verification.update { it - fileName }
        serviceScope.launch {
            historyDao.markRestarted(fileName, DownloadStatus.DOWNLOADING.name, System.currentTimeMillis())
        }
        startForegroundService()
        launchJob(entity)
    }

    fun deleteDownload(fileName: String, deleteFile: Boolean = false) {
        downloadJobs.remove(fileName)?.cancel()
        val entity = downloadEntities.remove(fileName)
        val extracted = extractedFilesMap.remove(fileName) ?: emptyList()
        val debrid = debridTorrents.remove(fileName)
        _verification.update { it - fileName }
        serviceScope.launch {
            if (debrid != null) debrid.first.delete(debrid.second)
            else if (entity?.isTorrent == true) torrentDownloadService.cancelDownload(entity)
            if (deleteFile && entity != null) downloadFileManager.deleteFileByName(entity, true, extracted)
            historyDao.delete(fileName)
        }
        downloadProgressTracker.removeDownload(fileName)
    }

    fun cancelAllDownloads() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
    }

    fun getDownloads(): List<DownloadItemModel> = downloadProgressTracker.getDownloads()

    /** The indexed file behind a download in this process (restored history included). */
    fun entityFor(fileName: String): DownloadableFileEntity? = downloadEntities[fileName]

    /** Names on disk for a finished download: the extracted files, or the file itself. */
    fun uploadCandidates(fileName: String): List<String> =
        extractedFilesMap[fileName]?.takeIf { it.isNotEmpty() }
            ?: listOf(com.cortinadev.dogmatix.util.FileParsingUtils.decodeUrlEncodedFileName(fileName))

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
        try {
            val downloadDirUri = downloadFileManager.getDownloadDirectoryUri(file)
            if (downloadDirUri == android.net.Uri.EMPTY)
                throw Exception("Download directory not configured or no longer accessible.")

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
                throw Exception("Internal torrent file not found at ${internalFile.absolutePath}")

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
                throw Exception(
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
                    context, internalFile, downloadDirUri, subPath
                )
                if (extracted.isNotEmpty()) {
                    extractedFilesMap[file.fileName] = extracted
                } else {
                    Log.w(TAG, "Extraction produced no files for ${file.fileName}")
                }
            } else {
                // Non-archive or auto-unzip disabled: copy directly from cache to SAF
                val documentFile = downloadFileManager.createDocumentFile(file, downloadDirUri.toString(), subPath)
                    ?: throw Exception("Failed to create destination file in storage.")
                Log.d(TAG, "Copying torrent file to SAF: ${documentFile.uri}")
                updateStatus(file.fileName, DownloadStatus.COPYING)
                context.contentResolver.openOutputStream(documentFile.uri)?.use { out ->
                    BufferedOutputStream(out, Constants.EXTRACTION_BUFFER_SIZE).use { buffOut ->
                        internalFile.inputStream().use { it.copyTo(buffOut, Constants.EXTRACTION_BUFFER_SIZE) }
                    }
                } ?: throw Exception("Could not open output stream for ${documentFile.uri}")
            }

            // Clean up cache
            internalFile.delete()
            internalFile.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()

            // Release handle only when no sibling files still downloading from same torrent
            torrentDownloadService.finishDownload(file)

            Log.i(TAG, "Torrent processed successfully: ${file.fileName}")
            updateStatus(file.fileName, DownloadStatus.COMPLETED)
            _finished.tryEmit(file.fileName)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing torrent file for ${file.fileName}: ${e.message}", e)
            updateStatus(file.fileName, DownloadStatus.FAILED)
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
        serviceScope.launch { client.delete(id) }
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
                if (System.currentTimeMillis() - started > DEBRID_MAX_WAIT_MS) throw Exception("$label did not finish fetching ${file.fileName} in time")
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
        urlProvider: suspend () -> String = { file.downloadUrl }
    ) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                if (attempt > 0) delay(2000L * attempt)
                performHttpDownloadAttempt(file, urlProvider(), resumable)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: LowStorageException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1} failed for ${file.fileName}: ${e.message}")
                lastError = e
            }
        }
        // A file of a web source with reserve addresses: the same file there, in their order.
        for (url in mirrorsOf(file)) {
            try {
                Log.i(TAG, "Trying ${file.fileName} from a reserve address")
                performHttpDownloadAttempt(file, url, resumable = false)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: LowStorageException) {
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
        val entries = com.cortinadev.dogmatix.util.SourcesJson.parseUrlEntries(consoleDao.getConsoleById(file.consoleId)?.urls ?: return emptyList())
        entries.filter { it.enabled && it.mirrors.isNotEmpty() }.firstNotNullOfOrNull { entry ->
            com.cortinadev.dogmatix.util.MirrorUrls.alternatives(file.downloadUrl, entry.url, entry.mirrors).takeIf { it.isNotEmpty() }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private suspend fun performHttpDownloadAttempt(file: DownloadableFileEntity, downloadUrl: String, resumable: Boolean = false) {
        val downloadDirUri = downloadFileManager.getDownloadDirectoryUri(file)
        if (downloadDirUri == android.net.Uri.EMPTY)
            throw Exception("Download directory not configured or no longer accessible.")

        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        var documentFile: DocumentFile? = null

        try {
            val subPath = downloadFileManager.getSubPath(file)
            val partial = if (resumable) downloadFileManager.findExistingFile(file, downloadDirUri.toString(), subPath) else null
            val partialBytes = partial?.length() ?: 0L
            // Files served by the RomM library need the account's credentials.
            val headers = if (RommSource.isDownloadFrom(rommClient.configuredBaseUrl(), downloadUrl)) rommClient.downloadHeaders() else emptyMap()
            val connection = downloadHttpClient.createConnection(downloadUrl, rangeStart = partialBytes, headers = headers)
            inputStream = connection.inputStream

            val startOffset: Long
            if (partial != null && partialBytes > 0L && connection.responseCode == java.net.HttpURLConnection.HTTP_PARTIAL) {
                documentFile = partial
                outputStream = downloadFileManager.getAppendOutputStream(partial)
                    ?: throw Exception("Failed to open output stream for ${partial.uri}")
                startOffset = partialBytes
                Log.d(TAG, "Resuming ${file.fileName} from $partialBytes bytes")
            } else {
                documentFile = downloadFileManager.createDocumentFile(file, downloadDirUri.toString(), subPath)
                    ?: throw Exception("Failed to create file in storage.")
                outputStream = downloadFileManager.getOutputStream(documentFile)
                    ?: throw Exception("Failed to open output stream for ${documentFile.uri}")
                startOffset = 0L
            }
            val contentLength = connection.contentLengthLong.let { if (it > 0) it + startOffset else it }
            if (file.fileSize <= 0) downloadProgressTracker.learnFileSize(file.fileName, contentLength)
            // A server that announces the hash of the whole body lets the finished file be checked.
            if (startOffset == 0L) {
                (Checksums.fromContentMd5(connection.getHeaderField("Content-MD5")) ?: Checksums.fromDigestHeader(connection.getHeaderField("Digest")))
                    ?.let { headerHashes[file.fileName] = it } ?: headerHashes.remove(file.fileName)
            }

            streamWithProgress(inputStream, outputStream, file, contentLength, startOffset)
            handlePostDownload(file, documentFile, subPath)

        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!resumable) documentFile?.let { downloadFileManager.deleteFile(it) }
            updateStatus(file.fileName, DownloadStatus.STOPPED)
            throw e
        } catch (e: Exception) {
            if (!resumable) documentFile?.let { downloadFileManager.deleteFile(it) }
            updateStatus(file.fileName, DownloadStatus.FAILED)
            throw e
        } finally {
            inputStream?.close()
            outputStream?.close()
        }
    }

    private suspend fun streamWithProgress(
        input: InputStream,
        output: OutputStream,
        file: DownloadableFileEntity,
        contentLength: Long,
        startOffset: Long = 0L
    ) {
        val buffer = ByteArray(Constants.BUFFER_SIZE)
        var sinceSpaceCheck = 0L
        var downloaded = startOffset
        val startTime = System.currentTimeMillis()
        var lastUpdateTime = startTime
        var lastDownloaded = startOffset

        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            if (downloadJobs[file.fileName]?.isCancelled == true) {
                updateStatus(file.fileName, DownloadStatus.STOPPED)
                return
            }

            output.write(buffer, 0, bytesRead)
            downloaded += bytesRead
            // One limit for all downloads together (and it follows the setting while downloading).
            bandwidthLimiter.acquire(bytesRead)
            // Every 32 MB: stop (keeping the part) when free space drops below the limit of Settings.
            sinceSpaceCheck += bytesRead
            if (sinceSpaceCheck >= 32L * 1024 * 1024) {
                sinceSpaceCheck = 0
                if (downloadGate.lowOnSpace()) throw LowStorageException(file.fileName)
            }

            val now = System.currentTimeMillis()

            val progress = if (contentLength > 0)
                ArchiveExtractionUtils.calculateProgress(downloaded, contentLength) else 0f

            if (downloadProgressTracker.shouldUpdateProgress(progress, lastUpdateTime, now)) {
                val elapsed = (now - lastUpdateTime) / 1000f
                val speedMBs = downloadSpeedController.calculateSpeed(downloaded - lastDownloaded, elapsed)
                    .takeIf { it > 0 }
                    ?: downloadSpeedController.calculateSpeed(downloaded, (now - startTime) / 1000f)
                downloadProgressTracker.updateDownloadProgress(file.fileName, progress, speedMBs, downloaded)
                lastUpdateTime = now
                lastDownloaded = downloaded
            }
        }
    }

    private suspend fun handlePostDownload(
        file: DownloadableFileEntity,
        documentFile: DocumentFile,
        subPath: String
    ) {
        if (!ArchiveUtils.isExtractable(file.fileExtension) || !settingsRepository.autoUnzip.first()) {
            updateStatus(file.fileName, DownloadStatus.COMPLETED)
            _finished.tryEmit(file.fileName)
            verifyInBackground(file, documentFile)
            return
        }

        updateStatus(file.fileName, DownloadStatus.UNZIPPING)
        try {
            val extracted = archiveExtractorService.extractArchive(
                context, documentFile.uri, downloadFileManager.getDownloadDirectoryUri(file), subPath)
            if (extracted.isNotEmpty()) {
                downloadFileManager.deleteFile(documentFile)
                extractedFilesMap[file.fileName] = extracted
            } else {
                Log.w(TAG, "Extraction produced no files for ${file.fileName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for ${file.fileName}: ${e.message}")
        }
        updateStatus(file.fileName, DownloadStatus.COMPLETED)
        _finished.tryEmit(file.fileName)
    }

    /**
     * Compares the finished file with the hash its source published (RomM lists one per game; some
     * servers send `Content-MD5` / `Digest`). Nothing to compare = nothing shown. Runs on its own so
     * the download is already usable while a big file is read through.
     */
    private fun verifyInBackground(file: DownloadableFileEntity, documentFile: DocumentFile) {
        val expected: ExpectedHash = Checksums.parse(file.expectedHash) ?: Checksums.parse(headerHashes.remove(file.fileName)) ?: return
        serviceScope.launch {
            _verification.update { it + (file.fileName to VerifyState.CHECKING) }
            val state = runCatching {
                val actual = context.contentResolver.openInputStream(documentFile.uri)?.use { Checksums.hexOf(it, expected.algo) }
                if (actual != null && Checksums.matches(expected, actual)) VerifyState.VERIFIED else VerifyState.MISMATCH
            }.getOrElse { e ->
                Log.w(TAG, "Could not check ${file.fileName}: ${e.message}")
                null
            }
            _verification.update { if (state == null) it - file.fileName else it + (file.fileName to state) }
            if (state == VerifyState.MISMATCH) Log.w(TAG, "Checksum of ${file.fileName} differs from the one the source published")
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
        if (dirUri == android.net.Uri.EMPTY) return null
        val dir = StorageHelper.createDirectory(context, dirUri.toString(), downloadFileManager.getSubPath(entity)) ?: return null
        val priority = listOf("m3u", "cue", "gdi", "chd", "iso", "pbp", "ccd", "mds")
        val names = uploadCandidates(fileName).sortedBy { n -> priority.indexOf(n.substringAfterLast('.').lowercase()).let { if (it < 0) priority.size else it } }
        val doc = names.firstNotNullOfOrNull { dir.findFile(it)?.takeIf { f -> f.isFile } } ?: return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(doc.uri, "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private suspend fun updateStatus(fileName: String, status: DownloadStatus) =
        downloadProgressTracker.updateDownloadStatus(fileName, status)

    /**
     * Starts the foreground service if it is not running; it stops itself once nothing is active
     * (see [DownloadForegroundService]). Android 12+ refuses this from the background; the download
     * then runs without the notification instead of taking the app down.
     */
    @Volatile private var lastServiceRequest = 0L

    private fun startForegroundService() {
        if (DownloadForegroundService.running) return
        // A retry of many rows asks many times before the service is up; one request is enough.
        val now = android.os.SystemClock.elapsedRealtime()
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

    /** True while any download is queued, running, copying or unpacking. */
    fun hasActiveDownloads(): Boolean = downloadProgressTracker.hasActiveDownloads()

    /** [hasActiveDownloads] as a flow, for the foreground service. */
    val anyActive: kotlinx.coroutines.flow.Flow<Boolean> = downloadProgressTracker.downloads.map { downloadProgressTracker.hasActiveDownloads() }
}
