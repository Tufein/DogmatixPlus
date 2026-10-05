package com.cortinadev.dogmatix.ui.screens.settings

import android.content.Intent
import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
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
import androidx.compose.runtime.withFrameNanos
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
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.ui.common.GamepadLayout
import com.cortinadev.dogmatix.ui.theme.AccentPresets
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.DownloadPolicy
import com.cortinadev.dogmatix.util.FileParsingUtils
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
    val schedule by extra.schedule.collectAsState()
    val queue by extra.queue.collectAsState()
    val autoScan by extra.autoScan.collectAsState()
    val afterDownload by extra.afterDownload.collectAsState()
    val appPrefs by extra.appPrefs.collectAsState()
    val activeProfileName by extra.activeProfileName.collectAsState()
    val updateOffer by extra.updateOffer.collectAsState()
    val updateProgress by extra.updateProgress.collectAsState()
    val context = LocalContext.current
    val backupDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            extra.setAutoBackupDir(context, it.toString())
        }
    }
    val retroArchThumbsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            extra.setRetroArchThumbnailsDir(context, it.toString())
        }
    }
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
    fun shiftNightStart(delta: Int) = extra.setNightWindow(context, DownloadPolicy.shift(schedule.nightStart, delta), schedule.nightEnd)
    fun shiftNightEnd(delta: Int) = extra.setNightWindow(context, schedule.nightStart, DownloadPolicy.shift(schedule.nightEnd, delta))
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

    var showCocoonHelp by remember { mutableStateOf(false) }
    if (showCocoonHelp) {
        AlertDialog(
            modifier = Modifier.closeOnGamepadB { showCocoonHelp = false },
            onDismissRequest = { showCocoonHelp = false },
            title = { Text(stringResource(R.string.cocoon_dialog_title)) },
            text = { Text(stringResource(R.string.cocoon_dialog_body)) },
            confirmButton = { DialogButton(stringResource(R.string.dialog_ok), onClick = { showCocoonHelp = false }, initialFocus = rememberInitialFocus()) }
        )
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

    // The rows per group (see [SettingsSection]), in the order they are shown.
    val ordered: List<SettingsRow> = listOf(
        SettingsRow(SettingsSection.LOOK) {
            SettingRow(
                title = stringResource(R.string.settings_theme),
                hint = stringResource(R.string.settings_theme_hint),
                onClick = { cycleTheme(1) },
                onAdjust = ::cycleTheme
            ) {
                Stepper(themeLabel, onDecrement = { cycleTheme(-1) }, onIncrement = { cycleTheme(1) }, valueWidth = 110.dp)
            }
        },
        SettingsRow(SettingsSection.LOOK) {
            SettingRow(
                title = stringResource(R.string.settings_language),
                hint = stringResource(R.string.settings_language_hint),
                onClick = { cycleLanguage(1) },
                onAdjust = ::cycleLanguage
            ) {
                Stepper(stringResource(appLanguage.label), onDecrement = { cycleLanguage(-1) }, onIncrement = { cycleLanguage(1) }, valueWidth = 96.dp)
            }
        },
        SettingsRow(SettingsSection.LOOK) {
            SettingRow(
                title = stringResource(R.string.settings_text_size),
                hint = stringResource(R.string.settings_text_size_hint),
                onClick = { extra.shiftTextSize(context, 1) },
                onAdjust = { extra.shiftTextSize(context, it) }
            ) {
                Stepper("${appPrefs.textSize} %", onDecrement = { extra.shiftTextSize(context, -1) }, onIncrement = { extra.shiftTextSize(context, 1) }, valueWidth = 96.dp)
            }
        },
        SettingsRow(SettingsSection.LOOK) {
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
        SettingsRow(SettingsSection.LOOK) {
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
        SettingsRow(SettingsSection.LOOK) {
            SettingRow(
                title = stringResource(R.string.settings_swap_face_buttons),
                hint = stringResource(R.string.settings_swap_face_buttons_hint),
                onClick = { viewModel.onSwapFaceButtonsChanged(context, !ui.swapFaceButtons) },
                onAdjust = { viewModel.onSwapFaceButtonsChanged(context, it > 0) }
            ) {
                ThemedSwitch(ui.swapFaceButtons) { viewModel.onSwapFaceButtonsChanged(context, it) }
            }
        },
        SettingsRow(SettingsSection.LOOK) {
            SettingRow(
                title = stringResource(R.string.settings_second_screen),
                hint = stringResource(R.string.settings_second_screen_hint),
                onClick = { extra.setSecondScreen(context, !appPrefs.secondScreen) },
                onAdjust = { extra.setSecondScreen(context, it > 0) }
            ) { ThemedSwitch(appPrefs.secondScreen) { extra.setSecondScreen(context, it) } }
        },
        SettingsRow(SettingsSection.LOOK) {
            SettingRow(
                title = stringResource(R.string.settings_bold_focus),
                hint = stringResource(R.string.settings_bold_focus_hint),
                onClick = { extra.setBoldFocus(context, !appPrefs.boldFocus) },
                onAdjust = { extra.setBoldFocus(context, it > 0) }
            ) { ThemedSwitch(appPrefs.boldFocus) { extra.setBoldFocus(context, it) } }
        },
        SettingsRow(SettingsSection.DOWNLOADS) {
            SettingRow(
                title = stringResource(R.string.settings_download_directory),
                hint = ui.downloadDirectory.ifBlank { stringResource(R.string.settings_not_set) },
                onClick = { launcher.launch(null) }
            ) {
                PillButton(stringResource(R.string.settings_change)) { launcher.launch(null) }
            }
        },
        SettingsRow(SettingsSection.DOWNLOADS) {
            SettingRow(
                title = stringResource(R.string.settings_separate_by_console),
                hint = stringResource(R.string.settings_separate_hint),
                onClick = { viewModel.onSeparateByConsoleChanged(context, !ui.separateByConsole) },
                onAdjust = { viewModel.onSeparateByConsoleChanged(context, it > 0) }
            ) {
                ThemedSwitch(ui.separateByConsole) { viewModel.onSeparateByConsoleChanged(context, it) }
            }
        },
        SettingsRow(SettingsSection.DOWNLOADS) {
            SettingRow(
                title = stringResource(R.string.settings_concurrent_label),
                hint = null,
                onClick = { adjustConcurrent(1) },
                onAdjust = ::adjustConcurrent
            ) {
                Stepper(ui.concurrentDownloads.toString(), onDecrement = { adjustConcurrent(-1) }, onIncrement = { adjustConcurrent(1) })
            }
        },
        SettingsRow(SettingsSection.DOWNLOADS) {
            SettingRow(
                title = stringResource(R.string.settings_per_server),
                hint = stringResource(R.string.settings_per_server_hint),
                onClick = { extra.shiftPerServer(context, 1) },
                onAdjust = { extra.shiftPerServer(context, it) }
            ) {
                Stepper(
                    if (queue.perServer == 0) stringResource(R.string.settings_off) else "${queue.perServer}",
                    onDecrement = { extra.shiftPerServer(context, -1) }, onIncrement = { extra.shiftPerServer(context, 1) }, valueWidth = 96.dp
                )
            }
        },
        SettingsRow(SettingsSection.DOWNLOADS) {
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
        SettingsRow(SettingsSection.DOWNLOADS, visible = limitKb > 0) {
            SettingRow(
                title = stringResource(R.string.settings_limit_day_only),
                hint = stringResource(R.string.settings_limit_day_only_hint, DownloadPolicy.formatMinutes(schedule.nightStart), DownloadPolicy.formatMinutes(schedule.nightEnd)),
                onClick = { extra.setSpeedLimitDayOnly(context, !schedule.speedLimitDayOnly) },
                onAdjust = { extra.setSpeedLimitDayOnly(context, it > 0) }
            ) { ThemedSwitch(schedule.speedLimitDayOnly) { extra.setSpeedLimitDayOnly(context, it) } }
        },
        SettingsRow(SettingsSection.DOWNLOADS) {
            SettingRow(
                title = stringResource(R.string.settings_min_free),
                hint = stringResource(R.string.settings_min_free_hint),
                onClick = { extra.shiftMinFree(context, 1) },
                onAdjust = { extra.shiftMinFree(context, it) }
            ) {
                Stepper(
                    if (queue.minFreeGb == 0) stringResource(R.string.settings_off) else "${queue.minFreeGb} GB",
                    onDecrement = { extra.shiftMinFree(context, -1) }, onIncrement = { extra.shiftMinFree(context, 1) }, valueWidth = 96.dp
                )
            }
        },
        SettingsRow(SettingsSection.DOWNLOADS) {
            SettingRow(
                title = stringResource(R.string.settings_resume),
                hint = stringResource(R.string.settings_resume_hint),
                onClick = { extra.setResume(context, !queue.resume) },
                onAdjust = { extra.setResume(context, it > 0) }
            ) { ThemedSwitch(queue.resume) { extra.setResume(context, it) } }
        },
        SettingsRow(SettingsSection.DOWNLOADS) {
            SettingRow(
                title = stringResource(R.string.settings_requeue),
                hint = stringResource(R.string.settings_requeue_hint),
                onClick = { extra.setRequeue(context, !queue.requeue) },
                onAdjust = { extra.setRequeue(context, it > 0) }
            ) { ThemedSwitch(queue.requeue) { extra.setRequeue(context, it) } }
        },
        SettingsRow(SettingsSection.DOWNLOADS) {
            SettingRow(
                title = stringResource(R.string.settings_auto_retry),
                hint = stringResource(R.string.settings_auto_retry_hint),
                onClick = { extra.setAutoRetry(context, !queue.autoRetry) },
                onAdjust = { extra.setAutoRetry(context, it > 0) }
            ) { ThemedSwitch(queue.autoRetry) { extra.setAutoRetry(context, it) } }
        },
        SettingsRow(SettingsSection.DOWNLOADS) {
            SettingRow(
                title = stringResource(R.string.settings_queue_summary),
                hint = stringResource(R.string.settings_queue_summary_hint),
                onClick = { extra.setQueueSummary(context, !queue.queueSummary) },
                onAdjust = { extra.setQueueSummary(context, it > 0) }
            ) { ThemedSwitch(queue.queueSummary) { extra.setQueueSummary(context, it) } }
        },
        SettingsRow(SettingsSection.SCHEDULE) {
            SettingRow(
                title = stringResource(R.string.settings_dl_wifi),
                hint = stringResource(R.string.settings_dl_wifi_hint),
                onClick = { extra.setWifiOnly(context, !schedule.wifiOnly) },
                onAdjust = { extra.setWifiOnly(context, it > 0) }
            ) { ThemedSwitch(schedule.wifiOnly) { extra.setWifiOnly(context, it) } }
        },
        SettingsRow(SettingsSection.SCHEDULE) {
            SettingRow(
                title = stringResource(R.string.settings_dl_charging),
                hint = stringResource(R.string.settings_dl_charging_hint),
                onClick = { extra.setChargingOnly(context, !schedule.chargingOnly) },
                onAdjust = { extra.setChargingOnly(context, it > 0) }
            ) { ThemedSwitch(schedule.chargingOnly) { extra.setChargingOnly(context, it) } }
        },
        SettingsRow(SettingsSection.SCHEDULE) {
            SettingRow(
                title = stringResource(R.string.settings_dl_night),
                hint = stringResource(R.string.settings_dl_night_hint, DownloadPolicy.formatMinutes(schedule.nightStart), DownloadPolicy.formatMinutes(schedule.nightEnd)),
                onClick = { extra.setNightOnly(context, !schedule.nightOnly) },
                onAdjust = { extra.setNightOnly(context, it > 0) }
            ) { ThemedSwitch(schedule.nightOnly) { extra.setNightOnly(context, it) } }
        },
        SettingsRow(SettingsSection.SCHEDULE, visible = schedule.nightOnly) {
            SettingRow(
                title = stringResource(R.string.settings_dl_night_start), hint = null,
                onClick = { shiftNightStart(1) }, onAdjust = ::shiftNightStart
            ) { Stepper(DownloadPolicy.formatMinutes(schedule.nightStart), onDecrement = { shiftNightStart(-1) }, onIncrement = { shiftNightStart(1) }, valueWidth = 72.dp) }
        },
        SettingsRow(SettingsSection.SCHEDULE, visible = schedule.nightOnly) {
            SettingRow(
                title = stringResource(R.string.settings_dl_night_end), hint = null,
                onClick = { shiftNightEnd(1) }, onAdjust = ::shiftNightEnd
            ) { Stepper(DownloadPolicy.formatMinutes(schedule.nightEnd), onDecrement = { shiftNightEnd(-1) }, onIncrement = { shiftNightEnd(1) }, valueWidth = 72.dp) }
        },
        SettingsRow(SettingsSection.AFTER) {
            SettingRow(
                title = stringResource(R.string.settings_auto_unzip),
                hint = stringResource(R.string.settings_auto_unzip_hint),
                onClick = { viewModel.onAutoUnzipChanged(context, !ui.autoUnzip) },
                onAdjust = { viewModel.onAutoUnzipChanged(context, it > 0) }
            ) {
                ThemedSwitch(ui.autoUnzip) { viewModel.onAutoUnzipChanged(context, it) }
            }
        },
        SettingsRow(SettingsSection.AFTER) {
            SettingRow(
                title = stringResource(R.string.settings_auto_m3u),
                hint = stringResource(R.string.settings_auto_m3u_hint),
                onClick = { extra.setAutoM3u(context, !afterDownload.autoM3u) },
                onAdjust = { extra.setAutoM3u(context, it > 0) }
            ) { ThemedSwitch(afterDownload.autoM3u) { extra.setAutoM3u(context, it) } }
        },
        SettingsRow(SettingsSection.AFTER) {
            SettingRow(
                title = stringResource(R.string.settings_esde_artwork),
                hint = stringResource(R.string.settings_esde_artwork_hint),
                onClick = { extra.setEsdeArtwork(context, !afterDownload.esdeArtwork) },
                onAdjust = { extra.setEsdeArtwork(context, it > 0) }
            ) { ThemedSwitch(afterDownload.esdeArtwork) { extra.setEsdeArtwork(context, it) } }
        },
        SettingsRow(SettingsSection.AFTER) {
            SettingRow(
                title = stringResource(R.string.settings_pegasus_artwork),
                hint = stringResource(R.string.settings_pegasus_artwork_hint),
                onClick = { extra.setPegasusArtwork(context, !afterDownload.pegasusArtwork) },
                onAdjust = { extra.setPegasusArtwork(context, it > 0) }
            ) { ThemedSwitch(afterDownload.pegasusArtwork) { extra.setPegasusArtwork(context, it) } }
        },
        SettingsRow(SettingsSection.AFTER) {
            SettingRow(
                title = stringResource(R.string.settings_retroarch_artwork),
                hint = afterDownload.retroArchThumbnailsDir.takeIf { it.isNotBlank() }?.let { FileParsingUtils.toUserReadablePath(it) }
                    ?: stringResource(R.string.settings_retroarch_artwork_hint),
                onClick = { retroArchThumbsLauncher.launch(null) }
            ) {
                if (afterDownload.retroArchThumbnailsDir.isNotBlank()) PillButton(stringResource(R.string.settings_retroarch_artwork_off)) { extra.setRetroArchThumbnailsDir(context, "") }
                PillButton(stringResource(R.string.settings_change)) { retroArchThumbsLauncher.launch(null) }
            }
        },
        SettingsRow(SettingsSection.TORRENTS) {
            SettingRow(
                title = stringResource(R.string.settings_debrid),
                hint = stringResource(R.string.settings_debrid_hint),
                onClick = { cycleDebrid(1) },
                onAdjust = ::cycleDebrid
            ) {
                Stepper(debrid.label, onDecrement = { cycleDebrid(-1) }, onIncrement = { cycleDebrid(1) }, valueWidth = 110.dp)
            }
        },
        SettingsRow(SettingsSection.TORRENTS, visible = debrid != DebridProvider.NONE) {
            SettingRow(
                title = debridKeyTitle,
                hint = maskedSecret(debridKey),
                onClick = { showDebridKeyDialog = true }
            ) {
                PillButton(stringResource(R.string.settings_change)) { showDebridKeyDialog = true }
            }
        },
        SettingsRow(SettingsSection.TORRENTS) {
            SettingRow(
                title = stringResource(R.string.settings_metadata_timeout),
                hint = stringResource(R.string.settings_metadata_timeout_hint),
                onClick = { adjustMetadataTimeout(1) },
                onAdjust = ::adjustMetadataTimeout
            ) {
                Stepper(stringResource(R.string.seconds_short, ui.metadataTimeoutSeconds), onDecrement = { adjustMetadataTimeout(-1) }, onIncrement = { adjustMetadataTimeout(1) })
            }
        },
        SettingsRow(SettingsSection.LIBRARY) {
            SettingRow(
                title = stringResource(R.string.settings_autoscan),
                hint = if (autoScan.last > 0) stringResource(R.string.settings_autoscan_last, DateUtils.getRelativeTimeSpanString(autoScan.last).toString())
                       else stringResource(R.string.settings_autoscan_hint),
                onClick = { extra.setAutoScan(context, !autoScan.on) },
                onAdjust = { extra.setAutoScan(context, it > 0) }
            ) { ThemedSwitch(autoScan.on) { extra.setAutoScan(context, it) } }
        },
        SettingsRow(SettingsSection.LIBRARY, visible = autoScan.on) {
            SettingRow(
                title = stringResource(R.string.settings_autoscan_every), hint = null,
                onClick = { extra.shiftAutoScanHours(context, 1) }, onAdjust = { extra.shiftAutoScanHours(context, it) }
            ) {
                Stepper(
                    if (autoScan.hours % 24 == 0) pluralStringResource(R.plurals.settings_days, autoScan.hours / 24, autoScan.hours / 24)
                    else stringResource(R.string.hours_short, autoScan.hours),
                    onDecrement = { extra.shiftAutoScanHours(context, -1) }, onIncrement = { extra.shiftAutoScanHours(context, 1) }, valueWidth = 96.dp
                )
            }
        },
        SettingsRow(SettingsSection.LIBRARY, visible = autoScan.on) {
            SettingRow(
                title = stringResource(R.string.settings_autoscan_wifi), hint = null,
                onClick = { extra.setAutoScanWifi(context, !autoScan.wifiOnly) }, onAdjust = { extra.setAutoScanWifi(context, it > 0) }
            ) { ThemedSwitch(autoScan.wifiOnly) { extra.setAutoScanWifi(context, it) } }
        },
        SettingsRow(SettingsSection.LIBRARY, visible = autoScan.on) {
            SettingRow(
                title = stringResource(R.string.settings_autoscan_charging), hint = null,
                onClick = { extra.setAutoScanCharging(context, !autoScan.charging) }, onAdjust = { extra.setAutoScanCharging(context, it > 0) }
            ) { ThemedSwitch(autoScan.charging) { extra.setAutoScanCharging(context, it) } }
        },
        SettingsRow(SettingsSection.LIBRARY, visible = autoScan.on) {
            SettingRow(
                title = stringResource(R.string.settings_autoscan_night),
                hint = stringResource(R.string.settings_autoscan_night_hint, DownloadPolicy.formatMinutes(schedule.nightStart), DownloadPolicy.formatMinutes(schedule.nightEnd)),
                onClick = { extra.setAutoScanNight(context, !autoScan.nightOnly) }, onAdjust = { extra.setAutoScanNight(context, it > 0) }
            ) { ThemedSwitch(autoScan.nightOnly) { extra.setAutoScanNight(context, it) } }
        },
        SettingsRow(SettingsSection.LIBRARY) {
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
        SettingsRow(SettingsSection.LIBRARY) {
            SettingRow(
                title = stringResource(R.string.settings_favorite_languages),
                hint = ui.favoriteLanguages.sorted().joinToString(" · ")
                    .ifBlank { stringResource(R.string.settings_favorite_languages_hint) },
                onClick = { showLanguagesDialog = true }
            ) {
                PillButton(stringResource(R.string.settings_change)) { showLanguagesDialog = true }
            }
        },
        SettingsRow(SettingsSection.LIBRARY) {
            SettingRow(
                title = stringResource(R.string.settings_wishlist_auto),
                hint = stringResource(R.string.settings_wishlist_auto_hint),
                onClick = { extra.setWishlistAuto(context, !afterDownload.wishlistAuto) },
                onAdjust = { extra.setWishlistAuto(context, it > 0) }
            ) { ThemedSwitch(afterDownload.wishlistAuto) { extra.setWishlistAuto(context, it) } }
        },
        SettingsRow(SettingsSection.TOOLS) {
            SettingRow(
                title = stringResource(R.string.settings_tools),
                hint = stringResource(R.string.settings_tools_hint),
                onClick = { navController.navigate(NavRoutes.Tools.route) }
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        SettingsRow(SettingsSection.FRONTENDS) {
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
        SettingsRow(SettingsSection.FRONTENDS) {
            SettingRow(
                title = stringResource(R.string.settings_esde),
                hint = ui.esdeDirectory.ifBlank { stringResource(R.string.settings_esde_hint) },
                onClick = ::runEsdeSetup
            ) {
                PillButton(stringResource(R.string.settings_esde_action), ::runEsdeSetup)
            }
        },
        SettingsRow(SettingsSection.FRONTENDS) {
            SettingRow(
                title = stringResource(R.string.settings_iisu),
                hint = ui.iisuDirectory.ifBlank { stringResource(R.string.settings_iisu_hint) },
                onClick = ::runIisuSetup
            ) {
                PillButton(stringResource(R.string.settings_iisu_action), ::runIisuSetup)
            }
        },
        SettingsRow(SettingsSection.FRONTENDS) {
            SettingRow(
                title = stringResource(R.string.settings_daijisho),
                hint = stringResource(R.string.settings_daijisho_hint),
                onClick = { viewModel.onPrepareDaijisho(context) }
            ) {
                PillButton(stringResource(R.string.settings_daijisho_action)) { viewModel.onPrepareDaijisho(context) }
            }
        },
        SettingsRow(SettingsSection.ROMM) {
            SettingRow(
                title = stringResource(R.string.settings_romm),
                hint = ui.rommUrl.ifBlank { stringResource(R.string.settings_romm_hint) },
                onClick = { navController.navigate(NavRoutes.Romm.route) }
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        SettingsRow(SettingsSection.ROMM) {
            SettingRow(
                title = stringResource(R.string.settings_save_sync),
                hint = stringResource(R.string.settings_save_sync_hint),
                onClick = { navController.navigate(NavRoutes.SaveSync.route) }
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        SettingsRow(SettingsSection.PROFILES) {
            SettingRow(
                title = stringResource(R.string.settings_profiles),
                hint = activeProfileName?.let { stringResource(R.string.settings_profiles_active, it) } ?: stringResource(R.string.settings_profiles_hint),
                onClick = { navController.navigate(NavRoutes.Profiles.route) }
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        SettingsRow(SettingsSection.PROFILES) {
            SettingRow(
                title = stringResource(R.string.settings_cocoon),
                hint = stringResource(R.string.settings_cocoon_hint),
                onClick = { showCocoonHelp = true }
            ) {
                PillButton(stringResource(R.string.settings_daijisho_action)) { showCocoonHelp = true }
            }
        },
        SettingsRow(SettingsSection.BACKUP) {
            SettingRow(
                title = stringResource(R.string.settings_backup_export),
                hint = stringResource(R.string.settings_backup_export_hint),
                onClick = ::exportBackup
            ) {
                PillButton(stringResource(R.string.settings_backup_export_action), ::exportBackup)
            }
        },
        SettingsRow(SettingsSection.BACKUP) {
            SettingRow(
                title = stringResource(R.string.settings_auto_backup),
                hint = when {
                    appPrefs.autoBackup && appPrefs.autoBackupDir.isBlank() -> stringResource(R.string.settings_auto_backup_pick)
                    appPrefs.autoBackupLast > 0 -> stringResource(R.string.settings_auto_backup_last, DateUtils.formatDateTime(context, appPrefs.autoBackupLast, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH))
                    else -> stringResource(R.string.settings_auto_backup_hint)
                },
                onClick = { extra.setAutoBackup(context, !appPrefs.autoBackup) },
                onAdjust = { extra.setAutoBackup(context, it > 0) }
            ) { ThemedSwitch(appPrefs.autoBackup) { extra.setAutoBackup(context, it) } }
        },
        SettingsRow(SettingsSection.BACKUP, visible = appPrefs.autoBackup) {
            SettingRow(
                title = stringResource(R.string.settings_auto_backup_folder),
                hint = appPrefs.autoBackupDir.ifBlank { stringResource(R.string.settings_not_set) }.let { if (it.startsWith("content://")) FileParsingUtils.toUserReadablePath(it) else it },
                onClick = { backupDirLauncher.launch(null) }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillButton(stringResource(R.string.settings_change)) { backupDirLauncher.launch(null) }
                    if (appPrefs.autoBackupDir.isNotBlank()) PillButton(stringResource(R.string.auto_backup_now)) { extra.backupNow(context) }
                }
            }
        },
        SettingsRow(SettingsSection.BACKUP) {
            SettingRow(
                title = stringResource(R.string.settings_backup_import),
                hint = stringResource(R.string.settings_backup_import_hint),
                onClick = ::importBackup
            ) {
                PillButton(stringResource(R.string.settings_backup_import_action), ::importBackup)
            }
        },
        SettingsRow(SettingsSection.APP) {
            SettingRow(
                title = stringResource(R.string.settings_prereleases),
                hint = stringResource(R.string.settings_prereleases_hint),
                onClick = { extra.setPreReleases(context, !appPrefs.preReleases) },
                onAdjust = { extra.setPreReleases(context, it > 0) }
            ) { ThemedSwitch(appPrefs.preReleases) { extra.setPreReleases(context, it) } }
        },
        SettingsRow(SettingsSection.APP) {
            SettingRow(
                title = stringResource(R.string.settings_update_check),
                hint = updateProgress?.let { stringResource(R.string.update_downloading, (it * 100).toInt()) } ?: stringResource(R.string.settings_update_check_hint),
                onClick = { extra.checkForUpdates(context) }
            ) { PillButton(stringResource(R.string.settings_update_check_action)) { extra.checkForUpdates(context) } }
        },
        SettingsRow(SettingsSection.APP) {
            SettingRow(
                title = stringResource(R.string.settings_diagnostics),
                hint = stringResource(R.string.settings_diagnostics_hint),
                onClick = { extra.shareDiagnostics(context) }
            ) { PillButton(stringResource(R.string.settings_diagnostics_action)) { extra.shareDiagnostics(context) } }
        },
        SettingsRow(SettingsSection.APP) {
            SettingRow(
                title = stringResource(R.string.settings_about),
                hint = stringResource(R.string.credits_fork_name) + " · " + stringResource(R.string.credits_original_name),
                onClick = { navController.navigate(NavRoutes.Contact.route) }
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
    // Rows that do not apply right now are left out; every group starts with its heading over the
    // full width, and in landscape its rows fill two columns.
    val shown = ordered.filter { it.visible }
    val columns = if (isLandscape) 2 else 1
    val cells = SettingsSection.entries.flatMap { section ->
        val inSection = shown.filter { it.section == section }
        if (inSection.isEmpty()) emptyList()
        else listOfNotNull(section.title?.let { SettingsCell(section, null, 0) }) + inSection.mapIndexed { i, row -> SettingsCell(section, row, i % columns) }
    }
    val currentCells by rememberUpdatedState(cells)

    // LB / RB: in landscape hop between the two columns (same grid row of the group); in portrait
    // jump to the first row of the previous / next group.
    val rowFocus = remember(cells.size) { List(cells.size) { FocusRequester() } }
    val currentFocus by rememberUpdatedState(rowFocus)
    var focusedIndex by remember { mutableStateOf(-1) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(isLandscape) {
        Gamepad.presses.collect { button ->
            if (button != GamepadButton.PREV_PANEL && button != GamepadButton.NEXT_PANEL) return@collect
            val list = currentCells
            val current = focusedIndex.takeIf { it in list.indices && list[it].row != null }
            if (isLandscape) {
                val target = when {
                    current == null -> list.indexOfFirst { it.row != null }
                    list[current].column == 0 -> current + 1   // left column → right
                    else -> current - 1                         // right column → left
                }
                val ok = target in list.indices && list[target].row != null && (current == null || list[target].section == list[current].section)
                if (ok) runCatching { currentFocus[target].requestFocus() }
            } else {
                val headings = list.indices.filter { list[it].row == null }
                val here = current?.let { c -> headings.lastOrNull { it < c } } ?: -1
                val heading = if (button == GamepadButton.NEXT_PANEL) headings.firstOrNull { it > here }
                    else headings.lastOrNull { it < here } ?: headings.firstOrNull()
                if (heading != null && heading + 1 in list.indices) {
                    // The row may be off screen (not composed yet): scroll the group in first.
                    gridState.scrollToItem(heading)
                    withFrameNanos { }
                    runCatching { currentFocus[heading + 1].requestFocus() }
                }
            }
        }
    }
    run {
        val base = legendFor(NavRoutes.Settings.route)
        val hop = LegendEntry("LB · RB", stringResource(if (isLandscape) R.string.pad_column else R.string.pad_group))
        val legend = remember(base, hop) { Legend(base.toMutableList().also { it.add(it.lastIndex, hop) }) }
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
            state = gridState,
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            cells.forEachIndexed { index, cell ->
                val row = cell.row
                if (row == null) {
                    item(key = "section-${cell.section.name}", span = { GridItemSpan(maxLineSpan) }) {
                        SettingsHeading(stringResource(cell.section.title ?: return@item), first = index == 0)
                    }
                } else {
                    item {
                        Box(
                            modifier = Modifier
                                .focusRequester(rowFocus[index])
                                .onFocusChanged { if (it.hasFocus) focusedIndex = index }
                        ) { row.content() }
                    }
                }
            }
        }
    }
}

/** The groups of the Settings screen, in the order they are shown. */
private enum class SettingsSection(@StringRes val title: Int?) {
    /** The way into Tools, first and without a heading. */
    TOOLS(null),
    LOOK(R.string.settings_section_look),
    DOWNLOADS(R.string.settings_section_downloads),
    SCHEDULE(R.string.settings_section_schedule),
    AFTER(R.string.settings_section_after),
    TORRENTS(R.string.settings_section_torrents),
    LIBRARY(R.string.settings_section_library),
    FRONTENDS(R.string.settings_section_frontends),
    ROMM(R.string.settings_section_romm),
    PROFILES(R.string.settings_section_profiles),
    BACKUP(R.string.settings_section_backup),
    APP(R.string.settings_section_app)
}

/** One Settings entry and the group it belongs to; [visible] false leaves it out. */
private class SettingsRow(val section: SettingsSection, val visible: Boolean = true, val content: @Composable () -> Unit)

/** A grid cell: a group heading ([row] null) or a row in [column] (0 = left). */
private class SettingsCell(val section: SettingsSection, val row: SettingsRow?, val column: Int)

/** Name of a group of settings, over the full width of the list. */
@Composable
private fun SettingsHeading(text: String, first: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (first) 0.dp else 18.dp, bottom = 4.dp)
    )
}

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
    ActionPill(label, onClick)
}

/** One accent colour as a circle; Material You shows as a four-colour wheel. */
@Composable
private fun AccentSwatch(color: Color, size: Dp, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val fill = if (color == AccentPresets.dynamic) Modifier.background(
        Brush.sweepGradient(listOf(Color(0xFFFF7F00), Color(0xFFD4E157), Color(0xFF3CC8FF), Color(0xFFE57BFF), Color(0xFFFF7F00)))
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
    val firstFocus = rememberInitialFocus()
    AlertDialog(
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
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss) }
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
