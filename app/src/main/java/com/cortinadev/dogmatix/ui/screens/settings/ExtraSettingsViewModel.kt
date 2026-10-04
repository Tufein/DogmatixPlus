package com.cortinadev.dogmatix.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.service.DiagnosticsService
import com.cortinadev.dogmatix.data.service.VersionCheckerService
import com.cortinadev.dogmatix.ui.common.executeWithToast
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The settings added in 1.2: download schedule, update channel and the diagnostics report. */
data class ExtraSettingsState(
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val nightOnly: Boolean = false,
    val nightStart: Int = 23 * 60,
    val nightEnd: Int = 7 * 60,
    val preReleases: Boolean = false
)

/** The settings added in 2.0 (see [AppSettings]). */
data class V2SettingsState(
    val autoScan: Boolean = false,
    val autoScanHours: Int = 24,
    val autoScanWifi: Boolean = true,
    val autoScanCharging: Boolean = true,
    val autoScanNight: Boolean = true,
    val autoScanLast: Long = 0L,
    val speedLimitDayOnly: Boolean = false,
    val boldFocus: Boolean = false
)

/** The settings added in 2.5. */
data class V25SettingsState(
    val minFreeGb: Int = 0,
    val autoBackup: Boolean = false,
    val autoBackupDir: String = "",
    val autoBackupLast: Long = 0L,
    val secondScreen: Boolean = true
)

data class V30SettingsState(
    val resume: Boolean = true,
    val requeue: Boolean = true,
    val perServer: Int = 0,
    val esdeArtwork: Boolean = false,
    val wishlistAuto: Boolean = false,
    val autoM3u: Boolean = true,
    val queueSummary: Boolean = true
)

/** The settings added in 3.2. */
data class V32SettingsState(val pegasusArtwork: Boolean = false, val retroArchThumbnailsDir: String = "")

@HiltViewModel
class ExtraSettingsViewModel @Inject constructor(
    private val autoBackupScheduler: com.cortinadev.dogmatix.data.service.AutoBackupScheduler,
    private val settings: SettingsRepository,
    private val appSettings: AppSettings,
    private val updateInstaller: com.cortinadev.dogmatix.data.service.UpdateInstaller,
    private val versionChecker: VersionCheckerService,
    private val diagnostics: DiagnosticsService,
    private val profiles: com.cortinadev.dogmatix.data.service.ProfileService
) : ViewModel() {

    /** Name of the active profile, or null when everything is shown. */
    val activeProfileName: StateFlow<String?> = combine(profiles.profiles, profiles.activeId) { list, id -> list.firstOrNull { it.id == id }?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)


    val state: StateFlow<ExtraSettingsState> = combine(
        combine(settings.downloadWifiOnly, settings.downloadChargingOnly, settings.downloadNightOnly) { w, c, n -> Triple(w, c, n) },
        settings.downloadNightStart, settings.downloadNightEnd, settings.updatePreReleases
    ) { (wifi, charging, night), start, end, pre -> ExtraSettingsState(wifi, charging, night, start, end, pre) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExtraSettingsState())

    val v2: StateFlow<V2SettingsState> = combine(
        combine(appSettings.autoScan, appSettings.autoScanHours, appSettings.autoScanWifiOnly, appSettings.autoScanCharging) { a, h, w, c -> listOf(a, h, w, c) },
        appSettings.autoScanNightOnly, appSettings.autoScanLast, appSettings.speedLimitDayOnly, appSettings.boldFocus
    ) { (a, h, w, c), night, last, dayOnly, bold ->
        V2SettingsState(a as Boolean, h as Int, w as Boolean, c as Boolean, night, last, dayOnly, bold)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), V2SettingsState())

    val v25: StateFlow<V25SettingsState> = combine(
        appSettings.minFreeGb, appSettings.autoBackup, appSettings.autoBackupDir, appSettings.autoBackupLast, appSettings.secondScreen
    ) { gb, backup, dir, last, second -> V25SettingsState(gb, backup, dir, last, second) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), V25SettingsState())

    val v30: StateFlow<V30SettingsState> = combine(
        combine(appSettings.resumeDownloads, appSettings.requeueAfterRestart, appSettings.perServerLimit) { r, q, p -> Triple(r, q, p) },
        appSettings.esdeArtwork, appSettings.wishlistAutoDownload,
        combine(appSettings.autoM3u, appSettings.queueSummary) { m, s -> m to s }
    ) { (r, q, p), art, wish, (m3u, summary) -> V30SettingsState(r, q, p, art, wish, m3u, summary) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), V30SettingsState())

    val v32: StateFlow<V32SettingsState> = combine(appSettings.pegasusArtwork, appSettings.retroArchThumbnailsDir) { p, r -> V32SettingsState(p, r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), V32SettingsState())

    fun setPegasusArtwork(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setPegasusArtwork(on) }
    fun setRetroArchThumbnailsDir(context: Context, uri: String) = executeWithToast(context, TAG) { appSettings.setRetroArchThumbnailsDir(uri) }

    fun setResume(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setResumeDownloads(on) }
    fun setRequeue(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setRequeueAfterRestart(on) }
    fun shiftPerServer(context: Context, delta: Int) = executeWithToast(context, TAG) {
        val choices = listOf(0, 1, 2, 3, 4, 6)
        val i = choices.indexOf(v30.value.perServer).takeIf { it >= 0 } ?: 0
        appSettings.setPerServerLimit(choices[(i + delta).coerceIn(0, choices.lastIndex)])
    }
    fun setEsdeArtwork(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setEsdeArtwork(on) }
    fun setWishlistAuto(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setWishlistAutoDownload(on) }
    fun setAutoM3u(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setAutoM3u(on) }
    fun setQueueSummary(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setQueueSummary(on) }

    fun shiftMinFree(context: Context, delta: Int) = executeWithToast(context, TAG) {
        val choices = listOf(0, 1, 2, 5, 10, 20, 50)
        val i = choices.indexOf(v25.value.minFreeGb).takeIf { it >= 0 } ?: 0
        appSettings.setMinFreeGb(choices[(i + delta).coerceIn(0, choices.lastIndex)])
    }
    fun setAutoBackup(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setAutoBackup(on) }
    fun setAutoBackupDir(context: Context, uri: String) = executeWithToast(context, TAG) { appSettings.setAutoBackupDir(uri) }
    fun setSecondScreen(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setSecondScreen(on) }

    /** Writes an automatic backup right away (to check the folder works). */
    fun backupNow(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            val name = runCatching { autoBackupScheduler.runIfDue(force = true) }.getOrNull()
            if (name != null) ToastUtil.showSuccess(app, app.getString(R.string.auto_backup_done, name))
            else ToastUtil.showError(app, app.getString(R.string.auto_backup_failed))
        }
    }

    fun setAutoScan(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setAutoScan(on) }
    fun setAutoScanWifi(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setAutoScanWifiOnly(on) }
    fun setAutoScanCharging(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setAutoScanCharging(on) }
    fun setAutoScanNight(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setAutoScanNightOnly(on) }
    fun shiftAutoScanHours(context: Context, delta: Int) = executeWithToast(context, TAG) {
        val choices = com.cortinadev.dogmatix.util.AutoScanPolicy.INTERVALS
        val i = choices.indexOf(v2.value.autoScanHours).takeIf { it >= 0 } ?: 1
        appSettings.setAutoScanHours(choices[(i + delta).coerceIn(0, choices.lastIndex)])
    }
    fun setSpeedLimitDayOnly(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setSpeedLimitDayOnly(on) }
    fun setBoldFocus(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setBoldFocus(on) }

    /** Progress of an update download (0..1), or null when none runs. */
    val updateProgress: StateFlow<Float?> = updateInstaller.progress

    /** The newer release found by the last check, offered for installation; null when none. */
    private val _offer = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val updateOffer: StateFlow<String?> = _offer

    fun dismissUpdateOffer() { _offer.value = null }

    /** Downloads the release APK, checks it and hands it to the system installer. */
    fun installUpdate(context: Context) {
        val tag = _offer.value ?: return
        _offer.value = null
        val app = context.applicationContext
        viewModelScope.launch {
            val error = updateInstaller.downloadAndInstall(tag)
            if (error != null) ToastUtil.showError(app, app.getString(R.string.update_install_failed, error))
        }
    }

    fun setWifiOnly(context: Context, on: Boolean) = executeWithToast(context, TAG) { settings.setDownloadWifiOnly(on) }
    fun setChargingOnly(context: Context, on: Boolean) = executeWithToast(context, TAG) { settings.setDownloadChargingOnly(on) }
    fun setNightOnly(context: Context, on: Boolean) = executeWithToast(context, TAG) { settings.setDownloadNightOnly(on) }
    fun setNightWindow(context: Context, start: Int, end: Int) = executeWithToast(context, TAG) { settings.setDownloadNightWindow(start, end) }
    fun setPreReleases(context: Context, on: Boolean) = executeWithToast(context, TAG) { settings.setUpdatePreReleases(on) }

    /** Looks at the releases now and says what it found. */
    fun checkForUpdates(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            when (val result = versionChecker.check(app)) {
                is VersionCheckerService.Result.Available -> _offer.value = result.tag
                is VersionCheckerService.Result.UpToDate -> ToastUtil.showSuccess(app, app.getString(R.string.update_none, result.tag))
                VersionCheckerService.Result.Failed -> ToastUtil.showError(app, app.getString(R.string.update_failed))
            }
        }
    }

    /** Builds the diagnostics report and opens the share sheet. */
    fun shareDiagnostics(context: Context) {
        val app = context.applicationContext
        val title = context.getString(R.string.diagnostics_share_title)
        viewModelScope.launch {
            runCatching { diagnostics.share(title) }.onFailure { ToastUtil.showError(app, app.getString(R.string.diagnostics_failed)) }
        }
    }

    private companion object { const val TAG = "ExtraSettingsViewModel" }
}
