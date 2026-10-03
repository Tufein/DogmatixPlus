package com.cortinadev.dogmatix.data.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cortinadev.dogmatix.DogmatixApplication
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.BackgroundSyncPolicy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SaveSyncScheduler"
private const val JOB_ID = 4201
private const val NOTIFICATION_ID = 4202

/**
 * Keeps the periodic background job for the save sync in step with Settings → Save sync: on while
 * "Sync in the background" is switched on, at the chosen interval, on Wi-Fi (or any network) and
 * optionally only while charging. The job survives a reboot.
 */
@Singleton
class SaveSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Plan(val on: Boolean, val hours: Int, val wifiOnly: Boolean, val charging: Boolean)

    init {
        scope.launch {
            combine(
                settingsRepository.saveSyncBackground, settingsRepository.saveSyncBgIntervalHours,
                settingsRepository.saveSyncBgWifiOnly, settingsRepository.saveSyncBgCharging
            ) { on, hours, wifi, charging -> Plan(on, hours, wifi, charging) }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
    }

    private fun apply(plan: Plan) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!plan.on) {
            scheduler.cancel(JOB_ID)
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SaveSyncJobService::class.java))
            .setPeriodic(BackgroundSyncPolicy.periodMillis(plan.hours))
            .setRequiredNetworkType(if (plan.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setRequiresCharging(plan.charging)
            .setPersisted(true)
            .build()
        val result = scheduler.schedule(job)
        Log.i(TAG, "Background save sync every ${plan.hours} h scheduled: ${if (result == JobScheduler.RESULT_SUCCESS) "ok" else "refused"}")
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SaveSyncJobEntryPoint {
    fun saveSyncService(): SaveSyncService
}

/** One run of the background save sync; tells the user only when a choice or a failure needs them. */
class SaveSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val service = EntryPointAccessors.fromApplication(applicationContext, SaveSyncJobEntryPoint::class.java).saveSyncService()
        running = scope.launch {
            val result = runCatching { if (service.isConfigured()) service.sync() else null }.getOrNull()
            if (result != null && BackgroundSyncPolicy.shouldNotify(result.conflicts, result.failed)) {
                notify(result.conflicts, result.failed)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true   // try again later
    }

    private fun notify(conflicts: Int, failed: Int) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val text = when {
            conflicts > 0 -> resources.getQuantityString(R.plurals.save_sync_bg_conflicts, conflicts, conflicts)
            else -> resources.getQuantityString(R.plurals.save_sync_bg_failed, failed, failed)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, DogmatixApplication.SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arrow_up)
            .setContentTitle(getString(R.string.save_sync_bg_title))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
