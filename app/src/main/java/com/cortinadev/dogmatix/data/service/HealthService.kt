package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.CoverDao
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.state.SourceScanResults
import com.cortinadev.dogmatix.util.FrontendCheck
import com.cortinadev.dogmatix.util.HealthCheck
import com.cortinadev.dogmatix.util.HealthResult
import com.cortinadev.dogmatix.util.HealthRules
import com.cortinadev.dogmatix.util.SourcesJson
import com.cortinadev.dogmatix.util.StorageInsights
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** One check may take this long; a dead server then shows as "no answer" instead of blocking the screen. */
const val HEALTH_CHECK_TIMEOUT_MS = 10_000L

/**
 * Runs every health check the app can do ("Check everything"). It only reads the existing
 * services; each check is gathered here and judged by the pure [HealthRules]. All checks run in
 * parallel off the main thread, each with its own time limit, and every result is emitted as soon
 * as it is ready.
 */
@Singleton
class HealthService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val consoleDao: ConsoleDao,
    private val coverDao: CoverDao,
    private val scanResults: SourceScanResults,
    private val libraryTools: LibraryToolsService,
    private val downloads: DownloadService,
    private val bios: BiosService,
    private val covers: CoverRepository,
    private val saveSync: SaveSyncService,
    private val romm: RommServerService,
    private val dav: DavStatusService,
    private val versionChecker: VersionCheckerService,
    private val settings: SettingsRepository,
    private val appSettings: AppSettings
) {

    /** Runs all checks at once; the flow ends when the last one is done. */
    fun runAll(): Flow<HealthResult> = channelFlow {
        HealthCheck.entries.forEach { check ->
            launch(Dispatchers.Default) { send(runOne(check)) }
        }
    }

    /** Runs one check with its time limit; never throws (a failure becomes a "look at this" result). */
    suspend fun runOne(check: HealthCheck, timeoutMs: Long = HEALTH_CHECK_TIMEOUT_MS): HealthResult = try {
        withTimeoutOrNull(timeoutMs) { withContext(Dispatchers.IO) { evaluate(check) } } ?: HealthRules.timedOut(check)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        HealthRules.failed(check, e.message)
    }

    /** "Look again" for covers: forgets the misses so they are searched again. Returns how many were cleared. */
    suspend fun retryCovers(): Int = covers.retryMisses()

    /** Asks the RomM server again (the check that follows reads the fresh answer). */
    suspend fun refreshRomm() { romm.check() }

    private suspend fun evaluate(check: HealthCheck): HealthResult = when (check) {
        HealthCheck.SOURCES -> sources()
        HealthCheck.STORAGE -> storage()
        HealthCheck.BIOS -> biosCheck()
        HealthCheck.COVERS -> HealthRules.covers(coverDao.countMisses())
        HealthCheck.SAVE_SYNC -> saveSyncCheck()
        HealthCheck.ROMM -> rommCheck()
        HealthCheck.WEBDAV -> davCheck()
        HealthCheck.RETRO_ACHIEVEMENTS -> HealthRules.retroAchievements(appSettings.raUser.first(), appSettings.raKey.first())
        HealthCheck.FRONTENDS -> frontends()
        HealthCheck.NOTIFICATIONS -> HealthRules.notifications(NotificationManagerCompat.from(context).areNotificationsEnabled())
        HealthCheck.BATTERY -> HealthRules.battery(
            runCatching { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true }.getOrDefault(false)
        )
        HealthCheck.UPDATE -> update()
    }

    private suspend fun sources(): HealthResult {
        val results = scanResults.results.value
        var lastAt = 0L
        val perConsole = consoleDao.getAllConsoles().first().map { console ->
            SourcesJson.parseUrlEntries(console.urls).filter { it.enabled }.map { entry ->
                val r = results["${console.id}|${entry.url}"]
                if (r != null) lastAt = maxOf(lastAt, r.at)
                r?.let { it.failure == null }
            }
        }
        return HealthRules.sources(perConsole, lastAt, System.currentTimeMillis())
    }

    private suspend fun storage(): HealthResult {
        val disk = libraryTools.disk()
        val need = StorageInsights.queueNeed(
            downloads.getDownloads()
                .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
                .map {
                    StorageInsights.QueueItem(
                        (it.fileSize - it.downloadedBytes).coerceAtLeast(0),
                        StorageInsights.isExtractable(it.fileName.substringAfterLast('.', ""))
                    )
                }
        )
        return HealthRules.storage(disk.folderSet, disk.freeBytes, disk.entries.sumOf { it.size }, StorageInsights.shortfall(need, disk.freeBytes))
    }

    private suspend fun biosCheck(): HealthResult {
        val report = bios.check(allSystems = false)
        return HealthRules.bios(report.folderSet, report.results.size, report.results.count { !it.ready })
    }

    private suspend fun saveSyncCheck(): HealthResult {
        val foldersSet = settings.saveSyncSavesDir.first().isNotBlank() ||
            settings.saveSyncStatesDir.first().isNotBlank() ||
            appSettings.saveSyncEmulatorFolders.first().isNotEmpty()
        val state = saveSync.state.value
        return HealthRules.saveSync(
            foldersSet = foldersSet,
            rommConfigured = saveSync.isConfigured(),
            running = state.running,
            error = state.error,
            conflicts = state.conflicts.size,
            failed = state.last?.failed ?: 0,
            lastSyncAt = state.last?.finishedAt ?: 0L,
            now = System.currentTimeMillis()
        )
    }

    private suspend fun rommCheck(): HealthResult {
        val configured = settings.rommUrl.first().isNotBlank() && settings.rommToken.first().isNotBlank()
        if (!configured) return HealthRules.romm(false, false, null, null, null)
        val info = romm.check()
        return HealthRules.romm(true, info.reachable, info.errorKind?.name, info.version, info.error)
    }

    private fun davCheck(): HealthResult {
        val s = dav.status.value
        return HealthRules.webdav(s.configured, s.connected, s.autoBackup, s.lastBackupAt, s.backupStale, s.attention, System.currentTimeMillis())
    }

    private suspend fun frontends(): HealthResult {
        val findings = FrontendCheck.evaluate(
            FrontendCheck.State(
                downloadFolderSet = settings.downloadDirectory.first().isNotBlank(),
                esdeFolderSet = settings.esdeDirectory.first().isNotBlank(),
                esdeCovers = appSettings.esdeArtwork.first(),
                iisuFolderSet = settings.iisuDirectory.first().isNotBlank(),
                pegasusCovers = appSettings.pegasusArtwork.first(),
                retroArchThumbnailsSet = appSettings.retroArchThumbnailsDir.first().isNotBlank()
            )
        )
        return HealthRules.frontends(findings)
    }

    /** The app's own update check (one request to the releases list, the same one Settings uses). */
    private suspend fun update(): HealthResult = when (val r = versionChecker.check(context)) {
        is VersionCheckerService.Result.Available -> HealthRules.update(true, r.tag)
        is VersionCheckerService.Result.UpToDate -> HealthRules.update(false, r.tag)
        VersionCheckerService.Result.Failed -> HealthRules.update(null, null)
    }
}
