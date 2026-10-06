package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus

/**
 * What the second screen's downloads dashboard shows and which touch buttons it offers: the
 * whole-queue *Pause all / Resume all* (the queue hold of [com.cortinadev.dogmatix.data.service.DownloadGate])
 * and a pause or resume button per download. Pure JVM for the tests.
 */
object SecondScreenControls {

    enum class Mode {
        /** Nothing running, nothing paused, queue not held: the mascot and a line of help. */
        IDLE,
        /** Downloads run (or wait their turn): the ring, the rows and *Pause all*. */
        ACTIVE,
        /** The user holds the queue: "Paused" with *Resume all* up front, even when the queue is empty. */
        HELD,
        /** Only downloads the user paused one by one: "Paused", each row with its own resume button. */
        PAUSED
    }

    /** The big whole-queue button; null = none. */
    enum class HoldButton { PAUSE_ALL, RESUME_ALL }

    /** The button at the end of a download's row. */
    enum class RowAction { PAUSE, RESUME, NONE }

    /**
     * @property rows the downloads listed: running ones first (queue order), then the paused ones.
     * @property waiting downloads that have not started yet (they wait for a slot, the rules or the hold).
     * @property paused downloads the user paused.
     */
    data class View(
        val mode: Mode,
        val holdButton: HoldButton?,
        val rows: List<DownloadItemModel>,
        val waiting: Int,
        val paused: Int
    )

    fun view(list: List<DownloadItemModel>, held: Boolean): View {
        val running = list.filter { QueueGlance.isRunning(it) }
        val paused = list.filter { it.status == DownloadStatus.PAUSED }
        val waiting = running.count { it.status == DownloadStatus.QUEUED }
        val rows = running + paused
        val mode = when {
            held -> Mode.HELD
            running.isNotEmpty() -> Mode.ACTIVE
            paused.isNotEmpty() -> Mode.PAUSED
            else -> Mode.IDLE
        }
        val button = when (mode) {
            Mode.HELD -> HoldButton.RESUME_ALL
            Mode.ACTIVE -> HoldButton.PAUSE_ALL
            Mode.PAUSED, Mode.IDLE -> null
        }
        return View(mode, button, rows, waiting, paused.size)
    }

    /**
     * The same rule as the Downloads screen: a paused download resumes; one that can pause
     * ([QueueActions.canPause]) gets a pause button; everything else (unpacking, copying, a torrent
     * still with its debrid service, or a download whose source is not known, [isTorrent] null) gets none.
     */
    fun rowAction(status: DownloadStatus, isTorrent: Boolean?): RowAction = when {
        status == DownloadStatus.PAUSED -> RowAction.RESUME
        isTorrent != null && QueueActions.canPause(status, isTorrent) -> RowAction.PAUSE
        else -> RowAction.NONE
    }
}
