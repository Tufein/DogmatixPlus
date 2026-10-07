package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import androidx.compose.ui.focus.focusRequester
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.*
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.ui.components.*
import com.cortinadev.dogmatix.util.DiskScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

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
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {
    val busy = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    fun action(operation: LibraryOperation, purge: Boolean = false) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true; failed.value = false
            try {
                withContext(Dispatchers.IO) { when (operation.kind) {
                    "trash" -> if (purge) trash.purge(operation.id) else trash.restore(operation.id)
                    "library_move" -> mover.start(operation.target)
                    "smart_move" -> gate.lock.withLock {
                        val fresh = history.get(operation.id) ?: return@withLock
                        // Never delete originals before the console actually points at the verified target.
                        val record = smartSettings.recordsNow()[fresh.consoleId]
                        val committed = if (fresh.destinationPlace == "SD") settings.consoleDownloadDirectories.first()[fresh.consoleId] == fresh.target
                            else fresh.destinationPlace == "INTERNAL" && record?.place == com.cortinadev.dogmatix.util.SmartStorage.Place.INTERNAL && record.movedAt >= fresh.time - 1000
                        check(committed) { "Move not committed; retry in Storage" }
                        check(fresh.phase in setOf("ready", "cleanup")) { "Retry in Storage first" }
                        val files = fresh.files.map { file ->
                            val target = Uri.parse(file.target)
                            check(copier.hash(target) == file.hash)
                            val source = Uri.parse(file.source)
                            val exists = androidx.documentfile.provider.DocumentFile.fromSingleUri(getContext(), source)?.exists()
                            if (exists == false) file.copy(removed = true)
                            else { check(copier.mayRemove(source, target, file.hash)); check(DiskScanner.delete(getContext(), source)); file.copy(removed = true) }
                        }
                        history.put(fresh.copy(files = files, phase = "done"))
                    }
                }
                }
                library.requestRefresh()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed.value = true }
            finally { busy.value = false }
        }
    }
    private fun getContext() = context
}

@Composable
fun RecoveryScreen(viewModel: RecoveryViewModel = hiltViewModel(), onNavigate: (String) -> Unit = {}) {
    val entries by viewModel.history.entries.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val context = LocalContext.current
    val firstFocus = rememberInitialFocus()
    val shareTitle = stringResource(R.string.recovery_share)
    var days by remember { mutableIntStateOf(viewModel.trash.retentionDays) }
    var automatic by remember { mutableStateOf(viewModel.trash.autoClean) }
    var purge by remember { mutableStateOf<LibraryOperation?>(null) }
    val trashBytes = entries.filter { it.kind == "trash" && it.phase != "done" }.sumOf { op -> op.files.sumOf { it.bytes } }
    purge?.let { operation ->
        val cancelFocus = rememberInitialFocus()
        AlertDialog(modifier = Modifier.closeOnGamepadB { purge = null }, onDismissRequest = { purge = null },
            title = { Text(stringResource(R.string.recovery_purge)) }, text = { Text(stringResource(R.string.recovery_purge_warning)) },
            confirmButton = { DialogButton(stringResource(R.string.recovery_purge), { purge = null; viewModel.action(operation, true) }) },
            dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), { purge = null }, initialFocus = cancelFocus) })
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle(stringResource(R.string.recovery_title), icon = R.drawable.ic_history) }
        item { Text(stringResource(R.string.recovery_space, formatBytes(trashBytes)), style = MaterialTheme.typography.bodyLarge) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill(stringResource(R.string.recovery_days, days), { days = when (days) { 7 -> 30; 30 -> 90; else -> 7 }; viewModel.trash.retentionDays = days }, modifier = Modifier.focusRequester(firstFocus), icon = R.drawable.ic_history)
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(automatic, { automatic = it; viewModel.trash.autoClean = it })
                Text(stringResource(R.string.recovery_automatic), modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            ActionPill(stringResource(R.string.recovery_share), {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, viewModel.history.report()), shareTitle))
            }, icon = R.drawable.ic_share)
            // 2.4.0: the readable history of what the app did, with Restore / Download again.
            ActionPill(stringResource(R.string.hist24_title), { onNavigate(ACTION_HISTORY_ROUTE) }, icon = R.drawable.ic_manage_history)
        }
        if (busy) item { CircularProgressIndicator() }
        if (failed || viewModel.history.unreadable) item { Text(stringResource(R.string.recovery_action_failed), color = MaterialTheme.colorScheme.error) }
        if (entries.isEmpty()) item { Text(stringResource(R.string.recovery_empty)) }
        items(entries, key = { it.id }) { entry ->
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
                        if (entry.phase != "purging") ActionPill(stringResource(R.string.recovery_restore), { viewModel.action(entry) }, icon = R.drawable.ic_restore)
                        if (entry.phase in setOf("stored", "purging")) ActionPill(stringResource(R.string.recovery_purge), { purge = entry }, icon = R.drawable.ic_trash, tone = ActionTone.Danger)
                    } else if (entry.kind in setOf("save_sync", "device_sync", "cloud_backup", "download")) {
                        ActionPill(stringResource(R.string.recovery_resume), { onNavigate(when (entry.kind) { "save_sync" -> "save_sync"; "download" -> "downloads"; else -> "cloud_backup" }) }, icon = R.drawable.ic_settings)
                    } else if (entry.kind == "library_move" || (entry.kind == "smart_move" && entry.phase in setOf("ready", "cleanup"))) {
                        ActionPill(stringResource(R.string.recovery_resume), { viewModel.action(entry) }, icon = R.drawable.ic_restore)
                    }
                }
            }
        }
    }
}
