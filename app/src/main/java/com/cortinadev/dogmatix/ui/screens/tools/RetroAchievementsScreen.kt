package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.service.RetroAchievementsService
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.MeterBar
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.theme.consoleColor
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.RetroAchievements
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RetroAchievementsViewModel @Inject constructor(
    private val service: RetroAchievementsService,
    private val settings: AppSettings,
    consoleRepository: ConsoleRepository
) : ViewModel() {
    /** Library consoles that RetroAchievements covers (cartridge systems). */
    val consoles: StateFlow<List<String>> = consoleRepository.getAllConsoles()
        .map { list -> list.map { it.id }.filter { RetroAchievements.consoleFor(it) != null }.sortedBy { ConsoleFormatter.getConsoleDisplayName(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val user: StateFlow<String> = settings.raUser.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val hasKey: StateFlow<Boolean> = settings.raKey.map { it.isNotBlank() }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val marked: StateFlow<Map<String, Int>> = service.marks.map { m -> m.byConsole.mapValues { it.value.size } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _results = MutableStateFlow<Map<String, RetroAchievementsService.ConsoleCheck>>(emptyMap())
    val results: StateFlow<Map<String, RetroAchievementsService.ConsoleCheck>> = _results.asStateFlow()
    private val _busy = MutableStateFlow<Pair<String, String>?>(null)
    /** Console being checked and its progress text. */
    val busy: StateFlow<Pair<String, String>?> = _busy.asStateFlow()

    fun saveCredentials(user: String, key: String) { viewModelScope.launch { settings.setRetroAchievements(user, key) } }

    fun check(context: Context, consoleId: String) {
        if (_busy.value != null) return
        val app = context.applicationContext
        viewModelScope.launch {
            _busy.value = consoleId to ""
            runCatching { service.check(consoleId, refresh = true) { done, total -> _busy.value = consoleId to "$done / $total" } }
                .onSuccess { r -> if (r != null) _results.update { it + (consoleId to r) } }
                .onFailure {
                    ToastUtil.showError(app, if (it is RetroAchievementsService.NoCredentialsException) app.getString(R.string.ra_need_key)
                        else app.getString(R.string.ra_failed, it.message ?: ""))
                }
            _busy.value = null
        }
    }
}

/**
 * RetroAchievements: with the user's RA name and web API key, check per console which games
 * have achievements — files on the device by their hash, games in the sources through an
 * imported No-Intro DAT. Supported games get an "RA" badge in the library.
 */
@Composable
fun RetroAchievementsScreen(viewModel: RetroAchievementsViewModel = hiltViewModel()) {
    val consoles by viewModel.consoles.collectAsState()
    val user by viewModel.user.collectAsState()
    val hasKey by viewModel.hasKey.collectAsState()
    val results by viewModel.results.collectAsState()
    val marked by viewModel.marked.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    if (editing) CredentialsDialog(user, onSave = { u, k -> viewModel.saveCredentials(u, k); editing = false }, onDismiss = { editing = false })

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_ra), icon = NavRoutes.RetroAchievements.icon)
        InfoCard(listOf(stringResource(R.string.ra_intro)), icon = R.drawable.ic_trophy)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "account") {
                val signedIn = user.isNotBlank() && hasKey
                ToolRow(stringResource(R.string.ra_account),
                    listOf(if (signedIn) stringResource(R.string.ra_account_set, user) else stringResource(R.string.ra_account_hint)),
                    onClick = { editing = true },
                    badge = if (signedIn) ({ Badge(user, warning = false, tone = PillTone.Success, icon = R.drawable.ic_check_circle) }) else null,
                    icon = R.drawable.ic_account
                ) { ToolAction(stringResource(R.string.ra_account_action), tone = if (signedIn) ActionTone.Neutral else ActionTone.Accent) { editing = true } }
            }
            if (consoles.isEmpty()) item(key = "none") { InfoCard(listOf(stringResource(R.string.ra_no_consoles)), icon = R.drawable.ic_info) }
            items(consoles, key = { it }) { id ->
                val r = results[id]
                val lines = buildList {
                    RetroAchievements.consoleFor(id)?.let { add(stringResource(R.string.ra_console_line, it.name)) }
                    when {
                        busy?.first == id -> add(stringResource(R.string.ra_checking, busy?.second.orEmpty()))
                        r != null -> add(stringResource(R.string.ra_result, r.raGames, r.supportedOnDevice, r.onDevice, r.fromDat))
                        (marked[id] ?: 0) > 0 -> add(stringResource(R.string.ra_marked, marked[id] ?: 0))
                        else -> add(stringResource(R.string.ra_not_checked))
                    }
                }
                ToolRow(
                    ConsoleFormatter.getConsoleDisplayName(id), lines,
                    onClick = { viewModel.check(context, id) },
                    leading = { ConsoleTile(id) },
                    below = if (r != null && r.onDevice > 0) ({
                        MeterBar(
                            (r.supportedOnDevice.toFloat() / r.onDevice).coerceIn(0f, 1f),
                            modifier = Modifier.padding(top = 6.dp), height = 6.dp, color = consoleColor(id)
                        )
                    }) else null
                ) {
                    if (busy == null) ToolAction(stringResource(R.string.ra_check), icon = R.drawable.ic_retry) { viewModel.check(context, id) }
                }
            }
        }
    }
}

@Composable
private fun CredentialsDialog(user: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(user) }
    var key by remember { mutableStateOf("") }
    val focus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ra_account)) },
        text = {
            Column {
                Text(stringResource(R.string.ra_key_where))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.ra_user_label)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(key, { key = it }, singleLine = true, label = { Text(stringResource(R.string.ra_key_label)) },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.profiles_save), enabled = name.isNotBlank() && key.isNotBlank(), onClick = { onSave(name, key) }) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss) }
    )
}
