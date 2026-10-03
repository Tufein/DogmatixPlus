package com.cortinadev.dogmatix.ui.screens.settings

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.common.Legend
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.TruncatedText
import com.cortinadev.dogmatix.ui.components.legendFor
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.screens.settings.components.ApiKeyDialog
import com.cortinadev.dogmatix.ui.screens.settings.components.DaijishoSetupDialog
import com.cortinadev.dogmatix.ui.screens.settings.components.FavoriteLanguagesDialog
import com.cortinadev.dogmatix.ui.screens.settings.components.maskedSecret
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.ui.common.GamepadLayout
import com.cortinadev.dogmatix.ui.theme.AccentPresets
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.DownloadPolicy
import com.cortinadev.dogmatix.util.ToastUtil
import com.cortinadev.dogmatix.util.TorrentConstants
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.ThemeMode
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import com.cortinadev.dogmatix.ui.screens.sources.SourcesViewModel
import com.cortinadev.dogmatix.ui.screens.sources.components.ConfirmDialog
import androidx.compose.ui.res.pluralStringResource
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

/** Languages offered in Settings; [tag] is empty for "follow the device". */
enum class AppLanguage(val tag: String, val label: Int) {
    SYSTEM("", R.string.language_system),
    EN("en", R.string.language_en),
    ES("es", R.string.language_es),
    NL("nl", R.string.language_nl),
    FR("fr", R.string.language_fr),
    DE("de", R.string.language_de),
    IT("it", R.string.language_it),
    PT("pt", R.string.language_pt);

    companion object {
        fun current(): AppLanguage {
            val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
            return entries.firstOrNull { it.tag == tag && it.tag.isNotEmpty() } ?: SYSTEM
        }
    }
}

private const val SPEED_STEP = 250
private const val SPEED_MAX = 5000

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel(),
    extra: ExtraSettingsViewModel = hiltViewModel()
) {
    val ui by viewModel.uiState.collectAsState()
    val more by extra.state.collectAsState()
    val v2 by extra.v2.collectAsState()
    val updateOffer by extra.updateOffer.collectAsState()
    val updateProgress by extra.updateProgress.collectAsState()
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    updateOffer?.let { tag ->
        ConfirmDialog(
            title = stringResource(R.string.update_offer_title, tag),
            message = stringResource(R.string.update_offer_message),
            confirmText = stringResource(R.string.update_offer_install),
            onConfirm = { extra.installUpdate(context) },
            onDismiss = extra::dismissUpdateOffer
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.onDownloadDirChanged(context, it.toString())
        }
    }
    val esdeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.onEsdeDirPicked(context, it.toString())
        }
    }
    // First run asks for the ES-DE folder; later runs reuse it and reconfigure directly.
    fun runEsdeSetup() {
        if (ui.esdeDirectory.isBlank()) esdeLauncher.launch(null) else viewModel.onConfigureEsde(context)
    }
    val iisuLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.onIisuDirPicked(context, it.toString())
        }
    }
    fun runIisuSetup() {
        if (ui.iisuDirectory.isBlank()) iisuLauncher.launch(null) else viewModel.onConfigureIisu(context)
    }

    // Backup: export into a document the user creates, restore from one they pick (after a confirmation).
    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportBackup(context, it.toString()) }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.readBackup(context, it.toString()) }
    }
    // The shell's instance, so the rescan after a restore survives leaving Settings.
    val sourcesViewModel: SourcesViewModel = hiltViewModel(LocalActivity.current as ComponentActivity)
    fun exportBackup() = backupExportLauncher.launch("dogmatix-backup-${LocalDate.now()}.json")
    val backupBusyMessage = stringResource(R.string.backup_import_busy)
    fun importBackup() {
        // A restore replaces every source: not while they are being scanned (the ViewModel checks again).
        if (sourcesViewModel.isRescanning.value) {
            ToastUtil.showInfo(context, backupBusyMessage)
            return
        }
        backupImportLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
    }
    val pendingRestore by viewModel.pendingRestore.collectAsState()
    pendingRestore?.let { pending ->
        val date = if (pending.createdAt > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(pending.createdAt)) else "?"
        ConfirmDialog(
            title = stringResource(R.string.backup_import_title),
            message = stringResource(R.string.backup_import_message, date, pending.appVersion),
            confirmText = stringResource(R.string.backup_import_action),
            onConfirm = { viewModel.restoreBackup(context) { sourcesViewModel.rescanAllSources() } },
            onDismiss = viewModel::dismissRestore
        )
    }

    fun cycleTheme(delta: Int) {
        val modes = ThemeMode.entries
        viewModel.onThemeModeChanged(context, modes[((ui.themeMode.ordinal + delta) % modes.size + modes.size) % modes.size])
    }
    fun cycleAccent(delta: Int) {
        val presets = AccentPresets.choices
        val current = presets.indexOf(ui.accent).coerceAtLeast(0)
        viewModel.onAccentChanged(context, presets[((current + delta) % presets.size + presets.size) % presets.size])
    }
    fun cycleGamepadLayout(delta: Int) {
        val layouts = GamepadLayout.entries
        viewModel.onGamepadLayoutChanged(context, layouts[((ui.gamepadLayout.ordinal + delta) % layouts.size + layouts.size) % layouts.size])
    }
    fun adjustConcurrent(delta: Int) =
        viewModel.onConcurrentDownloadsChanged(context, (ui.concurrentDownloads + delta).coerceIn(1, 10))
    // Fixed choices instead of free steps: the useful jumps are coarse (50 → 100 → … → no limit).
    fun adjustMaxSearchResults(delta: Int) {
        val choices = Constants.MAX_SEARCH_RESULTS_CHOICES
        val current = choices.indexOf(ui.maxSearchResults).takeIf { it >= 0 }
            ?: choices.indexOf(Constants.DEFAULT_MAX_SEARCH_RESULTS)
        viewModel.onMaxSearchResultsChanged(context, choices[(current + delta).coerceIn(0, choices.lastIndex)])
    }
    fun adjustMetadataTimeout(delta: Int) = viewModel.onMetadataTimeoutChanged(
        context, (ui.metadataTimeoutSeconds + delta * 10).coerceIn(TorrentConstants.MIN_METADATA_TIMEOUT_S, TorrentConstants.MAX_METADATA_TIMEOUT_S)
    )
    fun shiftNightStart(delta: Int) = extra.setNightWindow(context, DownloadPolicy.shift(more.nightStart, delta), more.nightEnd)
    fun shiftNightEnd(delta: Int) = extra.setNightWindow(context, more.nightStart, DownloadPolicy.shift(more.nightEnd, delta))
    val limitKb = if (ui.limitSpeed == Float.POSITIVE_INFINITY) 0 else ui.limitSpeed.toInt()
    fun adjustLimit(delta: Int) {
        val next = (limitKb + delta * SPEED_STEP).coerceIn(0, SPEED_MAX)
        viewModel.onLimitSpeedChanged(context, if (next == 0) Float.POSITIVE_INFINITY else next.toFloat())
    }

    val themeLabel = stringResource(ui.themeMode.labelRes) + if (ui.themeMode == ThemeMode.SYSTEM) {
        " · " + stringResource(if (LocalDogmatixTokens.current.isDark) R.string.theme_suffix_dark else R.string.theme_suffix_light)
    } else ""

    // App language: persisted by AppCompat (autoStoreLocales) and applied by recreating the activity.
    var appLanguage by remember { mutableStateOf(AppLanguage.current()) }
    fun cycleLanguage(delta: Int) {
        val entries = AppLanguage.entries
        appLanguage = entries[(appLanguage.ordinal + delta + entries.size) % entries.size]
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(appLanguage.tag))
    }

    var showAccentDialog by remember { mutableStateOf(false) }
    if (showAccentDialog) {
        AccentDialog(selected = ui.accent, onPick = { viewModel.onAccentChanged(context, it); showAccentDialog = false }, onDismiss = { showAccentDialog = false })
    }

    var showLanguagesDialog by remember { mutableStateOf(false) }
    if (showLanguagesDialog) {
        val available by viewModel.availableLanguages.collectAsState()
        LaunchedEffect(Unit) { viewModel.loadAvailableLanguages() }
        FavoriteLanguagesDialog(
            available = available,
            favorites = ui.favoriteLanguages,
            onChange = { viewModel.onFavoriteLanguagesChanged(context, it) },
            onDismiss = { showLanguagesDialog = false }
        )
    }

    val debrid = ui.debridProvider
    fun cycleDebrid(delta: Int) {
        val providers = DebridProvider.entries
        viewModel.onDebridProviderChanged(context, providers[((debrid.ordinal + delta) % providers.size + providers.size) % providers.size])
    }
    val debridKey = when (debrid) {
        DebridProvider.TORBOX -> ui.torboxApiKey
        DebridProvider.REAL_DEBRID -> ui.realDebridApiKey
        DebridProvider.NONE -> ""
    }
    val debridKeyTitle = stringResource(R.string.settings_debrid_key, debrid.label)
    val daijishoSetup by viewModel.daijishoSetup.collectAsState()
    daijishoSetup?.let { setup ->
        DaijishoSetupDialog(setup = setup, onDismiss = viewModel::onDaijishoSetupDismissed)
    }

    var showDebridKeyDialog by remember { mutableStateOf(false) }
    if (showDebridKeyDialog && debrid != DebridProvider.NONE) {
        ApiKeyDialog(
            title = debridKeyTitle,
            hint = stringResource(if (debrid == DebridProvider.TORBOX) R.string.settings_torbox_key_dialog_hint else R.string.settings_realdebrid_key_dialog_hint),
            value = debridKey,
            onTest = { viewModel.testDebridApiKey(context, debrid, it) },
            onSave = { viewModel.onDebridApiKeyChanged(context, debrid, it) },
            onDismiss = { showDebridKeyDialog = false }
        )
    }

    // Portrait shows the rows in this order; landscape splits them into two columns (`right`),
    // so related rows (debrid service + its API key) stay stacked in the same column.
    val ordered: List<SettingsRow> = listOf(
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_download_directory),
                hint = ui.downloadDirectory.ifBlank { stringResource(R.string.settings_not_set) },
                onClick = { launcher.launch(null) }
            ) {
                PillButton(stringResource(R.string.settings_change)) { launcher.launch(null) }
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_theme),
                hint = stringResource(R.string.settings_theme_hint),
                onClick = { cycleTheme(1) },
                onAdjust = ::cycleTheme
            ) {
                Stepper(themeLabel, onDecrement = { cycleTheme(-1) }, onIncrement = { cycleTheme(1) }, valueWidth = 110.dp)
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_language),
                hint = stringResource(R.string.settings_language_hint),
                onClick = { cycleLanguage(1) },
                onAdjust = ::cycleLanguage
            ) {
                Stepper(stringResource(appLanguage.label), onDecrement = { cycleLanguage(-1) }, onIncrement = { cycleLanguage(1) }, valueWidth = 96.dp)
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_accent),
                hint = stringResource(R.string.settings_accent_hint),
                onClick = { cycleAccent(1) },
                onAdjust = ::cycleAccent
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AccentSwatch(ui.accent, if (isLandscape) 24.dp else 32.dp, selected = true, onClick = { showAccentDialog = true })
                    PillButton(stringResource(R.string.settings_change)) { showAccentDialog = true }
                }
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_gamepad_layout),
                hint = stringResource(R.string.settings_gamepad_layout_hint),
                onClick = { cycleGamepadLayout(1) },
                onAdjust = ::cycleGamepadLayout
            ) {
                Stepper(
                    stringResource(ui.gamepadLayout.labelRes),
                    onDecrement = { cycleGamepadLayout(-1) },
                    onIncrement = { cycleGamepadLayout(1) },
                    valueWidth = 110.dp
                )
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_swap_face_buttons),
                hint = stringResource(R.string.settings_swap_face_buttons_hint),
                onClick = { viewModel.onSwapFaceButtonsChanged(context, !ui.swapFaceButtons) },
                onAdjust = { viewModel.onSwapFaceButtonsChanged(context, it > 0) }
            ) {
                ThemedSwitch(ui.swapFaceButtons) { viewModel.onSwapFaceButtonsChanged(context, it) }
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_bold_focus),
                hint = stringResource(R.string.settings_bold_focus_hint),
                onClick = { extra.setBoldFocus(context, !v2.boldFocus) },
                onAdjust = { extra.setBoldFocus(context, it > 0) }
            ) { ThemedSwitch(v2.boldFocus) { extra.setBoldFocus(context, it) } }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_concurrent_label),
                hint = null,
                onClick = { adjustConcurrent(1) },
                onAdjust = ::adjustConcurrent
            ) {
                Stepper(ui.concurrentDownloads.toString(), onDecrement = { adjustConcurrent(-1) }, onIncrement = { adjustConcurrent(1) })
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_limit_label),
                hint = stringResource(R.string.settings_limit_hint),
                onClick = { adjustLimit(1) },
                onAdjust = ::adjustLimit
            ) {
                Stepper(
                    if (limitKb == 0) stringResource(R.string.settings_unrestricted) else "$limitKb KB/s",
                    onDecrement = { adjustLimit(-1) },
                    onIncrement = { adjustLimit(1) },
                    valueWidth = 110.dp
                )
            }
        },
        SettingsRow(right = true, visible = limitKb > 0) {
            SettingRow(
                title = stringResource(R.string.settings_limit_day_only),
                hint = stringResource(R.string.settings_limit_day_only_hint, DownloadPolicy.formatMinutes(more.nightStart), DownloadPolicy.formatMinutes(more.nightEnd)),
                onClick = { extra.setSpeedLimitDayOnly(context, !v2.speedLimitDayOnly) },
                onAdjust = { extra.setSpeedLimitDayOnly(context, it > 0) }
            ) { ThemedSwitch(v2.speedLimitDayOnly) { extra.setSpeedLimitDayOnly(context, it) } }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_metadata_timeout),
                hint = stringResource(R.string.settings_metadata_timeout_hint),
                onClick = { adjustMetadataTimeout(1) },
                onAdjust = ::adjustMetadataTimeout
            ) {
                Stepper(stringResource(R.string.seconds_short, ui.metadataTimeoutSeconds), onDecrement = { adjustMetadataTimeout(-1) }, onIncrement = { adjustMetadataTimeout(1) })
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_auto_unzip),
                hint = stringResource(R.string.settings_auto_unzip_hint),
                onClick = { viewModel.onAutoUnzipChanged(context, !ui.autoUnzip) },
                onAdjust = { viewModel.onAutoUnzipChanged(context, it > 0) }
            ) {
                ThemedSwitch(ui.autoUnzip) { viewModel.onAutoUnzipChanged(context, it) }
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_separate_by_console),
                hint = stringResource(R.string.settings_separate_hint),
                onClick = { viewModel.onSeparateByConsoleChanged(context, !ui.separateByConsole) },
                onAdjust = { viewModel.onSeparateByConsoleChanged(context, it > 0) }
            ) {
                ThemedSwitch(ui.separateByConsole) { viewModel.onSeparateByConsoleChanged(context, it) }
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_debrid),
                hint = stringResource(R.string.settings_debrid_hint),
                onClick = { cycleDebrid(1) },
                onAdjust = ::cycleDebrid
            ) {
                Stepper(debrid.label, onDecrement = { cycleDebrid(-1) }, onIncrement = { cycleDebrid(1) }, valueWidth = 110.dp)
            }
        },
        SettingsRow(right = true, visible = debrid != DebridProvider.NONE) {
            SettingRow(
                title = debridKeyTitle,
                hint = maskedSecret(debridKey),
                onClick = { showDebridKeyDialog = true }
            ) {
                PillButton(stringResource(R.string.settings_change)) { showDebridKeyDialog = true }
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_dl_wifi),
                hint = stringResource(R.string.settings_dl_wifi_hint),
                onClick = { extra.setWifiOnly(context, !more.wifiOnly) },
                onAdjust = { extra.setWifiOnly(context, it > 0) }
            ) { ThemedSwitch(more.wifiOnly) { extra.setWifiOnly(context, it) } }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_dl_charging),
                hint = stringResource(R.string.settings_dl_charging_hint),
                onClick = { extra.setChargingOnly(context, !more.chargingOnly) },
                onAdjust = { extra.setChargingOnly(context, it > 0) }
            ) { ThemedSwitch(more.chargingOnly) { extra.setChargingOnly(context, it) } }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_dl_night),
                hint = stringResource(R.string.settings_dl_night_hint, DownloadPolicy.formatMinutes(more.nightStart), DownloadPolicy.formatMinutes(more.nightEnd)),
                onClick = { extra.setNightOnly(context, !more.nightOnly) },
                onAdjust = { extra.setNightOnly(context, it > 0) }
            ) { ThemedSwitch(more.nightOnly) { extra.setNightOnly(context, it) } }
        },
        SettingsRow(right = true, visible = more.nightOnly) {
            SettingRow(
                title = stringResource(R.string.settings_dl_night_start), hint = null,
                onClick = { shiftNightStart(1) }, onAdjust = ::shiftNightStart
            ) { Stepper(DownloadPolicy.formatMinutes(more.nightStart), onDecrement = { shiftNightStart(-1) }, onIncrement = { shiftNightStart(1) }, valueWidth = 72.dp) }
        },
        SettingsRow(right = false, visible = more.nightOnly) {
            SettingRow(
                title = stringResource(R.string.settings_dl_night_end), hint = null,
                onClick = { shiftNightEnd(1) }, onAdjust = ::shiftNightEnd
            ) { Stepper(DownloadPolicy.formatMinutes(more.nightEnd), onDecrement = { shiftNightEnd(-1) }, onIncrement = { shiftNightEnd(1) }, valueWidth = 72.dp) }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_autoscan),
                hint = if (v2.autoScanLast > 0) stringResource(R.string.settings_autoscan_last, android.text.format.DateUtils.getRelativeTimeSpanString(v2.autoScanLast).toString())
                       else stringResource(R.string.settings_autoscan_hint),
                onClick = { extra.setAutoScan(context, !v2.autoScan) },
                onAdjust = { extra.setAutoScan(context, it > 0) }
            ) { ThemedSwitch(v2.autoScan) { extra.setAutoScan(context, it) } }
        },
        SettingsRow(right = false, visible = v2.autoScan) {
            SettingRow(
                title = stringResource(R.string.settings_autoscan_every), hint = null,
                onClick = { extra.shiftAutoScanHours(context, 1) }, onAdjust = { extra.shiftAutoScanHours(context, it) }
            ) {
                Stepper(
                    if (v2.autoScanHours % 24 == 0) pluralStringResource(R.plurals.settings_days, v2.autoScanHours / 24, v2.autoScanHours / 24)
                    else stringResource(R.string.hours_short, v2.autoScanHours),
                    onDecrement = { extra.shiftAutoScanHours(context, -1) }, onIncrement = { extra.shiftAutoScanHours(context, 1) }, valueWidth = 96.dp
                )
            }
        },
        SettingsRow(right = false, visible = v2.autoScan) {
            SettingRow(
                title = stringResource(R.string.settings_autoscan_wifi), hint = null,
                onClick = { extra.setAutoScanWifi(context, !v2.autoScanWifi) }, onAdjust = { extra.setAutoScanWifi(context, it > 0) }
            ) { ThemedSwitch(v2.autoScanWifi) { extra.setAutoScanWifi(context, it) } }
        },
        SettingsRow(right = false, visible = v2.autoScan) {
            SettingRow(
                title = stringResource(R.string.settings_autoscan_charging), hint = null,
                onClick = { extra.setAutoScanCharging(context, !v2.autoScanCharging) }, onAdjust = { extra.setAutoScanCharging(context, it > 0) }
            ) { ThemedSwitch(v2.autoScanCharging) { extra.setAutoScanCharging(context, it) } }
        },
        SettingsRow(right = false, visible = v2.autoScan) {
            SettingRow(
                title = stringResource(R.string.settings_autoscan_night),
                hint = stringResource(R.string.settings_autoscan_night_hint, DownloadPolicy.formatMinutes(more.nightStart), DownloadPolicy.formatMinutes(more.nightEnd)),
                onClick = { extra.setAutoScanNight(context, !v2.autoScanNight) }, onAdjust = { extra.setAutoScanNight(context, it > 0) }
            ) { ThemedSwitch(v2.autoScanNight) { extra.setAutoScanNight(context, it) } }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_tools),
                hint = stringResource(R.string.settings_tools_hint),
                onClick = { navController.navigate(NavRoutes.Tools.route) }
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_overview),
                hint = stringResource(R.string.settings_overview_hint),
                onClick = { navController.navigate(NavRoutes.Overview.route) }
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_duplicates),
                hint = stringResource(R.string.settings_duplicates_hint),
                onClick = { navController.navigate(NavRoutes.Duplicates.route) }
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_backup_export),
                hint = stringResource(R.string.settings_backup_export_hint),
                onClick = ::exportBackup
            ) {
                PillButton(stringResource(R.string.settings_backup_export_action), ::exportBackup)
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_backup_import),
                hint = stringResource(R.string.settings_backup_import_hint),
                onClick = ::importBackup
            ) {
                PillButton(stringResource(R.string.settings_backup_import_action), ::importBackup)
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_max_results),
                hint = stringResource(R.string.settings_max_results_hint),
                onClick = { adjustMaxSearchResults(1) },
                onAdjust = ::adjustMaxSearchResults
            ) {
                Stepper(
                    if (ui.maxSearchResults <= 0) stringResource(R.string.settings_unlimited) else ui.maxSearchResults.toString(),
                    onDecrement = { adjustMaxSearchResults(-1) },
                    onIncrement = { adjustMaxSearchResults(1) },
                    valueWidth = 110.dp
                )
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_favorite_languages),
                hint = ui.favoriteLanguages.sorted().joinToString(" · ")
                    .ifBlank { stringResource(R.string.settings_favorite_languages_hint) },
                onClick = { showLanguagesDialog = true }
            ) {
                PillButton(stringResource(R.string.settings_change)) { showLanguagesDialog = true }
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_frontend_shortcuts),
                hint = stringResource(R.string.settings_frontend_shortcuts_hint),
                onClick = { viewModel.onDeployFrontendShortcuts(context) }
            ) {
                PillButton(stringResource(R.string.settings_frontend_shortcuts_action)) {
                    viewModel.onDeployFrontendShortcuts(context)
                }
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_esde),
                hint = ui.esdeDirectory.ifBlank { stringResource(R.string.settings_esde_hint) },
                onClick = ::runEsdeSetup
            ) {
                PillButton(stringResource(R.string.settings_esde_action), ::runEsdeSetup)
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_iisu),
                hint = ui.iisuDirectory.ifBlank { stringResource(R.string.settings_iisu_hint) },
                onClick = ::runIisuSetup
            ) {
                PillButton(stringResource(R.string.settings_iisu_action), ::runIisuSetup)
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_daijisho),
                hint = stringResource(R.string.settings_daijisho_hint),
                onClick = { viewModel.onPrepareDaijisho(context) }
            ) {
                PillButton(stringResource(R.string.settings_daijisho_action)) { viewModel.onPrepareDaijisho(context) }
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_romm),
                hint = ui.rommUrl.ifBlank { stringResource(R.string.settings_romm_hint) },
                onClick = { navController.navigate(NavRoutes.Romm.route) }
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_save_sync),
                hint = stringResource(R.string.settings_save_sync_hint),
                onClick = { navController.navigate(NavRoutes.SaveSync.route) }
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_prereleases),
                hint = stringResource(R.string.settings_prereleases_hint),
                onClick = { extra.setPreReleases(context, !more.preReleases) },
                onAdjust = { extra.setPreReleases(context, it > 0) }
            ) { ThemedSwitch(more.preReleases) { extra.setPreReleases(context, it) } }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_update_check),
                hint = updateProgress?.let { stringResource(R.string.update_downloading, (it * 100).toInt()) } ?: stringResource(R.string.settings_update_check_hint),
                onClick = { extra.checkForUpdates(context) }
            ) { PillButton(stringResource(R.string.settings_update_check_action)) { extra.checkForUpdates(context) } }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_diagnostics),
                hint = stringResource(R.string.settings_diagnostics_hint),
                onClick = { extra.shareDiagnostics(context) }
            ) { PillButton(stringResource(R.string.settings_diagnostics_action)) { extra.shareDiagnostics(context) } }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_about),
                hint = stringResource(R.string.credits_fork_name) + " · " + stringResource(R.string.credits_original_name),
                onClick = { navController.navigate(NavRoutes.Contact.route) }
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
    // Rows that do not apply right now are left out, so the two columns have no gaps.
    val shown = ordered.filter { it.visible }
    val rows: List<@Composable () -> Unit> = if (!isLandscape) shown.map { it.content } else {
        val left = shown.filter { !it.right }.map { it.content }
        val right = shown.filter { it.right }.map { it.content }
        (0 until maxOf(left.size, right.size)).flatMap { i -> listOf(left.getOrNull(i) ?: {}, right.getOrNull(i) ?: {}) }
    }

    // LB / RB (landscape): hop between the two columns, staying on the same grid row.
    val columns = if (isLandscape) 2 else 1
    val rowFocus = remember(rows.size) { List(rows.size) { FocusRequester() } }
    var focusedIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(isLandscape) {
        if (!isLandscape) return@LaunchedEffect
        Gamepad.presses.collect { button ->
            if (button != GamepadButton.PREV_PANEL && button != GamepadButton.NEXT_PANEL) return@collect
            val current = focusedIndex
            val target = when {
                current < 0 -> 0
                current % columns == 0 -> current + 1   // left column → right
                else -> current - 1                      // right column → left
            }.coerceIn(0, rows.lastIndex)
            runCatching { rowFocus[target].requestFocus() }
        }
    }
    if (isLandscape) {
        val base = legendFor(NavRoutes.Settings.route)
        val column = LegendEntry("LB · RB", stringResource(R.string.pad_column))
        val legend = remember(base, column) { Legend(base.toMutableList().also { it.add(it.lastIndex, column) }) }
        LaunchedEffect(legend) { Gamepad.legendOverride.value = legend }
        // Only clear our own legend: the previous screen's onDispose can run after ours is set.
        DisposableEffect(legend) { onDispose { if (Gamepad.legendOverride.value === legend) Gamepad.legendOverride.value = null } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 12.dp)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(rows.size) { index ->
                Box(
                    modifier = Modifier
                        .focusRequester(rowFocus[index])
                        .onFocusChanged { if (it.hasFocus) focusedIndex = index }
                ) { rows[index]() }
            }
        }
    }
}

/** One Settings entry; [right] puts it in the right-hand column of the landscape grid. */
private class SettingsRow(val right: Boolean, val visible: Boolean = true, val content: @Composable () -> Unit)

/**
 * A focusable settings row. Click / A runs [onClick]; while focused, D-pad left/right
 * calls [onAdjust] with -1 / +1 so steppers, switches and swatches work from a gamepad.
 */
@Composable
internal fun SettingRow(
    title: String,
    hint: String?,
    onClick: () -> Unit,
    onAdjust: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit
) {
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .focusRing(source)
            .onPreviewKeyEvent { event ->
                if (onAdjust == null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onAdjust(-1); true }
                    Key.DirectionRight -> { onAdjust(1); true }
                    else -> false
                }
            }
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            TruncatedText(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        trailing()
    }
}

@Composable
internal fun PillButton(label: String, onClick: () -> Unit) {
    val source = rememberFocusSource()
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** One accent colour as a circle; Material You shows as a four-colour wheel. */
@Composable
private fun AccentSwatch(color: Color, size: androidx.compose.ui.unit.Dp, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val fill = if (color == AccentPresets.dynamic) Modifier.background(
        androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color(0xFFFF7F00), Color(0xFFD4E157), Color(0xFF3CC8FF), Color(0xFFE57BFF), Color(0xFFFF7F00)))
    ) else Modifier.background(color)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(fill)
            .border(width = if (selected) 2.dp else 0.dp, color = if (selected) scheme.onSurface else Color.Transparent, shape = CircleShape)
            .focusRing(source, size / 2)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    )
}

/** Every accent colour, plus Material You where the system has it. */
@Composable
private fun AccentDialog(selected: Color, onPick: (Color) -> Unit, onDismiss: () -> Unit) {
    val choices = AccentPresets.choices
    val firstFocus = com.cortinadev.dogmatix.ui.components.rememberInitialFocus()
    androidx.compose.material3.AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accent_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                choices.chunked(7).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { i, color ->
                            Box(modifier = if (rowIndex == 0 && i == 0) Modifier.focusRequester(firstFocus) else Modifier) {
                                AccentSwatch(color, 40.dp, selected = color == selected, onClick = { onPick(color) })
                            }
                        }
                    }
                }
                if (AccentPresets.dynamicAvailable) Text(
                    stringResource(R.string.accent_dynamic_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { com.cortinadev.dogmatix.ui.components.DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss) }
    )
}

@Composable
internal fun ThemedSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = scheme.surface,
            checkedTrackColor = scheme.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = scheme.surface,
            uncheckedTrackColor = LocalDogmatixTokens.current.knobOff,
            uncheckedBorderColor = Color.Transparent
        )
    )
}
