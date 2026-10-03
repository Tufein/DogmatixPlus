package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.LibraryScanService
import com.cortinadev.dogmatix.data.service.LibraryToolsService
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.cortinadev.dogmatix.ui.screens.sources.components.ConfirmDialog
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.GameEntry
import com.cortinadev.dogmatix.util.StorageInsights
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageUiState(
    val loading: Boolean = true,
    val folderSet: Boolean = true,
    val freeBytes: Long? = null,
    val entries: List<GameEntry> = emptyList(),
    val usage: List<StorageInsights.ConsoleUsage> = emptyList(),
    val biggest: List<GameEntry> = emptyList(),
    val queue: StorageInsights.QueueNeed = StorageInsights.QueueNeed(0, 0)
) {
    val totalBytes: Long get() = entries.sumOf { it.size }
}

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val tools: LibraryToolsService,
    private val scanService: LibraryScanService,
    private val downloadService: DownloadService
) : ViewModel() {
    private val _ui = MutableStateFlow(StorageUiState())
    val ui: StateFlow<StorageUiState> = _ui.asStateFlow()
    private var job: Job? = null

    init { refresh() }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val disk = try { tools.disk() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val queue = StorageInsights.queueNeed(
                downloadService.getDownloads()
                    .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
                    .map { StorageInsights.QueueItem((it.fileSize - it.downloadedBytes).coerceAtLeast(0), StorageInsights.isExtractable(it.fileName.substringAfterLast('.', ""))) }
            )
            val entries = disk?.entries.orEmpty()
            _ui.value = StorageUiState(
                loading = false, folderSet = disk?.folderSet ?: true, freeBytes = disk?.freeBytes, entries = entries,
                usage = StorageInsights.usageByConsole(entries), biggest = StorageInsights.biggest(entries, 15), queue = queue
            )
        }
    }

    fun delete(context: Context, entry: GameEntry) {
        val app = context.applicationContext
        val failed = context.getString(R.string.duplicates_delete_failed, entry.baseName)
        val deleted = context.getString(R.string.duplicates_deleted, entry.baseName)
        viewModelScope.launch {
            val removed = try { scanService.delete(entry) } catch (e: CancellationException) { throw e } catch (e: Exception) { 0 }
            if (removed == 0) ToastUtil.showError(app, failed) else ToastUtil.showSuccess(app, deleted)
            refresh()
        }
    }
}

/** Where the space goes: per console, the biggest games, and whether the queued downloads still fit. */
@Composable
fun StorageScreen(viewModel: StorageViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<GameEntry?>(null) }
    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.duplicates_delete_title),
            message = pluralStringResource(
                R.plurals.duplicates_delete_message, entry.files.size, entry.baseName, entry.folder,
                pluralStringResource(R.plurals.tools_files, entry.files.size, entry.files.size), formatBytes(entry.size)
            ) + "\n\n" + entry.files.take(8).joinToString("\n") { "• " + it.name } + if (entry.files.size > 8) "\n• … (+${entry.files.size - 8})" else "",
            confirmText = stringResource(R.string.duplicates_delete),
            onConfirm = { viewModel.delete(context, entry) },
            onDismiss = { pendingDelete = null }
        )
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    val summary = when {
        ui.loading -> stringResource(R.string.tools_scanning)
        !ui.folderSet -> stringResource(R.string.tools_no_folder)
        else -> pluralStringResource(R.plurals.storage_summary, ui.entries.size, ui.entries.size, formatBytes(ui.totalBytes))
    }
    val shortfall = StorageInsights.shortfall(ui.queue, ui.freeBytes)
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_storage))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "summary") {
                ToolRow(
                    summary,
                    listOfNotNull(ui.freeBytes?.let { stringResource(R.string.storage_free, formatBytes(it)) }),
                    viewModel::refresh, Modifier.focusRequester(firstFocus)
                ) { PillButton(stringResource(R.string.tools_refresh), viewModel::refresh) }
            }
            if (ui.queue.total > 0) item(key = "queue") {
                InfoCard(
                    listOfNotNull(
                        stringResource(R.string.storage_queue, formatBytes(ui.queue.total)),
                        when {
                            shortfall == null -> null
                            shortfall > 0 -> stringResource(R.string.storage_queue_short, formatBytes(shortfall))
                            else -> stringResource(R.string.storage_queue_fits)
                        }
                    ),
                    accent = shortfall != null && shortfall > 0
                )
            }
            if (!ui.loading && ui.usage.isNotEmpty()) {
                item(key = "usageHeader") { SectionHeader(stringResource(R.string.storage_by_console)) }
                items(ui.usage, key = { "u:" + (it.consoleId ?: it.scope) }) { usage ->
                    val name = usage.consoleId?.let { ConsoleFormatter.getConsoleDisplayName(it) }
                        ?: if (usage.scope.isEmpty()) stringResource(R.string.tools_root_folder)
                        else stringResource(R.string.tools_unknown_folder, usage.scope.removePrefix("folder:"))
                    val share = if (ui.totalBytes == 0L) 0 else (usage.bytes * 100 / ui.totalBytes).toInt()
                    ToolRow(name, listOf("${pluralStringResource(R.plurals.storage_games, usage.games, usage.games)} · ${formatBytes(usage.bytes)} · $share%"), onClick = {})
                }
                item(key = "biggestHeader") { SectionHeader(stringResource(R.string.storage_biggest), stringResource(R.string.storage_biggest_hint)) }
                items(ui.biggest, key = { "b:" + it.id }) { entry ->
                    ToolRow(
                        entry.baseName,
                        listOf(entry.folder, formatBytes(entry.size) + " · " + pluralStringResource(R.plurals.tools_files, entry.files.size, entry.files.size)),
                        { pendingDelete = entry }
                    ) { PillButton(stringResource(R.string.duplicates_delete)) { pendingDelete = entry } }
                }
            }
        }
    }
}
