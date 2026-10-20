package com.cortinadev.dogmatix.ui.screens.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.service.*
import com.cortinadev.dogmatix.util.GamePackages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class PackageTransferViewModel @Inject constructor(
    private val service: PackageTransferService,
    private val library: LibraryIndexService,
    private val settings: AppSettings
) : ViewModel() {
    val snapshot = MutableStateFlow<PackageTransferSnapshot?>(null)
    val imported = MutableStateFlow<List<GamePackages.Manifest>?>(null)
    val preview = MutableStateFlow<PackageTransferPreview?>(null)
    val busy = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    val exported = MutableStateFlow(false)
    val restored = MutableStateFlow<Int?>(null)
    private var actionJob: Job? = null
    private var refreshJob: Job? = null
    init {
        viewModelScope.launch {
            combine(settings.activeProfile, settings.profiles) { _, _ -> Unit }.collect {
                actionJob?.cancel()
                snapshot.value = null; imported.value = null; preview.value = null
                exported.value = false; restored.value = null; failed.value = false
                refresh()
            }
        }
    }
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try { snapshot.value = service.snapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed.value = true }
        }
    }
    fun export(uri: Uri, selected: PackageTransferSnapshot) = action {
        service.export(selected, uri)
        exported.value = true
    }
    fun read(uri: Uri, expectedProfile: String) = action {
        check(settings.activeProfile.first() == expectedProfile)
        check(snapshot.value?.restrictions?.active == false)
        val records = service.read(uri)
        check(settings.activeProfile.first() == expectedProfile)
        imported.value = records
        preview.value = null
    }
    fun check(location: StorageLocation) {
        val records = imported.value ?: return
        preview.value = null
        action { preview.value = service.preview(records, location) }
    }
    fun restore(selected: PackageTransferPreview) = action {
        restored.value = service.restore(selected)
        preview.value = null
        library.requestRefresh()
        snapshot.value = service.snapshot()
    }
    fun cancel() { actionJob?.cancel(); preview.value = null }
    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; failed.value = false; exported.value = false; restored.value = null
        actionJob = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed.value = true }
            finally { busy.value = false }
        }
    }
}

@Composable
fun PackageTransferScreen(viewModel: PackageTransferViewModel = hiltViewModel(), onNavigate: (String) -> Unit = {}) {
    val snapshot by viewModel.snapshot.collectAsState()
    val imported by viewModel.imported.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val exported by viewModel.exported.collectAsState()
    val restored by viewModel.restored.collectAsState()
    var pendingExport by remember { mutableStateOf<PackageTransferSnapshot?>(null) }
    var pendingProfile by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<PackageTransferPreview?>(null) }
    var details by remember { mutableStateOf<GamePackages.Manifest?>(null) }
    LaunchedEffect(snapshot?.profileId, snapshot?.restrictions) {
        pendingExport = null; pendingProfile = null; confirm = null; details = null
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val selected = pendingExport
        pendingExport = null
        if (uri != null && selected != null) viewModel.export(uri, selected)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val profile = pendingProfile
        pendingProfile = null
        if (uri != null && profile != null) viewModel.read(uri, profile)
    }
    val exportFocus = remember { FocusRequester() }
    val importFocus = remember { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    var initialFocusGiven by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot != null) {
        if (snapshot != null && !initialFocusGiven) {
            withFrameNanos { }
            val target = when {
                snapshot?.records?.isNotEmpty() == true -> exportFocus
                snapshot?.restrictions?.active == false -> importFocus
                else -> refreshFocus
            }
            repeat(5) {
                if (runCatching { target.requestFocus() }.getOrDefault(false)) {
                    initialFocusGiven = true
                    return@LaunchedEffect
                }
                withFrameNanos { }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.package_transfer_title), icon = R.drawable.ic_stacks)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            item { InfoCard(listOf(stringResource(R.string.package_transfer_hint))) }
            item {
                OutlinedButton(onClick = { onNavigate(GAME_TRANSFER_ROUTE) }, modifier = Modifier.fillMaxWidth().testTag("package-transfer-games")) {
                    Text(stringResource(R.string.transfer31_open))
                }
            }
            if (failed) item { InfoCard(listOf(stringResource(R.string.package_transfer_failed)), danger = true) }
            if (exported) item { InfoCard(listOf(stringResource(R.string.package_transfer_exported)), accent = true) }
            restored?.let { count -> item { InfoCard(listOf(stringResource(R.string.package_transfer_restored, count)), accent = true) } }
            item {
                Button(onClick = {
                    snapshot?.let { selected -> pendingExport = selected; exportPicker.launch("valkyr-packages-${System.currentTimeMillis()}.json") }
                }, enabled = !busy && snapshot?.records?.isNotEmpty() == true,
                    modifier = Modifier.fillMaxWidth().focusRequester(exportFocus).testTag("package-transfer-export")) {
                    Text(stringResource(R.string.package_transfer_export, snapshot?.records?.size ?: 0))
                }
            }
            item {
                Button(onClick = {
                    pendingProfile = snapshot?.profileId
                    importPicker.launch(arrayOf("application/json", "application/octet-stream"))
                }, enabled = !busy && snapshot != null && snapshot?.restrictions?.active == false,
                    modifier = Modifier.fillMaxWidth().focusRequester(importFocus).testTag("package-transfer-import")) {
                    Text(stringResource(R.string.package_transfer_import))
                }
            }
            if (snapshot?.restrictions?.active == true) item { InfoCard(listOf(stringResource(R.string.package_transfer_restricted))) }
            if (busy) item {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.package_transfer_checking))
                    TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.package_transfer_cancel)) }
                }
            }
            if (imported != null && !busy) {
                item { Text(stringResource(R.string.package_transfer_folder), style = MaterialTheme.typography.titleMedium) }
                if (snapshot?.locations.isNullOrEmpty()) item { InfoCard(listOf(stringResource(R.string.package_transfer_no_folders)), danger = true) }
                items(snapshot?.locations.orEmpty(), key = { it.uri }) { location ->
                    OutlinedButton(onClick = { viewModel.check(location) }, modifier = Modifier.fillMaxWidth()) {
                        Text(location.label)
                    }
                }
            }
            preview?.let { checked ->
                item { InfoCard(listOf(checked.location.label, stringResource(R.string.package_transfer_preview, checked.checks.size))) }
                val verified = checked.copy(checks = checked.checks.filter { it.ready })
                if (verified.checks.size != checked.checks.size) item {
                    InfoCard(listOf(stringResource(R.string.package_transfer_selection, verified.checks.size, checked.checks.size)))
                }
                items(checked.checks, key = { it.record.sourceIdentity }) { check ->
                    val status = when {
                        !check.sourceFound -> stringResource(R.string.package_transfer_source_missing)
                        !check.existingCompatible -> stringResource(R.string.package_transfer_conflict)
                        check.missing.isNotEmpty() -> stringResource(R.string.package_transfer_missing, check.missing.size)
                        check.changed.isNotEmpty() -> stringResource(R.string.package_transfer_changed, check.changed.size)
                        else -> stringResource(R.string.package_transfer_ready)
                    }
                    ToolRow(check.record.fileName, listOf(check.record.consoleId, status), onClick = { details = check.record }) {
                        ToolAction(stringResource(R.string.package_transfer_files, check.record.parts.size)) { details = check.record }
                    }
                }
                item {
                    Button(onClick = { confirm = verified }, enabled = !busy && verified.ready,
                        modifier = Modifier.fillMaxWidth().testTag("package-transfer-restore")) {
                        Text(stringResource(R.string.package_transfer_restore))
                    }
                }
            }
            item { TextButton(onClick = viewModel::refresh, enabled = !busy,
                modifier = Modifier.focusRequester(refreshFocus).testTag("package-transfer-refresh")) {
                Text(stringResource(R.string.tools_refresh))
            } }
        }
    }
    confirm?.let { selected ->
        val cancelFocus = rememberInitialFocus()
        AlertDialog(onDismissRequest = { confirm = null }, modifier = Modifier.closeOnGamepadB { confirm = null },
            title = { Text(stringResource(R.string.package_transfer_restore)) },
            text = { Text(stringResource(R.string.package_transfer_confirm, selected.checks.size, selected.checks.sumOf { it.record.parts.size }) + "\n" + selected.location.label,
                Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { DialogButton(stringResource(R.string.package_transfer_restore), onClick = { confirm = null; viewModel.restore(selected) }) },
            dismissButton = { DialogButton(stringResource(R.string.package_transfer_cancel), onClick = { confirm = null }, initialFocus = cancelFocus) })
    }
    details?.let { record ->
        val closeFocus = rememberInitialFocus()
        AlertDialog(onDismissRequest = { details = null }, modifier = Modifier.closeOnGamepadB { details = null },
            title = { Text(record.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
            LazyColumn { items(record.parts, key = { it.path }) { part ->
                Text(listOf(record.subPath, part.path).filter { it.isNotEmpty() }.joinToString("/"), Modifier.padding(vertical = 4.dp))
            } }
        }, confirmButton = { DialogButton(stringResource(R.string.package_transfer_cancel), onClick = { details = null }, initialFocus = closeFocus) })
    }
}
