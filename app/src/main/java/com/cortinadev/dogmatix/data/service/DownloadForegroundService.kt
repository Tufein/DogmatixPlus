package com.cortinadev.dogmatix.data.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.cortinadev.dogmatix.DogmatixApplication
import com.cortinadev.dogmatix.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process in the foreground (notification + wake lock) while downloads are active.
 *
 * It manages its own lifetime: every start promotes it to the foreground at once, and it stops
 * itself a few seconds after the last download ended, with the id of the latest start — so a
 * download added in that moment keeps it alive instead of racing a separate "stop" request
 * (that race made Android kill the app: a service started with `startForegroundService` that is
 * stopped before it called `startForeground`). Stopping never cancels downloads.
 */
@AndroidEntryPoint
class DownloadForegroundService : Service() {

    @Inject lateinit var downloadService: DownloadService
    @Inject lateinit var torrentHandleRegistry: TorrentHandleRegistry
    @Inject lateinit var torrentProgressBridge: TorrentProgressBridge

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastStartId = 0

    companion object {
        const val ACTION_START_SERVICE = "start_service"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "DownloadFgService"
        /** How long the service waits after the last download before it stops (queues hand over in between). */
        private const val IDLE_GRACE_MS = 3_000L

        /** True from the first start until the service decided to stop; read by [DownloadService]. */
        @Volatile var running = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        torrentHandleRegistry.start()
        torrentHandleRegistry.session().addListener(torrentProgressBridge)

        // Keep the CPU awake so downloads continue when the screen is off.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dogmatix:DownloadWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
        scope.launch {
            downloadService.anyActive.distinctUntilChanged().collectLatest { active ->
                if (!active) {
                    delay(IDLE_GRACE_MS)
                    if (!downloadService.hasActiveDownloads()) {
                        running = false
                        ServiceCompat.stopForeground(this@DownloadForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        torrentHandleRegistry.session().removeListener(torrentProgressBridge)
        // Only an idle service is normally destroyed; if Android ended it while downloads still run
        // (the time limit), leave the torrent session to them.
        if (!downloadService.hasActiveDownloads()) torrentHandleRegistry.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every startForegroundService() must be answered with startForeground(), whatever follows.
        if (!promoteToForeground()) {
            running = false
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        return START_NOT_STICKY
    }

    /** Android 15's daily limit for data-sync services: give the foreground back; downloads keep running while the app lives. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground time limit reached; stopping the service, downloads continue")
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(): Boolean {
        val notification = NotificationCompat.Builder(this, DogmatixApplication.DOWNLOAD_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_downloading))
            .setContentText(getString(R.string.notification_downloading_text))
            .setSmallIcon(R.drawable.ic_arrow_down)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            true
        } catch (e: Exception) {
            // Android 12+ refuses a foreground start from the background (or after the time limit).
            Log.w(TAG, "Could not enter the foreground: ${e.message}")
            false
        }
    }

    override fun onBind(intent: Intent?) = null
}
