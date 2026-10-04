package com.cortinadev.dogmatix.ui.screens.settings.romm

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.common.Legend
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.components.legendFor
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.cortinadev.dogmatix.ui.screens.settings.SettingRow
import com.cortinadev.dogmatix.ui.screens.settings.ThemedSwitch
import com.cortinadev.dogmatix.ui.screens.settings.components.ApiKeyDialog
import com.cortinadev.dogmatix.ui.screens.settings.components.maskedSecret
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.util.CertTrust
import com.cortinadev.dogmatix.data.service.CoverRunState

/**
 * RomM server settings: URL, token, auto-upload and one stepper per console to pick the RomM
 * platform it uploads to (◀ ▶ cycles "Not mapped" + the server's platforms; suggestions are
 * pre-filled from the console's folder aliases). Same grid and LB/RB hop as Settings.
 */
@Composable
fun RommScreen(viewModel: RommViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsState()
    val platforms by viewModel.platforms.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(ui.url, ui.token) { if (ui.url.isNotBlank() && ui.token.isNotBlank()) viewModel.loadPlatforms(null) }

    var showUrlDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
    if (showUrlDialog) {
        ApiKeyDialog(
            title = stringResource(R.string.romm_server_url),
            hint = stringResource(R.string.romm_url_dialog_hint),
            value = ui.url,
            label = stringResource(R.string.romm_server_url),
            onTest = { viewModel.testConnection(context, it, ui.token) },
            onSave = { viewModel.setUrl(context, it) },
            onDismiss = { showUrlDialog = false }
        )
    }
    if (showTokenDialog) {
        ApiKeyDialog(
            title = stringResource(R.string.romm_token),
            hint = stringResource(R.string.romm_token_dialog_hint),
            value = ui.token,
            onTest = { viewModel.testConnection(context, ui.url, it) },
            onSave = { viewModel.setToken(context, it) },
            onDismiss = { showTokenDialog = false }
        )
    }

    val libraryState by viewModel.libraryState.collectAsState()
    val coverState by viewModel.coverState.collectAsState()
    val trustPrompt by viewModel.trustPrompt.collectAsState()
    trustPrompt?.let { prompt ->
        TrustCertificateDialog(prompt, onTrust = { viewModel.confirmTrust(context) }, onDismiss = viewModel::dismissTrustPrompt)
    }

    val notMapped = stringResource(R.string.romm_not_mapped)
    var headerIndex = 0
    val rows: List<@Composable () -> Unit> = buildList {
        add {
            SettingRow(title = stringResource(R.string.romm_server_url), hint = ui.url.ifBlank { stringResource(R.string.settings_not_set) }, onClick = { showUrlDialog = true }) {
                PillButton(stringResource(R.string.settings_change)) { showUrlDialog = true }
            }
        }
        add {
            SettingRow(title = stringResource(R.string.romm_token), hint = maskedSecret(ui.token), onClick = { showTokenDialog = true }) {
                PillButton(stringResource(R.string.settings_change)) { showTokenDialog = true }
            }
        }
        add {
            SettingRow(
                title = stringResource(R.string.romm_test_connection),
                hint = if (platforms.isEmpty()) stringResource(R.string.romm_platforms_none) else stringResource(R.string.romm_platforms_count, platforms.size),
                onClick = { viewModel.loadPlatforms(context, announce = true) }
            ) {
                PillButton(stringResource(if (loading) R.string.romm_testing else R.string.settings_test)) { viewModel.loadPlatforms(context, announce = true) }
            }
        }
        add {
            SettingRow(
                title = stringResource(R.string.romm_auto_upload),
                hint = stringResource(R.string.romm_auto_upload_hint),
                onClick = { viewModel.setAutoUpload(context, !ui.autoUpload) },
                onAdjust = { viewModel.setAutoUpload(context, it > 0) }
            ) {
                ThemedSwitch(ui.autoUpload) { viewModel.setAutoUpload(context, it) }
            }
        }
        add {
            SettingRow(
                title = stringResource(R.string.romm_upload_missing),
                hint = stringResource(R.string.romm_upload_missing_hint),
                onClick = { viewModel.uploadMissing(context) }
            ) { PillButton(stringResource(R.string.romm_upload_missing_action)) { viewModel.uploadMissing(context) } }
        }
        if (ui.url.startsWith("https://", ignoreCase = true)) add {
            val pinned = ui.trustFingerprint.isNotBlank()
            SettingRow(
                title = stringResource(R.string.romm_cert),
                hint = if (pinned) stringResource(R.string.romm_cert_trusted_hint, CertTrust.format(ui.trustFingerprint).take(23) + "…") else stringResource(R.string.romm_cert_hint),
                onClick = { if (pinned) viewModel.forgetTrust(context) else viewModel.checkCertificate(context, ui.url) }
            ) {
                PillButton(stringResource(if (pinned) R.string.romm_cert_forget else R.string.romm_cert_check)) {
                    if (pinned) viewModel.forgetTrust(context) else viewModel.checkCertificate(context, ui.url)
                }
            }
        }
        add {
            SettingRow(
                title = stringResource(R.string.romm_mark_games),
                hint = stringResource(R.string.romm_mark_games_hint),
                onClick = { viewModel.setMarkGames(context, !ui.markGames) },
                onAdjust = { viewModel.setMarkGames(context, it > 0) }
            ) { ThemedSwitch(ui.markGames) { viewModel.setMarkGames(context, it) } }
        }
        if (ui.markGames) add {
            val hint = when {
                libraryState.refreshing -> stringResource(R.string.romm_marks_refreshing)
                libraryState.error != null -> stringResource(R.string.romm_marks_error, libraryState.error ?: "")
                libraryState.updatedAt > 0 -> stringResource(R.string.romm_marks_known, libraryState.games, android.text.format.DateUtils.getRelativeTimeSpanString(libraryState.updatedAt).toString())
                else -> stringResource(R.string.romm_marks_never)
            }
            SettingRow(title = stringResource(R.string.romm_marks), hint = hint, onClick = viewModel::refreshMarks) {
                PillButton(stringResource(R.string.romm_marks_refresh), viewModel::refreshMarks)
            }
        }
        add {
            val hint = when {
                coverState.running -> stringResource(R.string.romm_covers_running, coverState.done, coverState.total)
                coverState.problem == CoverRunState.Problem.NO_ESDE_FOLDER || (coverState.problem == null && coverState.fetched == null && ui.esdeDirectory.isBlank()) -> stringResource(R.string.romm_covers_needs_esde)
                coverState.problem == CoverRunState.Problem.NO_PLATFORMS -> stringResource(R.string.romm_covers_needs_platforms)
                coverState.problem == CoverRunState.Problem.ESDE_NOT_WRITABLE -> stringResource(R.string.romm_covers_not_writable)
                coverState.problem == CoverRunState.Problem.FAILED -> stringResource(R.string.romm_covers_failed)
                coverState.fetched != null -> androidx.compose.ui.res.pluralStringResource(R.plurals.romm_covers_done, coverState.fetched ?: 0, coverState.fetched ?: 0) + if (coverState.failed > 0) " · " + stringResource(R.string.romm_covers_some_failed, coverState.failed) else ""
                else -> stringResource(R.string.romm_covers_hint)
            }
            SettingRow(title = stringResource(R.string.romm_covers), hint = hint, onClick = viewModel::startCovers) {
                PillButton(stringResource(if (coverState.running) R.string.romm_covers_busy else R.string.romm_covers_action), viewModel::startCovers)
            }
        }
        headerIndex = size
        add {
            SettingRow(
                title = stringResource(R.string.romm_platforms_header),
                hint = stringResource(R.string.romm_platforms_hint),
                onClick = { viewModel.applySuggestions(context) }
            ) {
                PillButton(stringResource(R.string.romm_apply_suggestions)) { viewModel.applySuggestions(context) }
            }
        }
        ui.consoles.forEach { console ->
            add {
                val mappedId = ui.platformMap[console.id]
                val mapped = platforms.firstOrNull { it.id == mappedId }
                val suggestion = if (mappedId == null) viewModel.suggestionFor(console.id) else null
                // Stepper positions: 0 = not mapped, 1..n = platforms (in the server's order).
                val index = if (mapped != null) platforms.indexOf(mapped) + 1 else 0
                fun step(delta: Int) {
                    if (platforms.isEmpty()) return
                    val next = ((index + delta) % (platforms.size + 1) + platforms.size + 1) % (platforms.size + 1)
                    viewModel.setPlatform(context, console.id, if (next == 0) null else platforms[next - 1].id)
                }
                val value = when {
                    mapped != null -> mapped.label
                    mappedId != null -> "#$mappedId"
                    suggestion != null -> stringResource(R.string.romm_suggested, suggestion.label)
                    else -> notMapped
                }
                SettingRow(
                    title = ConsoleFormatter.getConsoleDisplayName(console.id),
                    hint = ConsoleFormatter.getConsoleShortName(console.id),
                    onClick = { if (mappedId == null && suggestion != null) viewModel.setPlatform(context, console.id, suggestion.id) else step(1) },
                    onAdjust = ::step
                ) {
                    Stepper(value, onDecrement = { step(-1) }, onIncrement = { step(1) }, valueWidth = 140.dp)
                }
            }
        }
    }

    // LB / RB (landscape): hop between the two columns, staying on the same grid row. The
    // "Console → platform" header spans both columns, so parity flips after it.
    val columns = if (isLandscape) 2 else 1
    val rowFocus = remember(rows.size) { List(rows.size) { FocusRequester() } }
    var focusedIndex by remember { mutableStateOf(-1) }
    fun columnOf(index: Int) = when {
        index < headerIndex -> index % 2
        index == headerIndex -> -1
        else -> (index - headerIndex - 1) % 2
    }
    LaunchedEffect(isLandscape, rows.size) {
        if (!isLandscape) return@LaunchedEffect
        Gamepad.presses.collect { button ->
            if (button != GamepadButton.PREV_PANEL && button != GamepadButton.NEXT_PANEL) return@collect
            val current = focusedIndex
            val target = when (columnOf(current)) {
                0 -> current + 1                // left column → right
                1 -> current - 1                // right column → left
                else -> if (current < 0) 0 else current   // nothing / header: stay
            }.coerceIn(0, rows.lastIndex)
            runCatching { rowFocus[target].requestFocus() }
        }
    }
    // Land on the first row so the screen is usable from the D-pad without a "wake-up" press.
    LaunchedEffect(rows.size) { if (focusedIndex < 0 && rows.isNotEmpty()) runCatching { rowFocus[0].requestFocus() } }
    if (isLandscape) {
        val base = legendFor(NavRoutes.Romm.route)
        val column = LegendEntry("LB · RB", stringResource(R.string.pad_column))
        val legend = remember(base, column) { Legend(base.toMutableList().also { it.add(it.lastIndex, column) }) }
        LaunchedEffect(legend) { Gamepad.legendOverride.value = legend }
        // Only clear our own legend: the previous screen's onDispose can run after ours is set.
        DisposableEffect(legend) { onDispose { if (Gamepad.legendOverride.value === legend) Gamepad.legendOverride.value = null } }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        Text(
            stringResource(R.string.settings_romm),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(rows.size, span = { index -> GridItemSpan(if (index == headerIndex) columns else 1) }) { index ->
                Box(
                    modifier = Modifier
                        .focusRequester(rowFocus[index])
                        .onFocusChanged { if (it.hasFocus) focusedIndex = index }
                ) { rows[index]() }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TrustCertificateDialog(prompt: TrustPrompt, onTrust: () -> Unit, onDismiss: () -> Unit) {
    val cert = prompt.certificate
    val until = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(cert.validUntil))
    val cancelFocus = com.cortinadev.dogmatix.ui.components.rememberInitialFocus()
    androidx.compose.material3.AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.romm_cert_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.romm_cert_dialog_message, prompt.url))
                Text(CertTrust.format(cert.fingerprint), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text(stringResource(R.string.romm_cert_dialog_details, cert.subject, until), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { com.cortinadev.dogmatix.ui.components.DialogButton(text = stringResource(R.string.romm_cert_trust), onClick = onTrust) },
        dismissButton = { com.cortinadev.dogmatix.ui.components.DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}
