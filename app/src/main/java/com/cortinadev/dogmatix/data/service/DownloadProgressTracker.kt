package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.ProgressBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadProgressTracker @Inject constructor(
    private val historyDao: DownloadHistoryDao
) {

    private val _downloads = MutableStateFlow<List<DownloadItemModel>>(emptyList())
    val downloads: StateFlow<List<DownloadItemModel>> = _downloads

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Pending progress and the list publication form one operation: an older flush must not
    // overtake a completion or retry and restore stale bytes afterward.
    private val progressLock = Any()

    // One writer keeps rapid COPYING -> COMPLETED transitions in order. Launching an IO
    // coroutine for every row could write an older status last and exhaust the IO pool in bulk.
    private val statusNames = ConcurrentHashMap.newKeySet<String>()
    private val statusWrites = Channel<Unit>(Channel.CONFLATED)

    init {
        persistScope.launch {
            for (signal in statusWrites) {
                val names = statusNames.toList().filter { statusNames.remove(it) }
                val current = _downloads.value.associateBy { it.fileName }
                for (name in names) {
                    val item = current[name] ?: continue
                    try {
                        historyDao.updateStatus(name, item.status.name, item.finishedAt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("DownloadProgressTracker", "Could not persist status for $name: ${e.message}")
                    }
                }
            }
        }
    }

    /** Seeds the list with entries persisted from previous runs (see [DownloadService]). */
    fun restore(items: List<DownloadItemModel>) {
        _downloads.update { current ->
            val known = current.map { it.fileName }.toSet()
            items.filter { it.fileName !in known } + current
        }
    }

    /** [allowedFrom] atomically prevents stale native observations from replacing worker states. */
    fun updateDownloadStatus(fileName: String, status: DownloadStatus, allowedFrom: Set<DownloadStatus>? = null): Unit = synchronized(progressLock) {
        var changed: DownloadItemModel? = null
        // Progress still waiting for the next batch lands together with the new status.
        val late = pending[fileName]
        _downloads.update { list ->
            changed = null
            list.map { item ->
                if (item.fileName != fileName || (allowedFrom != null && item.status !in allowedFrom)) item
                else {
                    val base = late?.let { item.copy(progress = it.progress, downloadSpeed = it.speed, downloadedBytes = it.downloadedBytes) } ?: item
                    val updated = base.copy(status = status)
                    val finished = if (updated.isFinished) item.finishedAt ?: System.currentTimeMillis() else null
                    updated.copy(finishedAt = finished).also { changed = it }
                }
            }
        }
        changed?.let {
            pending.remove(fileName)
            persistStatus(it)
        }
    }

    private fun persistStatus(item: DownloadItemModel) {
        persistCurrentStatuses(listOf(item.fileName))
    }

    /** Reconcile statuses changed while a new batch's initial history rows were being inserted. */
    fun persistCurrentStatuses(fileNames: Collection<String>) {
        if (fileNames.isEmpty()) return
        statusNames.addAll(fileNames)
        statusWrites.trySend(Unit)
    }

    private val lastUpdateTimes = ConcurrentHashMap<String, Long>()

    /** Progress waiting for the next list update (see [ProgressBatch]). */
    private val pending = ConcurrentHashMap<String, ProgressBatch.Progress>()
    private val flushScheduled = AtomicBoolean(false)

    fun updateDownloadProgress(fileName: String, progress: Float, speed: Float, downloadedBytes: Long): Unit = synchronized(progressLock) {
        val now = System.currentTimeMillis()
        val lastUpdate = lastUpdateTimes[fileName] ?: 0L

        if (shouldUpdateProgress(progress, lastUpdate, now)) {
            lastUpdateTimes[fileName] = now
            pending[fileName] = ProgressBatch.Progress(progress, speed, downloadedBytes)
            when {
                // A finished transfer shows 100% at once.
                progress >= Constants.PROGRESS_COMPLETE -> flushProgress()
                flushScheduled.compareAndSet(false, true) -> persistScope.launch {
                    delay(Constants.PROGRESS_BATCH_MS)
                    flushScheduled.set(false)
                    flushProgress()
                }
            }
        }
    }

    /** Applies all gathered progress in one list update. */
    fun flushProgress(): Unit = synchronized(progressLock) {
        if (pending.isEmpty()) return
        val batch = HashMap<String, ProgressBatch.Progress>()
        for (name in pending.keys.toList()) pending.remove(name)?.let { batch[name] = it }
        if (batch.isNotEmpty()) _downloads.update { ProgressBatch.apply(it, batch) }
    }

    /** A download started without a known size (a shared link) takes the size the server announces. */
    fun learnFileSize(fileName: String, size: Long) {
        if (size <= 0) return
        var learned = false
        _downloads.update { list ->
            list.map { if (it.fileName == fileName && it.fileSize <= 0) it.copy(fileSize = size).also { learned = true } else it }
        }
        if (learned) persistScope.launch { runCatching { historyDao.setFileSize(fileName, size) } }
    }

    fun addDownload(downloadItem: DownloadItemModel) {
        addDownloads(listOf(downloadItem))
    }

    /** Adds many downloads in one list update (a bulk start). */
    fun addDownloads(items: List<DownloadItemModel>): Unit = synchronized(progressLock) {
        if (items.isEmpty()) return
        val names = items.mapTo(HashSet()) { it.fileName }
        names.forEach { pending.remove(it); lastUpdateTimes.remove(it) }
        _downloads.update { list -> list.filter { it.fileName !in names } + items }
    }

    fun removeDownload(fileName: String): Unit = synchronized(progressLock) {
        pending.remove(fileName)
        _downloads.update { list -> list.filter { it.fileName != fileName } }
        lastUpdateTimes.remove(fileName)
    }

    fun getDownloads(): List<DownloadItemModel> {
        return _downloads.value
    }

    fun resetDownloadForRetry(fileName: String): Unit = synchronized(progressLock) {
        pending.remove(fileName)
        _downloads.update { list ->
            list.map { item ->
                if (item.fileName == fileName) {
                    item.copy(
                        status = DownloadStatus.DOWNLOADING,
                        progress = 0f,
                        downloadSpeed = 0f,
                        downloadedBytes = 0L,
                        startedAt = System.currentTimeMillis(),
                        finishedAt = null
                    )
                } else {
                    item
                }
            }
        }
        persistCurrentStatuses(listOf(fileName))
    }

    /** [resetDownloadForRetry] for many rows in one list update; returns the names that could be retried. */
    fun resetDownloadsForRetry(fileNames: Collection<String>): List<String> = synchronized(progressLock) {
        val wanted = fileNames.toHashSet()
        wanted.forEach { pending.remove(it) }
        val reset = ArrayList<String>()
        val now = System.currentTimeMillis()
        _downloads.update { list ->
            reset.clear()
            list.map { item ->
                if (item.fileName in wanted && (item.status == DownloadStatus.FAILED || item.status == DownloadStatus.STOPPED ||
                        item.status == DownloadStatus.COMPLETED || item.status == DownloadStatus.PAUSED)) {
                    reset += item.fileName
                    item.copy(status = DownloadStatus.DOWNLOADING, progress = 0f, downloadSpeed = 0f, downloadedBytes = 0L, startedAt = now, finishedAt = null)
                } else item
            }
        }
        persistCurrentStatuses(reset)
        return reset
    }

    /** Finished rows (completed included — the UI offers "download again"), stopped or failed. */
    fun canRetryDownload(fileName: String): Boolean {
        return _downloads.value.any {
            it.fileName == fileName &&
            (it.status == DownloadStatus.FAILED || it.status == DownloadStatus.STOPPED ||
             it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.PAUSED)
        }
    }

    /** True while [fileName] is queued, downloading, copying or extracting. */
    fun isActive(fileName: String): Boolean =
        _downloads.value.any { it.fileName == fileName && !it.isFinished }

    /** Names of the rows that are queued, downloading, copying or extracting, in one pass over the list. */
    fun activeNames(): Set<String> = _downloads.value.filterNot { it.isFinished }.mapTo(HashSet()) { it.fileName }

    fun hasActiveDownloads(): Boolean {
        return _downloads.value.any {
            it.status == DownloadStatus.QUEUED ||
            it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.COPYING ||
            it.status == DownloadStatus.UNZIPPING
        }
    }

    fun shouldUpdateProgress(progress: Float, lastUpdateTime: Long, currentTime: Long): Boolean {
        return progress >= Constants.PROGRESS_COMPLETE ||
               (currentTime - lastUpdateTime) > Constants.PROGRESS_UPDATE_INTERVAL_MS
    }
}
