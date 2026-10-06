package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The part of the 7.5 power rules that touches downloads already running. New downloads simply
 * wait in [DownloadGate] like they do for the charger; a download that is running when the battery
 * drops below the limit or the device gets hot is parked with the user's own *Pause* (a torrent
 * keeps its cache and handle, a web download its partial file) and queued again at once, so it
 * waits in the gate with the others ("Waits for: low battery") and goes on by itself when the
 * rule lets go. Debrid downloads and downloads that are copying or extracting are left to finish.
 *
 * It acts on the moment a hold begins only, so *Start now* during a hold is respected.
 */
@Singleton
class PowerHoldService @Inject constructor(
    private val powerMonitor: PowerMonitor,
    private val downloadService: DownloadService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    /** Starts watching; safe to call more than once ([DownloadForegroundService] calls it when it comes up). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            powerMonitor.hold.map { it.any }.distinctUntilChanged().collect { held -> if (held) parkRunning() }
        }
    }

    private fun parkRunning() {
        val running = downloadService.downloads.value
            .filter { it.status == DownloadStatus.DOWNLOADING && !downloadService.isDebrid(it.fileName) }
            .map { it.fileName }
        if (running.isEmpty()) return
        Log.i(TAG, "Power rule: parking ${running.size} running download(s)")
        running.forEach { name ->
            downloadService.pauseDownload(name)
            scope.launch { requeueWhenPaused(name) }
        }
    }

    /** Once the pause landed (the job ended cleanly), the download goes back in line and waits in the gate. */
    private suspend fun requeueWhenPaused(fileName: String) {
        val paused = withTimeoutOrNull(PAUSE_TIMEOUT_MS) {
            downloadService.downloads.first { list ->
                val row = list.firstOrNull { it.fileName == fileName }
                row == null || row.status == DownloadStatus.PAUSED
            }.any { it.fileName == fileName }
        } ?: false
        // A torrent's own pause finishes a moment after the job ended; let it land first.
        if (!paused) {
            Log.w(TAG, "Power rule: $fileName did not pause in time; left as it is")
            return
        }
        delay(SETTLE_MS)
        downloadService.retryDownload(fileName)
    }

    private companion object {
        const val TAG = "PowerHold"
        const val PAUSE_TIMEOUT_MS = 60_000L
        const val SETTLE_MS = 2_000L
    }
}
