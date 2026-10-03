package com.cortinadev.dogmatix.data.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.util.BackupRotation
import com.cortinadev.dogmatix.util.DiskScanner
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
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val JOB_ID = 4231
private const val INTERVAL_DAYS = 7

/**
 * Weekly backup into a folder the user picked (Settings → Automatic backup): the same file as
 * *Back up*, named by date; the newest five are kept. A daily job (charging, any network not
 * needed) checks whether a week has passed.
 */
@Singleton
class AutoBackupScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val backupService: BackupService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            combine(appSettings.autoBackup, appSettings.autoBackupDir) { on, dir -> on && dir.isNotBlank() }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
    }

    private fun apply(on: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!on) { scheduler.cancel(JOB_ID); return }
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, AutoBackupJobService::class.java))
                .setPeriodic(24L * 3_600_000)
                .setRequiresCharging(true)
                .setPersisted(true)
                .build()
        )
    }

    /** Writes a backup now when one is due ([force]: regardless); returns the file name, or null. */
    suspend fun runIfDue(force: Boolean = false): String? = withContext(Dispatchers.IO) {
        val dirUri = appSettings.autoBackupDir.first().takeIf { it.isNotBlank() } ?: return@withContext null
        if (!force && !BackupRotation.isDue(appSettings.autoBackupLast.first(), System.currentTimeMillis(), INTERVAL_DAYS)) return@withContext null
        val dir = DiskScanner.rootOf(dirUri) ?: return@withContext null
        val (json, _) = backupService.export()
        val name = BackupRotation.fileName(LocalDate.now())
        val resolver = context.contentResolver
        // One backup per day: today's is replaced.
        DiskScanner.list(context, dir).filter { it.name == name }.forEach { DiskScanner.delete(context, it.uri) }
        val doc = DocumentsContract.createDocument(resolver, DiskScanner.uriOf(dir), "application/octet-stream", name) ?: return@withContext null
        resolver.openOutputStream(doc)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: return@withContext null
        val entries = DiskScanner.list(context, dir)
        BackupRotation.toDelete(entries.map { it.name }).forEach { old -> entries.firstOrNull { it.name == old }?.let { DiskScanner.delete(context, it.uri) } }
        appSettings.setAutoBackupLast(System.currentTimeMillis())
        Log.i("AutoBackup", "Backup written: $name")
        name
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoBackupEntryPoint {
    fun autoBackup(): AutoBackupScheduler
}

class AutoBackupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val backup = EntryPointAccessors.fromApplication(applicationContext, AutoBackupEntryPoint::class.java).autoBackup()
        running = scope.launch {
            runCatching { backup.runIfDue() }.onFailure { Log.w("AutoBackup", "Backup failed: ${it.message}") }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return true }
}
