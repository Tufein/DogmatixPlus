package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.util.HoldParking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The part of the 7.5 power rules and *Pause all* that touches downloads already transferring.
 * New downloads simply wait in [DownloadGate] like they do for the charger; a download that is
 * transferring when a hold begins is parked with a pause (a torrent keeps its cache and handle, a
 * web download its partial file) and queued again, so it waits in the gate with the others
 * ("Waits for: low battery") and goes on by itself when the hold lets go.
 *
 * Only downloads that can be paused without losing their progress are parked (see
 * [DownloadService.canParkSafely]); the others, and debrid downloads and those copying or
 * extracting, finish. Rows that merely wait (for a slot, the schedule or their own condition) are
 * left alone, so their "Download when" condition and their place in line stay.
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

    private val _parked = MutableStateFlow<Set<String>>(emptySet())
    /** Downloads this service paused and will queue again; the user touching a row takes it off. */
    val parked: StateFlow<Set<String>> = _parked.asStateFlow()

    init {
        // A pause, resume, stop or removal by the user wins: that row is no longer ours to requeue.
        downloadService.addUserActionListener { name -> _parked.update { it - name } }
    }

    /** Starts watching; safe to call more than once ([DownloadForegroundService] calls it when it comes up). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // 7.5: *Pause all* (the queue hold from Downloads, the second screen, the tile and the
            // notification) parks transferring downloads the same way, so it really pauses them.
            combine(powerMonitor.hold.map { it.any }, downloadService.gate.held) { power, user -> power || user }
                .distinctUntilChanged().collect { held -> if (held) parkRunning() }
        }
    }

    private fun parkRunning() {
        val names = HoldParking.toPark(
            list = downloadService.downloads.value,
            transferring = downloadService.downloads.value.mapNotNullTo(HashSet()) { it.fileName.takeIf(downloadService::isTransferring) },
            isPausing = downloadService::isPausing,
            parked = _parked.value,
            canPark = downloadService::canParkSafely
        )
        if (names.isEmpty()) return
        Log.i(TAG, "Hold: parking ${names.size} transferring download(s)")
        _parked.update { it + names }
        names.forEach { downloadService.pauseDownload(it, byUser = false) }
        scope.launch { requeueWhenPaused(names) }
    }

    /** Once the pauses landed, what is still ours goes back in line in one go, in list order, and waits in the gate. */
    private suspend fun requeueWhenPaused(names: List<String>) {
        withTimeoutOrNull(PAUSE_TIMEOUT_MS) {
            downloadService.downloads.first { list ->
                val byName = list.associateBy { it.fileName }
                val mine = _parked.value
                names.all { it !in mine || HoldParking.landed(byName[it]?.status) }
            }
        } ?: Log.w(TAG, "Hold: not every parked download paused in time")
        // A torrent's own pause finishes a moment after the job ended; let it land first.
        delay(SETTLE_MS)
        val mine = _parked.value.intersect(names.toSet())
        val again = HoldParking.toRequeue(downloadService.downloads.value, mine)
        _parked.update { it - names.toSet() }
        if (again.isNotEmpty()) downloadService.retryDownloads(again, byUser = false)
    }

    private companion object {
        const val TAG = "PowerHold"
        const val PAUSE_TIMEOUT_MS = 60_000L
        const val SETTLE_MS = 2_000L
    }
}
