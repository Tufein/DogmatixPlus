package com.cortinadev.dogmatix.data.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cortinadev.dogmatix.DogmatixApplication
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.util.NotifAction
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.util.NotificationActions
import com.cortinadev.dogmatix.util.QueueSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When a run of downloads (two or more) is done, one notification says how it went —
 * "120 downloaded · 3 failed" — and opens the Downloads screen. *Settings → Notify when the queue
 * is done*.
 *
 * 7.5: a run that was one game (a single download, or the discs of one game) that completed gets
 * its own quiet notice instead — "Chrono Trigger downloaded", with *Open in the library* and
 * *Open the downloads* — under the same setting. The summary gained *Open the downloads*.
 */
@Singleton
class QueueSummaryService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadService: DownloadService,
    private val appSettings: AppSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Every run is looked at (a run of one may be a game); the summary itself still needs two.
    private val summary = QueueSummary(minSize = 1)

    fun start() {
        scope.launch {
            downloadService.downloads.collect { list ->
                val s = summary.update(list) ?: return@collect
                if (!appSettings.queueSummary.first()) return@collect
                val game = finishedGame(list)
                when {
                    game != null -> notifyGame(game)
                    s.total >= 2 -> notify(s)
                }
            }
        }
    }

    /** The game the run that just ended was, when it was exactly one and it completed. */
    private fun finishedGame(list: List<DownloadItemModel>): NotificationActions.FinishedGame? {
        val byName = list.associateBy { it.fileName }
        val files = summary.lastRun.map { name ->
            val entity = downloadService.entityFor(name) ?: return null
            NotificationActions.RunFile(entity.consoleId, entity.name, byName[name]?.status == DownloadStatus.COMPLETED)
        }
        return NotificationActions.singleGame(files)
    }

    private fun notifyGame(game: NotificationActions.FinishedGame) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val id = NotificationActions.gameNotificationId(game)
        // Two request codes per notice, outside the fixed codes of the other notifications.
        val libraryCode = 15_000 + (id - NotificationActions.GAME_ID_BASE) * 2
        val builder = NotificationCompat.Builder(context, DogmatixApplication.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arrow_down)
            .setContentTitle(context.getString(R.string.notif75_game_done_title, game.title))
            .setContentText(
                if (game.files > 1) context.resources.getQuantityString(R.plurals.notif75_game_done_files, game.files, game.files)
                else context.getString(R.string.notif75_game_done_text)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GAME_GROUP)
            .setAutoCancel(true)
        for (action in NotificationActions.finishedGame(game)) when (action) {
            NotifAction.OPEN_IN_LIBRARY -> game.link?.let { link ->
                val open = NotificationButtons.openLibrary(context, link, libraryCode)
                builder.setContentIntent(open)
                builder.addAction(R.drawable.ic_library, context.getString(R.string.notif75_open_library), open)
            }
            NotifAction.OPEN_DOWNLOADS -> {
                NotificationButtons.addOpenDownloads(builder, context, libraryCode + 1)
                if (game.link == null) builder.setContentIntent(NotificationButtons.openDownloads(context, libraryCode + 1))
            }
            else -> Unit
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(id, builder.build())
        // Two or more notices of games: one group header keeps them together.
        val shown = runCatching { manager.activeNotifications.count { it.notification.group == GAME_GROUP && it.id != GAME_SUMMARY_ID } }.getOrDefault(0)
        if (shown >= 2) {
            val header = NotificationCompat.Builder(context, DogmatixApplication.DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_arrow_down)
                .setContentTitle(context.resources.getQuantityString(R.plurals.notif75_games_done, shown, shown))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setGroup(GAME_GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(NotificationButtons.openDownloads(context, GAME_SUMMARY_CODE))
                .build()
            manager.notify(GAME_SUMMARY_ID, header)
        }
    }

    private fun notify(s: QueueSummary.Summary) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val r = context.resources
        val title = if (s.failed == 0 && s.stopped == 0) r.getQuantityString(R.plurals.queue_done_title_ok, s.completed, s.completed)
            else context.getString(R.string.queue_done_title)
        val text = buildList {
            add(r.getQuantityString(R.plurals.queue_done_completed, s.completed, s.completed))
            if (s.failed > 0) add(r.getQuantityString(R.plurals.queue_done_failed, s.failed, s.failed))
            if (s.stopped > 0) add(r.getQuantityString(R.plurals.queue_done_stopped, s.stopped, s.stopped))
        }.joinToString(" · ")
        val open = PendingIntent.getActivity(
            context, 5,
            Intent(context, MainActivity::class.java)
                .putExtra(PendingLibraryFilters.EXTRA_OPEN_ROUTE, NavRoutes.Downloads.route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, DogmatixApplication.SCAN_CHANNEL_ID)
            .setSmallIcon(if (s.failed > 0) R.drawable.ic_error else R.drawable.ic_arrow_down)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .also { b -> NotificationActions.queueDone().forEach { if (it == NotifAction.OPEN_DOWNLOADS) NotificationButtons.addOpenDownloads(b, context) } }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val NOTIFICATION_ID = 4222
        const val GAME_GROUP = "com.cortinadev.dogmatix.FINISHED_GAMES"
        const val GAME_SUMMARY_ID = 7_499
        const val GAME_SUMMARY_CODE = 7_505
    }
}
