package com.tufein.valkyr.data.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.tufein.valkyr.data.local.RommDeviceSettings
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.RommRemoteInstall
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

private const val TAG = "RemoteInstallScheduler"
private const val JOB_ID = 5560

/**
 * Keeps the periodic poll for remote installs in step with Settings → RomM: scheduled while the feature is on, the
 * handheld is paired and "Use RomM" is on; not on a low battery. Android runs a periodic job at most every 15
 * minutes ([RommRemoteInstall.BACKGROUND_POLL_MS]); while the app is open it polls every minute by itself.
 * The job is persisted, so it survives a reboot. Not tested on a device: scheduling follows the documented JobScheduler rules.
 */
@Singleton
class RommRemoteInstallScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    settings: RommDeviceSettings,
    appSettings: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            combine(settings.enabled, settings.pairing, appSettings.rommEnabled) { on, pairing, romm -> on && pairing.paired && romm }
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
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, RommRemoteInstallJobService::class.java))
            .setPeriodic(RommRemoteInstall.BACKGROUND_POLL_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build()
        Log.i(TAG, "Remote install poll scheduled: ${if (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) "ok" else "refused"}")
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RommRemoteInstallJobEntryPoint {
    fun remoteInstall(): RommRemoteInstallService
}

/** One poll in the background; queues the downloads and reports, tells the user nothing (the queue shows the downloads). */
class RommRemoteInstallJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val service = EntryPointAccessors.fromApplication(applicationContext, RommRemoteInstallJobEntryPoint::class.java).remoteInstall()
        running = scope.launch {
            runCatching { service.poll() }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }
}
