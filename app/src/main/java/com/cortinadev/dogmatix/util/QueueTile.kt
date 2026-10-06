package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel

/** How the Quick Settings tile shows the queue. */
enum class QueueTileMode {
    /** Downloads are running or waiting their turn. */
    ACTIVE,
    /** The queue is on hold (*Downloads → Hold the queue*): running downloads finish, nothing new starts. */
    PAUSED,
    /** Nothing queued, nothing on hold. */
    IDLE
}

/** What a tap on the tile does. */
enum class QueueTileTap { HOLD, RESUME, OPEN_DOWNLOADS }

/**
 * The Quick Settings tile's view of the download queue: its state, the number of downloads in
 * the queue (what the widget and the second screen count as running, see [QueueGlance]) and what
 * a tap does. Pure JVM for the tests.
 */
object QueueTile {
    data class State(val mode: QueueTileMode, val queued: Int)

    fun state(list: List<DownloadItemModel>, held: Boolean): State {
        val queued = list.count { QueueGlance.isRunning(it) }
        val mode = when {
            held -> QueueTileMode.PAUSED
            queued > 0 -> QueueTileMode.ACTIVE
            else -> QueueTileMode.IDLE
        }
        return State(mode, queued)
    }

    /** On hold: resume. Running or waiting: hold. Nothing to hold: open the Downloads section instead. */
    fun tap(state: State): QueueTileTap = when (state.mode) {
        QueueTileMode.PAUSED -> QueueTileTap.RESUME
        QueueTileMode.ACTIVE -> QueueTileTap.HOLD
        QueueTileMode.IDLE -> QueueTileTap.OPEN_DOWNLOADS
    }
}
