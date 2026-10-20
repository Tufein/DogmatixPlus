package com.tufein.valkyr.data.service

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.appcompat.app.AppCompatDelegate
import com.tufein.valkyr.BuildConfig
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.CloudSettings
import com.tufein.valkyr.data.local.dao.ConsoleDao
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.util.CrashLog
import com.tufein.valkyr.util.EmulatorCatalog
import com.tufein.valkyr.util.HealthCheck
import com.tufein.valkyr.util.HealthResult
import com.tufein.valkyr.util.ProblemReport
import com.tufein.valkyr.util.ReportApp
import com.tufein.valkyr.util.ReportDisplay
import com.tufein.valkyr.util.ReportFacts
import com.tufein.valkyr.util.ReportHistoryLine
import com.tufein.valkyr.util.ReportSigning
import com.tufein.valkyr.util.ReportVolume
import com.tufein.valkyr.util.SourcesJson
import com.tufein.valkyr.util.UpdateInstallability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gathers what "Report a problem" may write ([ReportFacts]): versions, device, space, a fixed list
 * of settings, which integrations are set up, counts, the local health checks, the last history
 * codes and the last crash. It only reads what the app already keeps; it starts no network request
 * (the RomM and update checks are left out of the health part) and sends nothing anywhere: the
 * player copies or shares the text themselves.
 */
@Singleton
class ProblemReportService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val appSettings: AppSettings,
    private val cloudSettings: CloudSettings,
    private val consoleDao: ConsoleDao,
    private val fileDao: DownloadableFileDao,
    private val downloads: DownloadService,
    private val operations: OperationHistoryService,
    private val actionLog: ActionLogService,
    private val profiles: ProfileService,
    private val rommClient: RommClient,
    private val diagnostics: DiagnosticsService,
    // Only created when the health part is gathered.
    private val health: dagger.Lazy<HealthService>
) {
    /** Health checks that would talk to a server: not run from the report. */
    private val networkChecks = setOf(HealthCheck.ROMM, HealthCheck.UPDATE)

    suspend fun collect(includeHealth: Boolean): ReportFacts = withContext(Dispatchers.IO) {
        val healthResults = if (includeHealth) runHealth() else emptyList()
        ReportFacts(
            generatedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            buildCode = BuildConfig.VERSION_CODE.toLong(),
            debugBuild = BuildConfig.DEBUG,
            packageName = context.packageName,
            signing = signing(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            firmware = Build.DISPLAY.orEmpty().take(60),
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            windowDp = context.resources.configuration.let { "${it.screenWidthDp}x${it.screenHeightDp} dp" },
            displays = displays(),
            controller = Gamepad.connected.value,
            appLanguage = runCatching { AppCompatDelegate.getApplicationLocales().toLanguageTags() }.getOrDefault(""),
            systemLocale = runCatching { Resources.getSystem().configuration.locales[0].toLanguageTag() }.getOrDefault(""),
            fontScale = context.resources.configuration.fontScale,
            textSizePercent = appSettings.textSizePercent.first(),
            volumes = volumes(),
            settings = settingsSummary(),
            rommConfigured = settings.rommUrl.first().isNotBlank() && settings.rommToken.first().isNotBlank(),
            // The version the app already read from the server; null until a RomM screen or sync asked.
            rommVersion = rommClient.serverVersion,
            webDav = cloudSettings.configured.first(),
            retroAchievements = appSettings.raUser.first().isNotBlank() && appSettings.raKey.first().isNotBlank(),
            saveSync = settings.saveSyncSavesDir.first().isNotBlank() || settings.saveSyncStatesDir.first().isNotBlank() ||
                appSettings.saveSyncEmulatorFolders.first().isNotEmpty(),
            debrid = settings.debridProvider.first().name,
            apps = installedApps(),
            health = healthResults,
            healthSkipped = if (includeHealth) HealthCheck.entries.filter { it in networkChecks } else emptyList(),
            operations = operationLines(),
            actions = actionLines(),
            crash = CrashLog.read(context),
            crashAt = CrashLog.writtenAt(context)
        ).let { counts(it) }
    }

    /** Values removed wherever they appear: the stored keys and addresses, plus names the player chose. */
    suspend fun secrets(): List<String> = withContext(Dispatchers.IO) {
        buildList {
            addAll(diagnostics.secrets())
            add(appSettings.raUser.first())
            profiles.profiles.value.forEach { add(it.name) }
            listOf(settings.downloadDirectory.first(), settings.esdeDirectory.first(), settings.iisuDirectory.first())
                .plus(settings.consoleDownloadDirectories.first().values)
                .filter { it.isNotBlank() }
                .forEach { add(it); add(Uri.decode(it)) }
        }.filter { it.isNotBlank() }.distinct()
    }

    private suspend fun counts(facts: ReportFacts): ReportFacts {
        val consoles = consoleDao.getAllConsoles().first()
        val sources = consoles.flatMap { SourcesJson.parseUrlEntries(it.urls) }
        val items = downloads.getDownloads()
        return facts.copy(
            consoles = consoles.size,
            sourcesEnabled = sources.count { it.enabled },
            sourcesTotal = sources.size,
            libraryGames = fileDao.getFilesCount(),
            profiles = profiles.profiles.value.size,
            downloadsByStatus = items.groupingBy { it.status.name }.eachCount(),
            failedByCategory = items.filter { it.status == DownloadStatus.FAILED }
                .groupingBy { it.failure?.category?.name ?: "UNCLASSIFIED" }.eachCount()
        )
    }

    /** The local checks of "Check everything", in parallel, each with the health screen's own time limit. */
    private suspend fun runHealth(): List<HealthResult> = coroutineScope {
        val service = health.get()
        HealthCheck.entries.filter { it !in networkChecks }.map { check -> async { service.runOne(check) } }.awaitAll()
    }

    private fun signing(): ReportSigning = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        ProblemReport.signingOf(info.signingInfo?.apkContentsSigners.orEmpty().map { UpdateInstallability.certificateDigest(it.toByteArray()) })
    } catch (e: Exception) {
        ReportSigning.UNKNOWN
    }

    private fun displays(): List<ReportDisplay> = runCatching {
        val manager = context.getSystemService(DisplayManager::class.java)
        val presentation = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }.toSet()
        manager.displays.map { d -> ReportDisplay(d.mode.physicalWidth, d.mode.physicalHeight, d.displayId in presentation) }
    }.getOrDefault(emptyList())

    /** Kind, state and space of each volume; the path is only used to read the space and is never kept. */
    private fun volumes(): List<ReportVolume> = runCatching {
        context.getSystemService(StorageManager::class.java).storageVolumes.map { volume ->
            val dir = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> volume.directory
                volume.isPrimary -> Environment.getExternalStorageDirectory()
                else -> null
            }
            val stat = dir?.let { runCatching { StatFs(it.path) }.getOrNull() }
            ReportVolume(volume.isPrimary, volume.isRemovable, volume.state.orEmpty(), stat?.availableBytes, stat?.totalBytes)
        }
    }.getOrDefault(emptyList())

    /** The whitelisted settings ([ProblemReport.SETTINGS_WHITELIST]): switches, numbers and fixed names. */
    private suspend fun settingsSummary(): Map<String, String> = mapOf(
        "theme" to settings.themeMode.first(),
        "gamepad_layout" to settings.gamepadLayout.first(),
        "swap_face_buttons" to settings.swapFaceButtons.first().toString(),
        "text_size" to "${appSettings.textSizePercent.first()}%",
        "bold_focus" to appSettings.boldFocus.first().toString(),
        "second_screen" to appSettings.secondScreen.first().toString(),
        "separate_by_console" to settings.separateByConsole.first().toString(),
        "auto_unzip" to settings.autoUnzip.first().toString(),
        "concurrent_downloads" to settings.concurrentDownloads.first().toString(),
        "speed_limit" to if (settings.limitSpeed.first() > 0f) "on" else "off",
        "per_server_limit" to appSettings.perServerLimit.first().toString(),
        "wifi_only" to settings.downloadWifiOnly.first().toString(),
        "charging_only" to settings.downloadChargingOnly.first().toString(),
        "night_only" to settings.downloadNightOnly.first().toString(),
        "resume_downloads" to appSettings.resumeDownloads.first().toString(),
        "requeue_after_restart" to appSettings.requeueAfterRestart.first().toString(),
        "auto_retry" to appSettings.autoRetryFailed.first().toString(),
        "queue_held" to appSettings.queueHeld.first().toString(),
        "auto_scan" to appSettings.autoScan.first().toString(),
        "auto_m3u" to appSettings.autoM3u.first().toString(),
        "esde_covers" to appSettings.esdeArtwork.first().toString(),
        "pegasus_covers" to appSettings.pegasusArtwork.first().toString(),
        "update_channel" to if (settings.updatePreReleases.first()) "pre-releases" else "releases",
        "min_free_gb" to appSettings.minFreeGb.first().toString(),
        "auto_backup" to appSettings.autoBackup.first().toString()
    )

    /** Emulators of the catalog and the frontends the app sets up, when installed, with their version names. */
    private fun installedApps(): List<ReportApp> {
        val known = EmulatorCatalog.all.flatMap { emulator -> emulator.variants.map { (it.label ?: emulator.label) to it.packageName } } +
            listOf("ES-DE" to "org.es_de.frontend", "iisu" to "com.iisulauncher")
        return known.distinctBy { it.second }.mapNotNull { (label, pkg) ->
            try {
                val info = context.packageManager.getPackageInfo(pkg, 0)
                ReportApp(label, pkg, info.versionName.orEmpty().take(32).ifBlank { "version unknown" })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun operationLines(): List<ReportHistoryLine> =
        operations.entries.value.sortedByDescending { it.time }.take(ProblemReport.HISTORY_LINES).map {
            ReportHistoryLine(it.time, it.kind, "${it.phase}|files=${maxOf(it.totalFiles, it.files.size)}")
        }

    /** The active profile's lines only, as the history screen shows them: kind, topic and reason codes. */
    private fun actionLines(): List<ReportHistoryLine> {
        val profile = profiles.currentIdNow()
        return actionLog.entries.value.orEmpty().filter { it.profileId == profile }.sortedByDescending { it.at }
            .take(ProblemReport.HISTORY_LINES)
            .map { ReportHistoryLine(it.at, it.kind.name, listOfNotNull(it.topic, it.reason).joinToString("|")) }
    }
}
