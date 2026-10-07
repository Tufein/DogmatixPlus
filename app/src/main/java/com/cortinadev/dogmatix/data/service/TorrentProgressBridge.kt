package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.util.DownloadFailures
import com.cortinadev.dogmatix.util.TorrentProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AlertListener
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.swig.int64_vector
import javax.inject.Inject
import javax.inject.Singleton

/** Application-scoped listener: notification service restarts never interrupt torrent updates. */
@Singleton
class TorrentProgressBridge @Inject constructor(
    private val progressTracker: DownloadProgressTracker
) : AlertListener {

    private data class TrackedFile(
        val fileIndex: Int,
        val handle: TorrentHandle,
        val torrentId: String,
        val expectedSize: Long
    )

    private val tracked = mutableMapOf<String, TrackedFile>()
    private class RateState(var bytes: Long, var nanos: Long, var bytesPerSec: Float = 0f)
    private val rates = mutableMapOf<String, RateState>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private var pollingJob: Job? = null

    fun trackDownload(fileName: String, fileIndex: Int, handle: TorrentHandle) {
        // Native queries must stay outside the tracked monitor, including the torrent identity.
        val expectedSize = handle.torrentFile()?.files()?.fileSize(fileIndex) ?: 0L
        val torrentId = handle.infoHash().toString()
        synchronized(this) {
            tracked[fileName] = TrackedFile(fileIndex, handle, torrentId, expectedSize)
            rates.remove(fileName)
            startPolling()
        }
        refreshRequests.trySend(Unit)
        Log.d(TAG, "Tracking $fileName at index $fileIndex")
    }

    fun untrackDownload(fileName: String, fileIndex: Int) {
        synchronized(this) {
            tracked.remove(fileName)
            rates.remove(fileName)
            if (tracked.isEmpty()) {
                pollingJob?.cancel()
                pollingJob = null
            }
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                updateProgress()
                withTimeoutOrNull(1000L) { refreshRequests.receive() }
            }
        }
    }

    private fun updateProgress() {
        val statuses = progressTracker.downloads.value.associate { it.fileName to it.status }
        val snapshot = synchronized(this) { tracked.filter { TorrentProgress.acceptsUpdates(statuses[it.key]) } }
        // Whole-archive torrents have hundreds of thousands of files. Query/allocate their
        // progress array once per torrent, rather than once for every selected sibling file.
        for (files in snapshot.entries.groupBy { it.value.torrentId }.values) {
            try {
                val handle = files.first().value.handle
                if (!handle.isValid) continue
                // Piece granularity is cheaper and counts only hash-verified data. Aggregate
                // torrent progress cannot safely stand in for a selected file's bytes.
                val progress = int64_vector()
                try {
                    handle.swig().file_progress(progress, TorrentHandle.PIECE_GRANULARITY)
                    val fileCount = progress.size
                    files.forEach { (fileName, info) ->
                        val bytes = TorrentProgress.fileBytes(info.fileIndex, fileCount) { progress.get(it) }
                            ?: return@forEach
                        updateFile(fileName, info, bytes, statuses[fileName])
                    }
                } finally {
                    // The library convenience API leaves this potentially huge native vector
                    // to finalization and also copies all files into Java, even unselected ones.
                    progress.delete()
                }
            } catch (e: Exception) {
                // A removed/failed handle must not end polling for all the other torrents.
                Log.w(TAG, "Could not poll torrent progress: ${e.message}")
            }
        }
    }

    private fun updateFile(fileName: String, info: TrackedFile, downloaded: Long, currentStatus: DownloadStatus?) {
        val total = info.expectedSize
        if (total <= 0) return
        if (!TorrentProgress.acceptsUpdates(currentStatus)) return

        val now = System.nanoTime()
        synchronized(this) {
            // Untracking/retrying can happen while the native query is in flight.
            if (tracked[fileName] !== info) return
            val rate = rates.getOrPut(fileName) { RateState(downloaded, now) }
            val dtSeconds = (now - rate.nanos) / 1e9f
            if (dtSeconds > 0.2f) {
                // The tracker applies the same time-based smoothing to HTTP and torrents.
                rate.bytesPerSec = (downloaded - rate.bytes).coerceAtLeast(0L) / dtSeconds
                rate.bytes = downloaded
                rate.nanos = now
            }
            val speedMBs = rate.bytesPerSec / (1024f * 1024f)
            // Keep the identity check and publication together: untrack/retrack must not let
            // an old snapshot complete a newer download of the same name. No JNI under this lock.
            TorrentProgress.statusUpdate(currentStatus, downloaded, total)?.let {
                progressTracker.updateDownloadStatus(fileName, it, allowedFrom = TorrentProgress.NETWORK_STATUSES)
            }
            val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            progressTracker.updateDownloadProgress(fileName, progress, speedMBs, downloaded)
        }
    }

    fun countTrackedForHandle(handle: TorrentHandle): Int {
        val torrentId = handle.infoHash().toString()
        return synchronized(this) { tracked.values.count { it.torrentId == torrentId } }
    }

    fun getTrackedFileIndicesForHandle(handle: TorrentHandle): Set<Int> {
        val torrentId = handle.infoHash().toString()
        return synchronized(this) {
            tracked.values.filter { it.torrentId == torrentId }.mapTo(HashSet()) { it.fileIndex }
        }
    }

    override fun types(): IntArray = intArrayOf(
        AlertType.TORRENT_FINISHED.swig(),
        AlertType.FILE_ERROR.swig(),
        AlertType.TORRENT_ERROR.swig()
    )

    override fun alert(alert: Alert<*>) {
        try {
            when (alert.type()) {
                // A finish alert can precede a new sibling being selected. Recheck exact per-file
                // verified bytes instead of declaring every currently tracked sibling complete.
                AlertType.TORRENT_FINISHED -> refreshRequests.trySend(Unit)
                AlertType.FILE_ERROR -> onError((alert as FileErrorAlert).handle(), alert.message(), fileError = true)
                AlertType.TORRENT_ERROR -> onError((alert as TorrentErrorAlert).handle(), alert.message(), fileError = false)
                else -> Unit
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not process torrent alert: ${e.message}")
        }
    }

    private fun onError(handle: TorrentHandle?, message: String, fileError: Boolean) {
        if (handle == null) return
        Log.e(TAG, message)
        val torrentId = handle.infoHash().toString()
        val snapshot = synchronized(this) {
            tracked.filterValues { it.torrentId == torrentId }
        }
        snapshot.forEach { (fileName, info) ->
            synchronized(this) {
                if (tracked[fileName] !== info) return@forEach
                progressTracker.updateDownloadStatus(fileName, DownloadStatus.FAILED, allowedFrom = TorrentProgress.NETWORK_STATUSES,
                    failure = DownloadFailures.torrentAlert(message, fileError))
            }
        }
    }

    companion object { private const val TAG = "TorrentProgressBridge" }
}
