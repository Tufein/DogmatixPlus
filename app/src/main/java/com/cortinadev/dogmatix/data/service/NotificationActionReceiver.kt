package com.cortinadev.dogmatix.data.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.util.NotifAction
import com.cortinadev.dogmatix.util.QueueActions
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** What the notification buttons need from the app graph (a manifest receiver has no constructor injection). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationActionEntryPoint {
    fun downloads(): DownloadService
}

/**
 * The buttons of the download notifications that do not open the app (7.5): *Pause all* /
 * *Resume all* toggle the same hold as *Downloads → Hold the queue* and the Quick Settings tile
 * ([DownloadGate.setHeld]); *Stop all* stops what the Downloads screen's *Stop all* stops.
 * Not exported: only the app's own (explicit, immutable) PendingIntents reach it.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val downloads = EntryPointAccessors.fromApplication(context.applicationContext, NotificationActionEntryPoint::class.java).downloads()
        when (intent.action) {
            ACTION_PAUSE_ALL -> downloads.gate.setHeld(true)
            ACTION_RESUME_ALL -> downloads.gate.setHeld(false)
            // Cancelling only cancels jobs and launches the clean-up, so this is quick even for a long queue.
            ACTION_STOP_ALL -> QueueActions.stoppable(downloads.downloads.value).forEach(downloads::cancelDownload)
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.cortinadev.dogmatix.action.NOTIF_PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.cortinadev.dogmatix.action.NOTIF_RESUME_ALL"
        const val ACTION_STOP_ALL = "com.cortinadev.dogmatix.action.NOTIF_STOP_ALL"
    }
}

/**
 * Builds the buttons of [NotifAction] for a notification. Every PendingIntent is explicit and
 * immutable and has a request code of its own, so one never replaces another.
 */
object NotificationButtons {
    private const val CODE_PAUSE_ALL = 7_501
    private const val CODE_RESUME_ALL = 7_502
    private const val CODE_STOP_ALL = 7_503
    private const val CODE_QUEUE_DOWNLOADS = 7_504

    /** The queue-wide buttons of the ongoing notification ([NotifAction.PAUSE_ALL], [NotifAction.RESUME_ALL], [NotifAction.STOP_ALL]). */
    fun addQueueActions(builder: NotificationCompat.Builder, context: Context, actions: List<NotifAction>) {
        for (action in actions) {
            val (icon, label, intentAction, code) = when (action) {
                NotifAction.PAUSE_ALL -> Quad(R.drawable.ic_pause, R.string.notif75_pause_all, NotificationActionReceiver.ACTION_PAUSE_ALL, CODE_PAUSE_ALL)
                NotifAction.RESUME_ALL -> Quad(R.drawable.ic_play, R.string.notif75_resume_all, NotificationActionReceiver.ACTION_RESUME_ALL, CODE_RESUME_ALL)
                NotifAction.STOP_ALL -> Quad(R.drawable.ic_stop, R.string.notif75_stop_all, NotificationActionReceiver.ACTION_STOP_ALL, CODE_STOP_ALL)
                else -> continue
            }
            val pending = PendingIntent.getBroadcast(
                context, code,
                Intent(context, NotificationActionReceiver::class.java).setAction(intentAction),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(icon, context.getString(label), pending)
        }
    }

    /** Opens the Downloads section (through MainActivity's EXTRA_OPEN_ROUTE). */
    fun openDownloads(context: Context, code: Int = CODE_QUEUE_DOWNLOADS): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java)
            .putExtra(PendingLibraryFilters.EXTRA_OPEN_ROUTE, NavRoutes.Downloads.route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Opens the library on a `dogmatix://library?…` link (MainActivity hands it to the library like any deep link). */
    fun openLibrary(context: Context, link: String, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** The "Open the downloads" button. */
    fun addOpenDownloads(builder: NotificationCompat.Builder, context: Context, code: Int = CODE_QUEUE_DOWNLOADS) {
        builder.addAction(R.drawable.ic_download, context.getString(R.string.notif75_open_downloads), openDownloads(context, code))
    }

    private data class Quad(val icon: Int, val label: Int, val action: String, val code: Int)
}
