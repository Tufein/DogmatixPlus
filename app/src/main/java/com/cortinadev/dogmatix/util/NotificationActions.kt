package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel

/** A button on one of the download notifications (7.5). */
enum class NotifAction {
    /** Holds the whole queue (*Downloads → Hold the queue*): running downloads finish, nothing new starts. */
    PAUSE_ALL,
    /** Lets a held queue go again. */
    RESUME_ALL,
    /** Stops everything queued, downloading or unpacking, like *Stop all* on the Downloads screen. */
    STOP_ALL,
    /** Opens the library searched on the game that just finished. */
    OPEN_IN_LIBRARY,
    /** Opens the Downloads section. */
    OPEN_DOWNLOADS
}

/**
 * Which buttons each download notification carries, and whether a finished run of downloads was
 * one game (then it gets its own "downloaded" notice instead of the queue summary). Pure JVM for
 * the tests; the notifications themselves are built by `DownloadForegroundService` and
 * `QueueSummaryService`, and the buttons that do not open the app go to `NotificationActionReceiver`.
 */
object NotificationActions {

    /** One file of a run that just ended: its console, its name in the library and whether it completed. */
    data class RunFile(val consoleId: String, val name: String, val completed: Boolean)

    /** A game whose download finished: what the notice says and the `dogmatix://library?…` link it opens. */
    data class FinishedGame(val consoleId: String, val title: String, val files: Int, val link: String?) {
        /** Stable per game, so a second download of the same game replaces its notice. */
        val key: String get() = "$consoleId|${title.lowercase()}"
    }

    /**
     * The ongoing downloads notification: on hold only *Resume all*; otherwise *Pause all* while
     * something is in the queue, plus *Stop all* when something can be stopped (the same rows the
     * Downloads screen's *Stop all* acts on, see [QueueActions.stoppable]).
     */
    fun ongoing(list: List<DownloadItemModel>, held: Boolean): List<NotifAction> {
        if (held) return listOf(NotifAction.RESUME_ALL)
        return buildList {
            if (list.any { QueueGlance.isRunning(it) }) add(NotifAction.PAUSE_ALL)
            if (QueueActions.counts(list).stoppable > 0) add(NotifAction.STOP_ALL)
        }
    }

    /** The notice of a finished game: the library (when there is a title to search) and the downloads. */
    fun finishedGame(game: FinishedGame): List<NotifAction> =
        if (game.link != null) listOf(NotifAction.OPEN_IN_LIBRARY, NotifAction.OPEN_DOWNLOADS) else listOf(NotifAction.OPEN_DOWNLOADS)

    /** The "queue done" summary. */
    fun queueDone(): List<NotifAction> = listOf(NotifAction.OPEN_DOWNLOADS)

    /**
     * The game a finished run was, or null when it was more than one game, nothing in it is known,
     * or not every file completed (a failure is left to the Downloads screen and the summary).
     * The discs of one game ("Disc 1", "Disc 2") count as one game: same console, same clean title.
     */
    fun singleGame(files: List<RunFile>): FinishedGame? {
        if (files.isEmpty() || files.any { !it.completed }) return null
        val consoles = files.map { it.consoleId }.distinct()
        if (consoles.size != 1) return null
        val titles = files.map { GameTitleCleaner.clean(it.name).ifBlank { it.name.trim() } }
        if (titles.map { it.lowercase() }.distinct().size != 1) return null
        val title = titles.first()
        if (title.isBlank()) return null
        return FinishedGame(consoles.first(), title, files.size, GameShareText.deepLink(consoles.first(), title))
    }

    /** First id of the per-game notices; [gameNotificationId] stays within [GAME_ID_BASE] until +[GAME_ID_SPAN]. */
    const val GAME_ID_BASE = 7_500
    const val GAME_ID_SPAN = 400

    /** The notification id of a game's notice (stable per [FinishedGame.key]). */
    fun gameNotificationId(game: FinishedGame): Int = GAME_ID_BASE + Math.floorMod(game.key.hashCode(), GAME_ID_SPAN)
}
