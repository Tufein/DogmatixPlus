package com.cortinadev.dogmatix.data.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.local.WeeklyDigestSettings
import com.cortinadev.dogmatix.util.WeeklyDigest
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WeeklyDigestScheduler"
private const val JOB_ID = 7401

/**
 * Keeps the weekly digest job in step with its setting (7.0): while the digest is on, a periodic
 * job wakes up once a week (in the last day of each week) and asks [WeeklyDigestService] to send
 * the digest. No network or charger is needed: everything it reads is on the device. The job
 * survives a reboot. Injected into the application so it follows the setting from the start.
 *
 * Like the other background jobs of the app this uses the JobScheduler (the project carries no
 * WorkManager); a periodic job that is already scheduled is left alone, so starting the app does
 * not push the next run back every time.
 */
@Singleton
class WeeklyDigestScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: WeeklyDigestSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            settings.enabled.distinctUntilChanged().collect { apply(it) }
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
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, WeeklyDigestJobService::class.java))
                .setPeriodic(WeeklyDigest.PERIOD_MS, WeeklyDigest.FLEX_MS)
                .setPersisted(true)
                .build()
            val result = scheduler.schedule(job)
            Log.i(TAG, "Weekly digest scheduled: ${if (result == JobScheduler.RESULT_SUCCESS) "ok" else "refused"}")
        }.onFailure { Log.w(TAG, "Weekly digest not scheduled: ${it.message}") }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WeeklyDigestEntryPoint {
    fun digestService(): WeeklyDigestService
}

/** One wake-up of the weekly digest: sends it when it is on and due (see [WeeklyDigestService.runIfDue]). */
class WeeklyDigestJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val service = EntryPointAccessors.fromApplication(applicationContext, WeeklyDigestEntryPoint::class.java).digestService()
        running = scope.launch {
            runCatching { service.runIfDue() }.onFailure { Log.w("WeeklyDigestJob", "Weekly digest failed: ${it.message}") }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true   // try again later
    }
}
