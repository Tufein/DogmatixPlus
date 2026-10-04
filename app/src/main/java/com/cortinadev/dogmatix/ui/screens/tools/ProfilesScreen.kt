package com.cortinadev.dogmatix.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.service.ProfileService
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.Profile
import com.cortinadev.dogmatix.util.Profiles
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val service: ProfileService,
    consoleRepository: ConsoleRepository
) : ViewModel() {
    val profiles: StateFlow<List<Profile>> = service.profiles
    val activeId: StateFlow<String> = service.activeId
    val pinSet: StateFlow<Boolean> = service.pinHash.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val consoles: StateFlow<List<ConsoleEntity>> = consoleRepository.getAllConsoles()
        .map { list -> list.sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A restricted profile is active: profiles and the PIN cannot be changed. */
    val restricted: StateFlow<Boolean> = service.restrictions.map { it.active }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun save(profile: Profile) { viewModelScope.launch { service.save(profile) } }
    fun delete(id: String) { viewModelScope.launch { service.delete(id) } }

    fun switchTo(context: android.content.Context, id: String, pin: String = "", onWrongPin: () -> Unit = {}) {
        val app = context.applicationContext
        viewModelScope.launch {
            if (service.switchTo(id, pin)) ToastUtil.showSuccess(app, app.getString(R.string.profiles_switched))
            else { ToastUtil.showError(app, app.getString(R.string.profiles_wrong_pin)); onWrongPin() }
        }
    }

    fun setPin(context: android.content.Context, pin: String) {
        val app = context.applicationContext
        viewModelScope.launch {
            if (service.setPin(pin)) ToastUtil.showSuccess(app, app.getString(if (pin.isBlank()) R.string.profiles_pin_removed else R.string.profiles_pin_set))
            else ToastUtil.showError(app, app.getString(R.string.profiles_pin_locked))
        }
    }
}

/**
 * Profiles: each hides some consoles and tags from the library (a child's profile, a "couch"
 * profile). One is active at a time; "Everything" shows all. With a PIN, leaving a restricted
 * profile needs it, and only the unrestricted side can change profiles or the PIN.
 */
@Composable
fun ProfilesScreen(viewModel: ProfilesViewModel = hiltViewModel()) {
    val profiles by viewModel.profiles.collectAsState()
    val activeId by viewModel.activeId.collectAsState()
    val pinSet by viewModel.pinSet.collectAsState()
    val consoles by viewModel.consoles.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Profile?>(null) }
    var askPinFor by remember { mutableStateOf<String?>(null) }
    var settingPin by remember { mutableStateOf(false) }
    val locked by viewModel.restricted.collectAsState()

    fun switch(id: String) {
        if (locked && pinSet && id != activeId) askPinFor = id else viewModel.switchTo(context, id)
    }

    editing?.let { p -> EditProfileDialog(p, consoles, onSave = { viewModel.save(it); editing = null }, onDismiss = { editing = null }) }
    askPinFor?.let { target ->
        PinDialog(stringResource(R.string.profiles_enter_pin), onOk = { pin -> viewModel.switchTo(context, target, pin); askPinFor = null }, onDismiss = { askPinFor = null })
    }
    if (settingPin) PinDialog(stringResource(R.string.profiles_set_pin_title), allowEmpty = true, onOk = { viewModel.setPin(context, it); settingPin = false }, onDismiss = { settingPin = false })

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.settings_profiles))
        InfoCard(listOf(stringResource(R.string.profiles_intro)), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            item(key = "all") {
                ToolRow(stringResource(R.string.profiles_everything), listOf(stringResource(R.string.profiles_everything_hint)), onClick = { switch("") },
                    badge = if (activeId.isEmpty()) ({ Badge(stringResource(R.string.profiles_active_badge), warning = false) }) else null) {
                    if (activeId.isNotEmpty()) PillButton(stringResource(R.string.profiles_use)) { switch("") }
                }
            }
            items(profiles.sortedBy { it.name.lowercase() }, key = { it.id }) { p ->
                val summary = buildList {
                    if (p.hiddenConsoles.isNotEmpty()) add(stringResource(R.string.profiles_hides_consoles, p.hiddenConsoles.joinToString(", ") { ConsoleFormatter.getConsoleShortName(it) }))
                    if (p.hiddenTags.isNotEmpty()) add(stringResource(R.string.profiles_hides_tags, p.hiddenTags.sorted().joinToString(", ")))
                    if (isEmpty()) add(stringResource(R.string.profiles_hides_nothing))
                }
                ToolRow(p.name, summary, onClick = { switch(p.id) },
                    badge = if (activeId == p.id) ({ Badge(stringResource(R.string.profiles_active_badge), warning = false) }) else null) {
                    if (activeId != p.id) PillButton(stringResource(R.string.profiles_use)) { switch(p.id) }
                    if (!locked) {
                        PillButton(stringResource(R.string.profiles_edit)) { editing = p }
                        PillButton(stringResource(R.string.profiles_delete)) { viewModel.delete(p.id) }
                    }
                }
            }
            if (!locked) {
                item(key = "add") {
                    ToolRow(stringResource(R.string.profiles_add), listOf(stringResource(R.string.profiles_add_hint)), onClick = {
                        editing = Profile(java.util.UUID.randomUUID().toString(), "")
                    }) { PillButton(stringResource(R.string.profiles_add_action)) { editing = Profile(java.util.UUID.randomUUID().toString(), "") } }
                }
                item(key = "pin") {
                    ToolRow(stringResource(R.string.profiles_pin), listOf(stringResource(if (pinSet) R.string.profiles_pin_on else R.string.profiles_pin_off)), onClick = { settingPin = true }) {
                        PillButton(stringResource(R.string.profiles_pin_action)) { settingPin = true }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditProfileDialog(profile: Profile, consoles: List<ConsoleEntity>, onSave: (Profile) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(profile.name) }
    var hidden by remember { mutableStateOf(profile.hiddenConsoles) }
    var tags by remember { mutableStateOf(profile.hiddenTags.sorted().joinToString(", ")) }
    val focus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_edit_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.profiles_name)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(tags, { tags = it }, singleLine = true, label = { Text(stringResource(R.string.profiles_tags_label)) },
                    supportingText = { Text(stringResource(R.string.profiles_tags_hint)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.profiles_consoles_label), style = MaterialTheme.typography.titleSmall)
                consoles.forEach { c ->
                    val off = c.id in hidden
                    DialogButton(
                        text = (if (off) "✕ " else "✓ ") + ConsoleFormatter.getConsoleDisplayName(c.id),
                        onClick = { hidden = if (off) hidden - c.id else hidden + c.id }
                    )
                }
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.profiles_save), enabled = name.isNotBlank(),
                onClick = { onSave(profile.copy(name = name.trim().take(40), hiddenConsoles = hidden, hiddenTags = Profiles.parseTags(tags))) })
        },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss) }
    )
}

@Composable
private fun PinDialog(title: String, allowEmpty: Boolean = false, onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    val focus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(pin, { v -> pin = v.filter { it.isDigit() }.take(8) }, singleLine = true,
                label = { Text(stringResource(R.string.profiles_pin_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        },
        confirmButton = { DialogButton(stringResource(R.string.dialog_ok), enabled = allowEmpty || pin.length >= 4, onClick = { onOk(pin) }) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss) }
    )
}
