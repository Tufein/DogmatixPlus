package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import androidx.compose.ui.focus.focusRequester
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.*
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.ui.components.*
import com.cortinadev.dogmatix.util.*
import com.cortinadev.dogmatix.data.local.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

sealed interface RecoveryConfirmation {
    data class Operation(val preview: OperationRestorePreview) : RecoveryConfirmation
    data class Replacement(val preview: ReplacementRestorePreview) : RecoveryConfirmation
    data class Save(val preview: LocalSafetyRestorePreview) : RecoveryConfirmation
}

@HiltViewModel
class RecoveryViewModel @Inject constructor(
    val history: OperationHistoryService,
    val trash: TrashService,
    private val mover: LibraryMoveService,
    private val library: LibraryIndexService,
    private val copier: VerifiedDocumentCopy,
    private val gate: StorageMoveGate,
    private val settings: SettingsRepository,
    private val smartSettings: com.cortinadev.dogmatix.data.local.SmartStorageSettings,
    private val appSettings: AppSettings,
    private val hub: RecoveryHubService,
    private val downloads: DownloadService,
    private val saveSync: SaveSyncService,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {
    val busy = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    val snapshot = MutableStateFlow(RecoveryHubSnapshot())
    val saveCopies = MutableStateFlow<List<SafetyCopy>>(emptyList())
    val resumable = MutableStateFlow<Map<String, Int>>(emptyMap())
    val confirmation = MutableStateFlow<RecoveryConfirmation?>(null)
    val loaded = MutableStateFlow(false)
    val refreshing = MutableStateFlow(false)
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            combine(appSettings.activeProfile, appSettings.profiles, history.entries) { _, _, _ -> Unit }.collect {
                confirmation.value = null
                loaded.value = false
                snapshot.value = RecoveryHubSnapshot()
                saveCopies.value = emptyList()
                resumable.value = emptyMap()
                refresh()
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            refreshing.value = true
            try { load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed.value = true }
            finally { refreshing.value = false }
        }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        val checked = hub.snapshot()
        val copies = saveSync.localSafetyCopies(checked.profileId)
        val counts = checked.storage.associate { state ->
            if (!StorageAvailability.writable(state.status) && state.location.downloadRoot) downloads.holdStorageUnavailable(state.location.uri)
            state.location.key to if (state.location.downloadRoot) downloads.resumableStorageDownloads(state.location.uri).size else 0
        }
        currentCoroutineContext().ensureActive()
        check(hub.isCurrent(checked))
        snapshot.value = checked
        saveCopies.value = copies
        resumable.value = counts
        loaded.value = true
    }

    fun action(operation: LibraryOperation, purge: Boolean = false) {
        inspect { RecoveryConfirmation.Operation(hub.previewOperation(operation.id, purge)) }
    }
    fun action(replacement: ReplacementRecovery) = inspect { RecoveryConfirmation.Replacement(hub.previewReplacement(replacement)) }
    fun action(copy: SafetyCopy) = inspect { RecoveryConfirmation.Save(saveSync.previewLocalSafetyRestore(appSettings.activeProfile.first(), copy)) }
    private fun inspect(preview: suspend () -> RecoveryConfirmation) = runAction { confirmation.value = preview() }
    fun dismissConfirmation() { confirmation.value = null }

    fun confirm() {
        val selected = confirmation.value ?: return
        confirmation.value = null
        when (selected) {
            is RecoveryConfirmation.Operation -> performOperation(selected.preview)
            else -> runAction {
                when (selected) {
                    is RecoveryConfirmation.Replacement -> hub.restoreReplacement(selected.preview)
                    is RecoveryConfirmation.Save -> check(saveSync.restoreLocalSafetyCopy(selected.preview) is CloudSaveResult.Done)
                    else -> Unit
                }
                library.requestRefresh()
                load()
            }
        }
    }
    fun resumeStorage(location: StorageLocation) {
        val selected = snapshot.value
        runAction {
            check(selected.storage.any { it.location == location })
            check(hub.isCurrent(selected))
            downloads.resumeStorageDownloads(location.uri, selected.profileId)
            load()
        }
    }
    private fun runAction(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; failed.value = false
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed.value = true }
            finally { busy.value = false }
        }
    }
    fun report(): String = snapshot.value.operations.joinToString("\n") { "${it.time} | ${it.kind} | ${it.phase} | ${maxOf(it.totalFiles, it.files.size)} files" }

    private fun performOperation(preview: OperationRestorePreview) {
        if (busy.value) return
        busy.value = true; failed.value = false
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { appSettings.withActiveProfile(preview.profileId) { val operation = hub.revalidate(preview); when (operation.kind) {
                    "trash" -> if (preview.purge) trash.purge(operation.id) else trash.restore(operation.id)
                    "library_move" -> mover.start(operation.target)
                    "smart_move" -> gate.lock.withLock {
                        val fresh = hub.revalidate(preview)
                        // Never delete originals before the console actually points at the verified target.
                        val record = smartSettings.recordsNow()[fresh.consoleId]
                        val committed = if (fresh.destinationPlace == "SD") settings.consoleDownloadDirectories.first()[fresh.consoleId] == fresh.target
                            else fresh.destinationPlace == "INTERNAL" && record?.place == com.cortinadev.dogmatix.util.SmartStorage.Place.INTERNAL && record.movedAt >= fresh.time - 1000
                        check(committed) { "Move not committed; retry in Storage" }
                        check(fresh.phase in setOf("ready", "cleanup")) { "Retry in Storage first" }
                        val files = fresh.files.map { file ->
                            currentCoroutineContext().ensureActive()
                            val target = Uri.parse(file.target)
                            check(copier.hash(target) == file.hash)
                            val source = Uri.parse(file.source)
                            val exists = androidx.documentfile.provider.DocumentFile.fromSingleUri(getContext(), source)?.exists()
                            if (exists == false) file.copy(removed = true)
                            else { check(copier.mayRemove(source, target, file.hash)); check(DiskScanner.delete(getContext(), source)); file.copy(removed = true) }
                        }
                        history.put(fresh.copy(files = files, phase = "done"))
                    }
                } }
                }
                library.requestRefresh()
                load()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed.value = true }
            finally { busy.value = false }
        }
    }
    private fun getContext() = context
}

@Composable
fun RecoveryScreen(viewModel: RecoveryViewModel = hiltViewModel(), onNavigate: (String) -> Unit = {}) {
    val snapshot by viewModel.snapshot.collectAsState()
    val entries = snapshot.operations
    val copies by viewModel.saveCopies.collectAsState()
    val resumable by viewModel.resumable.collectAsState()
    val loaded by viewModel.loaded.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val confirmation by viewModel.confirmation.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val context = LocalContext.current
    val firstFocus = rememberInitialFocus()
    val shareTitle = stringResource(R.string.recovery_share)
    var days by remember { mutableIntStateOf(viewModel.trash.retentionDays) }
    var automatic by remember { mutableStateOf(viewModel.trash.autoClean) }
    val trashBytes = entries.filter { it.kind == "trash" && it.phase != "done" }.sumOf { op -> op.files.sumOf { it.bytes } }
    confirmation?.let { RecoveryPreviewDialog(it, viewModel::confirm, viewModel::dismissConfirmation) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle(stringResource(R.string.road28_recovery_title), icon = R.drawable.ic_history) }
        item { Text(stringResource(R.string.road28_recovery_hint), style = MaterialTheme.typography.bodyMedium) }
        item { ActionPill(stringResource(R.string.road28_storage_check), viewModel::refresh, modifier = Modifier.focusRequester(firstFocus), icon = R.drawable.ic_restore) }
        if (refreshing || busy) item { CircularProgressIndicator() }
        items(snapshot.storage, key = { "storage:${it.location.key}" }) { state ->
            Panel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.location.label, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(when (state.status) {
                    StorageAccessStatus.AVAILABLE -> R.string.road28_storage_available
                    StorageAccessStatus.RETURNED -> R.string.road28_storage_returned
                    StorageAccessStatus.READ_ONLY -> R.string.road28_storage_read_only
                    StorageAccessStatus.ACCESS_LOST -> R.string.road28_storage_permission
                    else -> R.string.road28_storage_unavailable
                }))
                val count = resumable[state.location.key] ?: 0
                if (count > 0) Text(stringResource(R.string.road28_storage_paused, count), style = MaterialTheme.typography.bodySmall)
                if (!busy && count > 0 && StorageAvailability.writable(state.status)) ActionPill(stringResource(R.string.road28_storage_resume), { viewModel.resumeStorage(state.location) }, icon = R.drawable.ic_restore)
                if (!StorageAvailability.readable(state.status)) ActionPill(stringResource(R.string.road28_storage_settings), { onNavigate("settings") }, icon = R.drawable.ic_settings)
            }
        }
        if (snapshot.incompleteScan) item { Text(stringResource(R.string.road28_recovery_partial), style = MaterialTheme.typography.bodySmall) }
        if (snapshot.replacements.isNotEmpty()) item { Text(stringResource(R.string.road28_recovery_replacements), style = MaterialTheme.typography.titleMedium) }
        items(snapshot.replacements, key = { "replacement:${it.key}" }) { entry ->
            Panel(Modifier.fillMaxWidth().testTag("recovery-replacement-${entry.recovery.id}"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.recovery.fileName, style = MaterialTheme.typography.titleMedium)
                Text("${entry.folderLabel} · ${formatBytes(entry.bytes)}", style = MaterialTheme.typography.bodySmall)
                if (!busy) ActionPill(stringResource(R.string.road28_recovery_preview), { viewModel.action(entry) }, icon = R.drawable.ic_restore)
            }
        }
        if (copies.isNotEmpty()) item { Text(stringResource(R.string.road28_recovery_saves), style = MaterialTheme.typography.titleMedium) }
        items(copies, key = { "save:${it.relative}" }) { copy ->
            Panel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(copy.name, style = MaterialTheme.typography.titleMedium)
                Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(copy.takenAt)) + " · " + formatBytes(copy.size), style = MaterialTheme.typography.bodySmall)
                if (!busy) ActionPill(stringResource(R.string.road28_recovery_preview), { viewModel.action(copy) }, icon = R.drawable.ic_restore)
            }
        }
        item { Text(stringResource(R.string.recovery_space, formatBytes(trashBytes)), style = MaterialTheme.typography.bodyLarge) }
        item {
            if (loaded && snapshot.profileId.isBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(stringResource(R.string.recovery_days, days), { days = when (days) { 7 -> 30; 30 -> 90; else -> 7 }; viewModel.trash.retentionDays = days }, icon = R.drawable.ic_history)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(automatic, { automatic = it; viewModel.trash.autoClean = it })
                    Text(stringResource(R.string.recovery_automatic), modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            ActionPill(stringResource(R.string.recovery_share), {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, viewModel.report()), shareTitle))
            }, icon = R.drawable.ic_share)
            // 2.4.0: the readable history of what the app did, with Restore / Download again.
            ActionPill(stringResource(R.string.hist24_title), { onNavigate(ACTION_HISTORY_ROUTE) }, icon = R.drawable.ic_manage_history)
        }
        if (failed || viewModel.history.unreadable) item { Text(stringResource(R.string.recovery_action_failed), color = MaterialTheme.colorScheme.error) }
        if (loaded && entries.isEmpty() && copies.isEmpty() && snapshot.replacements.isEmpty()) item { Text(stringResource(R.string.recovery_empty)) }
        items(entries, key = { "operation:${it.id}" }) { entry ->
            Panel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val kind = when (entry.kind) {
                    "trash" -> R.string.recovery_to_trash
                    "library_move", "smart_move" -> R.string.recovery_move
                    "download" -> R.string.nav_downloads
                    "save_sync" -> R.string.nav_save_sync
                    else -> R.string.recovery_title
                }
                Text(stringResource(kind), style = MaterialTheme.typography.titleMedium)
                if (entry.title.isNotEmpty()) Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                val phase = when (entry.phase) {
                    "done" -> R.string.recovery_done
                    "stored" -> R.string.recovery_stored
                    "cleanup", "ready" -> R.string.recovery_cleanup
                    "conflict" -> R.string.recovery_conflict
                    "failed", "interrupted" -> R.string.recovery_interrupted
                    else -> R.string.recovery_pending
                }
                Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.time)) + " · " + stringResource(phase), style = MaterialTheme.typography.bodySmall)
                entry.files.take(20).forEach { Text("${it.name} · ${formatBytes(it.bytes)}", style = MaterialTheme.typography.bodySmall) }
                if (maxOf(entry.totalFiles, entry.files.size) > 20) Text(stringResource(R.string.recovery_file_count, maxOf(entry.totalFiles, entry.files.size)), style = MaterialTheme.typography.bodySmall)
                if (!busy && entry.phase != "done") {
                    if (entry.kind == "trash") {
                        if (entry.phase != "purging") ActionPill(stringResource(R.string.road28_recovery_preview), { viewModel.action(entry) }, icon = R.drawable.ic_restore)
                        if (entry.phase in setOf("stored", "purging")) ActionPill(stringResource(R.string.recovery_purge), { viewModel.action(entry, true) }, icon = R.drawable.ic_trash, tone = ActionTone.Danger)
                    } else if (entry.kind in setOf("save_sync", "device_sync", "cloud_backup", "download")) {
                        ActionPill(stringResource(R.string.recovery_resume), { onNavigate(when (entry.kind) { "save_sync" -> "save_sync"; "download" -> "downloads"; else -> "cloud_backup" }) }, icon = R.drawable.ic_settings)
                    } else if (entry.kind == "library_move" || (entry.kind == "smart_move" && entry.phase in setOf("ready", "cleanup"))) {
                        ActionPill(stringResource(R.string.road28_recovery_preview), { viewModel.action(entry) }, icon = R.drawable.ic_restore)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryPreviewDialog(pending: RecoveryConfirmation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = rememberInitialFocus()
    val purging = pending is RecoveryConfirmation.Operation && pending.preview.purge
    val moving = pending is RecoveryConfirmation.Operation && pending.preview.operation.kind in setOf("library_move", "smart_move")
    AlertDialog(modifier = Modifier.closeOnGamepadB(onDismiss), onDismissRequest = onDismiss,
        title = { Text(stringResource(if (purging) R.string.recovery_purge else R.string.road28_recovery_preview)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (pending) {
                    is RecoveryConfirmation.Operation -> {
                        Text(stringResource(if (purging) R.string.recovery_purge_warning else R.string.road28_recovery_keep_current))
                        if (moving) {
                            Text(stringResource(R.string.road28_recovery_from, pending.preview.source))
                            Text(stringResource(R.string.road28_recovery_to, pending.preview.target))
                        }
                        pending.preview.files.forEach { file ->
                            Text("${file.name} · ${formatBytes(file.bytes)}")
                            Text(stringResource(R.string.road28_recovery_from, file.source), style = MaterialTheme.typography.bodySmall)
                            if (!purging) Text(stringResource(R.string.road28_recovery_to, file.target), style = MaterialTheme.typography.bodySmall)
                        }
                        val count = maxOf(pending.preview.operation.totalFiles, pending.preview.operation.files.size)
                        if (count > pending.preview.files.size) Text(stringResource(R.string.recovery_file_count, count))
                    }
                    is RecoveryConfirmation.Replacement -> {
                        val entry = pending.preview.replacement
                        Text("${entry.recovery.fileName} · ${formatBytes(entry.bytes)}")
                        Text(stringResource(R.string.road28_recovery_from, entry.folderLabel))
                        Text(stringResource(R.string.road28_recovery_to, "${entry.folderLabel}/${entry.recovery.fileName}"))
                        Text(stringResource(R.string.recovery27_confirm, entry.recovery.fileName))
                    }
                    is RecoveryConfirmation.Save -> {
                        Text("${pending.preview.copy.name} · ${formatBytes(pending.preview.copy.size)}")
                        Text(stringResource(R.string.road28_recovery_from, stringResource(R.string.road28_recovery_saves)))
                        Text(stringResource(R.string.road28_recovery_to, pending.preview.copy.path))
                        Text(stringResource(R.string.road28_recovery_save_protected))
                    }
                }
            }
        },
        confirmButton = { DialogButton(stringResource(if (purging) R.string.recovery_purge else if (moving) R.string.recovery_resume else R.string.recovery_restore), onConfirm) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onDismiss, initialFocus = cancelFocus) })
}
