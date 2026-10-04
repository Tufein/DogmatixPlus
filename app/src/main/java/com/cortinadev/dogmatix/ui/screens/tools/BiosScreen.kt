package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.service.BiosReport
import com.cortinadev.dogmatix.data.service.BiosService
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.cortinadev.dogmatix.util.BiosCatalog
import com.cortinadev.dogmatix.util.BiosNote
import com.cortinadev.dogmatix.util.FileParsingUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BiosUiState(val loading: Boolean = true, val allSystems: Boolean = false, val report: BiosReport? = null)

@HiltViewModel
class BiosViewModel @Inject constructor(
    private val bios: BiosService,
    private val appSettings: AppSettings
) : ViewModel() {
    private val _ui = MutableStateFlow(BiosUiState())
    val ui: StateFlow<BiosUiState> = _ui.asStateFlow()
    val folder: StateFlow<String> = appSettings.biosDir.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init { refresh() }

    fun refresh(all: Boolean = _ui.value.allSystems) {
        _ui.value = _ui.value.copy(loading = true, allSystems = all)
        viewModelScope.launch { _ui.value = BiosUiState(false, all, bios.check(all)) }
    }

    fun setFolder(uri: String) {
        viewModelScope.launch { appSettings.setBiosDir(uri); refresh() }
    }
}

/** BIOS check: is every BIOS file the consoles in Sources need in the emulator's system folder, and the right dump? */
@Composable
fun BiosScreen(viewModel: BiosViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val folder by viewModel.folder.collectAsState()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.setFolder(it.toString())
        }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(ui.loading) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_bios))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                ToolRow(
                    stringResource(R.string.bios_folder),
                    listOf(if (folder.isBlank()) stringResource(R.string.bios_folder_hint) else FileParsingUtils.toUserReadablePath(folder)),
                    onClick = { picker.launch(null) },
                    modifier = Modifier.focusRequester(firstFocus)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PillButton(stringResource(R.string.settings_change)) { picker.launch(null) }
                        PillButton(stringResource(if (ui.allSystems) R.string.bios_show_mine else R.string.bios_show_all)) { viewModel.refresh(!ui.allSystems) }
                    }
                }
            }
            val report = ui.report
            when {
                ui.loading -> item { Row(Modifier.padding(16.dp)) { CircularProgressIndicator() } }
                report == null || report.results.isEmpty() -> item { InfoCard(listOf(stringResource(R.string.bios_none_needed)), Modifier.padding(16.dp)) }
                else -> items(report.results, key = { it.system.name }) { r ->
                    val badge = when {
                        !report.folderSet -> null
                        r.allGood -> stringResource(R.string.bios_ready)
                        r.ready -> stringResource(R.string.bios_ready_other)
                        else -> stringResource(R.string.bios_missing)
                    }
                    ToolRow(
                        r.system.name,
                        r.files.map { f -> fileLine(f) },
                        onClick = { },
                        badge = badge?.let { b -> { Badge(b, warning = !r.ready) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun fileLine(f: BiosCatalog.FileResult): String {
    val state = when (f.state) {
        BiosCatalog.State.OK -> stringResource(R.string.bios_file_ok)
        BiosCatalog.State.OTHER_VERSION -> stringResource(R.string.bios_file_other)
        BiosCatalog.State.MISSING -> stringResource(if (f.file.required) R.string.bios_file_missing else R.string.bios_file_missing_optional)
        BiosCatalog.State.PRESENT_UNCHECKED -> stringResource(R.string.bios_file_present)
    }
    val note = f.file.note?.let { stringResource(noteText(it)) }
    return listOfNotNull(f.file.path, state, note, f.file.label.takeIf { it.isNotBlank() }).joinToString(" · ")
}

private fun noteText(note: BiosNote): Int = when (note) {
    BiosNote.JAPAN -> R.string.bios_note_japan
    BiosNote.USA -> R.string.bios_note_usa
    BiosNote.EUROPE -> R.string.bios_note_europe
    BiosNote.USA_OLDER -> R.string.bios_note_usa_older
    BiosNote.USA_EUROPE -> R.string.bios_note_usa_europe
    BiosNote.PS2_ANY -> R.string.bios_note_ps2_any
    BiosNote.GBA_OPTIONAL -> R.string.bios_note_gba_optional
    BiosNote.BOOT_LOGO -> R.string.bios_note_boot_logo
    BiosNote.ORIGINAL_BIOS_MODE -> R.string.bios_note_original_bios_mode
    BiosNote.NEOGEO_SET -> R.string.bios_note_neogeo_set
}
