package com.cortinadev.dogmatix.data.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cortinadev.dogmatix.DogmatixApplication
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.AutoScanPolicy
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AutoScanScheduler"
private const val JOB_ID = 4211
private const val NOTIFICATION_ID = 4212

/**
 * Keeps the background source scan in step with Settings → Sources: while it is on, a job wakes up
 * every hour that the conditions hold (Wi-Fi, charging) and scans when [AutoScanPolicy.isDue].
 * The job survives a reboot.
 */
@Singleton
class AutoScanScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appSettings: AppSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Plan(val on: Boolean, val wifiOnly: Boolean, val charging: Boolean)

    init {
        scope.launch {
            combine(appSettings.autoScan, appSettings.autoScanWifiOnly, appSettings.autoScanCharging) { on, wifi, charging -> Plan(on, wifi, charging) }
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
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutoScanJobService::class.java))
            .setPeriodic(AutoScanPolicy.CHECK_EVERY_MS)
            .setRequiredNetworkType(if (plan.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setRequiresCharging(plan.charging)
            .setPersisted(true)
            .build()
        val result = scheduler.schedule(job)
        Log.i(TAG, "Background scan check scheduled: ${if (result == JobScheduler.RESULT_SUCCESS) "ok" else "refused"}")
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoScanJobEntryPoint {
    fun scanService(): SourceScanService
    fun appSettings(): AppSettings
    fun settingsRepository(): SettingsRepository
    fun offlineCollections(): OfflineCollectionsService
}

/** One wake-up of the background scan: scans when due, and says what it found. */
class AutoScanJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val entry = EntryPointAccessors.fromApplication(applicationContext, AutoScanJobEntryPoint::class.java)
        running = scope.launch {
            runCatching {
                val settings = entry.appSettings()
                val calendar = Calendar.getInstance()
                val due = AutoScanPolicy.isDue(
                    now = System.currentTimeMillis(), last = settings.autoScanLast.first(), hours = settings.autoScanHours.first(),
                    nightOnly = settings.autoScanNightOnly.first(),
                    minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE),
                    nightStart = entry.settingsRepository().downloadNightStart.first(), nightEnd = entry.settingsRepository().downloadNightEnd.first()
                )
                if (due) {
                    val summary = entry.scanService().scanAllInBackground()
                    if (summary != null) {
                        settings.setAutoScanLast(summary.at)
                        Log.i("AutoScanJobService", "Background scan: $summary")
                        if (summary.newFiles > 0 || summary.failed > 0) notify(summary)
                        // 7.0: collections kept on the device fetch what the scan brought in (waited for here, so the job stays alive).
                        entry.offlineCollections().run(OfflineCollectionsService.Trigger.AUTO)
                    }
                }
            }.onFailure { Log.w("AutoScanJobService", "Background scan failed: ${it.message}") }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true   // try again later
    }

    private fun notify(summary: ScanSummary) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val title = if (summary.newFiles > 0) resources.getQuantityString(R.plurals.autoscan_new_title, summary.newFiles, summary.newFiles)
            else getString(R.string.autoscan_done_title)
        val text = buildList {
            add(resources.getQuantityString(R.plurals.autoscan_sources, summary.sources, summary.sources))
            if (summary.failed > 0) add(resources.getQuantityString(R.plurals.autoscan_failed, summary.failed, summary.failed))
        }.joinToString(" · ")
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .apply { if (summary.newFiles > 0) { action = Intent.ACTION_VIEW; data = Uri.parse("dogmatix://library?new=1") } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, DogmatixApplication.SCAN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_retry)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
