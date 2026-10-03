package com.cortinadev.dogmatix.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.SettingsRepository
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

@HiltViewModel
class ExtraSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val versionChecker: VersionCheckerService,
    private val diagnostics: DiagnosticsService
) : ViewModel() {

    val state: StateFlow<ExtraSettingsState> = combine(
        combine(settings.downloadWifiOnly, settings.downloadChargingOnly, settings.downloadNightOnly) { w, c, n -> Triple(w, c, n) },
        settings.downloadNightStart, settings.downloadNightEnd, settings.updatePreReleases
    ) { (wifi, charging, night), start, end, pre -> ExtraSettingsState(wifi, charging, night, start, end, pre) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExtraSettingsState())

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
                is VersionCheckerService.Result.Available -> ToastUtil.showInfo(app, app.getString(R.string.update_available, result.tag))
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
