package com.cortinadev.dogmatix.data.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.local.SmartStorageSettings
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

private const val TAG = "SmartStorageScheduler"
private const val JOB_ID = 8411
private const val PERIOD_MS = 7L * 24 * 60 * 60 * 1000
private const val FLEX_MS = 24L * 60 * 60 * 1000

/**
 * Keeps the weekly smart storage run in step with its settings (8.0): while smart storage and its
 * weekly run are on, a periodic job wakes up once a week while the device charges and is idle-ish
 * (battery not low) and asks [SmartStorageService] to run. An automatic run moves at most
 * [com.cortinadev.dogmatix.util.SmartStorage.AUTO_RUN_BUDGET_BYTES]; if the system stops the job
 * the copies made so far are kept and the next run carries on. Survives a reboot. Inject it into
 * the application so it follows the settings from the start (the Storage tool injects it too).
 */
@Singleton
class SmartStorageScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SmartStorageSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            combine(settings.enabled, settings.weekly) { on, weekly -> on && weekly }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
    }

    private fun apply(on: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!on) {
            scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        // A missing manifest entry must not take the app down: schedule() throws for an unknown service.
        runCatching {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SmartStorageJobService::class.java))
                .setPeriodic(PERIOD_MS, FLEX_MS)
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(false)
                .setPersisted(true)
                .build()
            val result = scheduler.schedule(job)
            Log.i(TAG, "Smart storage scheduled: ${if (result == JobScheduler.RESULT_SUCCESS) "ok" else "refused"}")
        }.onFailure { Log.w(TAG, "Smart storage not scheduled: ${it.message}") }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmartStorageEntryPoint {
    fun smartStorage(): SmartStorageService
}

/** One weekly wake-up of smart storage (see [SmartStorageService.runIfEnabled]). */
class SmartStorageJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val service = EntryPointAccessors.fromApplication(applicationContext, SmartStorageEntryPoint::class.java).smartStorage()
        running = scope.launch {
            runCatching { service.runIfEnabled() }.onFailure { Log.w("SmartStorageJob", "Smart storage run failed: ${it.message}") }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true   // try again later; copies made so far are reused
    }
}
