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
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
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
 */
@Singleton
class QueueSummaryService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadService: DownloadService,
    private val appSettings: AppSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val summary = QueueSummary()

    fun start() {
        scope.launch {
            downloadService.downloads.collect { list ->
                val s = summary.update(list) ?: return@collect
                if (appSettings.queueSummary.first()) notify(s)
            }
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
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private companion object { const val NOTIFICATION_ID = 4222 }
}
