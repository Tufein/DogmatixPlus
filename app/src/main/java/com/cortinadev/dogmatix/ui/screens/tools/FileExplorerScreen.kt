package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.cortinadev.dogmatix.ui.screens.sources.components.ConfirmDialog
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DiskDir
import com.cortinadev.dogmatix.util.DiskEntry
import com.cortinadev.dogmatix.util.DiskFile
import com.cortinadev.dogmatix.util.DiskScanner
import com.cortinadev.dogmatix.util.DuplicateFinder
import com.cortinadev.dogmatix.util.SetChecker
import com.cortinadev.dogmatix.util.SetProblem
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A starting point of the file explorer: one of the folders the app was given access to. */
data class ExplorerRoot(val label: String, val dir: DiskDir)

data class ExplorerState(
    val roots: List<ExplorerRoot> = emptyList(),
    /** Folders opened, root first; empty = the list of roots. */
    val path: List<Pair<String, DiskDir>> = emptyList(),
    val entries: List<DiskEntry> = emptyList(),
    val loading: Boolean = false,
    val bySize: Boolean = false,
    /** Result of "Size of this folder": bytes and files, for the folder it was asked for. */
    val folderSize: Pair<Long, Int>? = null,
    val sizing: Boolean = false,
    val problems: List<SetProblem>? = null
) {
    val atRoots: Boolean get() = path.isEmpty()
    val sorted: List<DiskEntry> get() = entries.sortedWith(
        compareByDescending<DiskEntry> { it.isDirectory }.then(
            if (bySize) compareByDescending { it.size } else compareBy { it.name.lowercase() }
        )
    )
}

/**
 * Looks inside the folders the app may use (download folder, per-console folders, the save sync and
 * ES-DE folders): what is really there, how big it is, whether a file counts as a game, and whether
 * the disc sets of a folder are complete. Files can be opened in another app or deleted.
 */
@HiltViewModel
class FileExplorerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ExplorerState())
    val state: StateFlow<ExplorerState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val roots = buildList {
                settings.downloadDirectory.first().takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) }
                    ?.let { add(ExplorerRoot(context.getString(R.string.files_root_downloads), it)) }
                settings.consoleDownloadDirectories.first().forEach { (consoleId, uri) ->
                    DiskScanner.rootOf(uri)?.let { add(ExplorerRoot(ConsoleFormatter.getConsoleDisplayName(consoleId), it)) }
                }
                settings.saveSyncSavesDir.first().takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) }
                    ?.let { add(ExplorerRoot(context.getString(R.string.save_sync_saves_folder), it)) }
                settings.saveSyncStatesDir.first().takeIf { it.isNotBlank() && it != settings.saveSyncSavesDir.first() }?.let { DiskScanner.rootOf(it) }
                    ?.let { add(ExplorerRoot(context.getString(R.string.save_sync_states_folder), it)) }
                settings.esdeDirectory.first().takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) }
                    ?.let { add(ExplorerRoot(context.getString(R.string.files_root_esde), it)) }
            }.distinctBy { DiskScanner.canonicalKey(it.dir) }
            _state.update { it.copy(roots = roots) }
        }
    }

    fun openRoot(root: ExplorerRoot) = open(listOf(root.label to root.dir))

    fun openFolder(entry: DiskEntry) {
        val current = _state.value.path.lastOrNull() ?: return
        open(_state.value.path + (entry.name to DiskScanner.dirOf(current.second, entry)))
    }

    /** One level up; false when already at the list of roots. */
    fun up(): Boolean {
        val path = _state.value.path
        if (path.isEmpty()) return false
        if (path.size == 1) _state.update { it.copy(path = emptyList(), entries = emptyList(), folderSize = null, problems = null) }
        else open(path.dropLast(1))
        return true
    }

    fun refresh() { _state.value.path.takeIf { it.isNotEmpty() }?.let { open(it) } }

    fun toggleSort() = _state.update { it.copy(bySize = !it.bySize) }

    private fun open(path: List<Pair<String, DiskDir>>) {
        _state.update { it.copy(path = path, loading = true, folderSize = null, problems = null) }
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { DiskScanner.list(context, path.last().second).filterNot { it.name.endsWith(".tmp") && it.name.startsWith(".") } }
            if (_state.value.path == path) _state.update { it.copy(entries = entries, loading = false) }
        }
    }

    /** Adds up every file below the current folder (up to six levels deep). */
    fun measure() {
        val dir = _state.value.path.lastOrNull()?.second ?: return
        _state.update { it.copy(sizing = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var bytes = 0L; var files = 0
                fun walk(d: DiskDir, depth: Int) {
                    for (e in DiskScanner.list(context, d)) {
                        if (e.isDirectory) { if (depth < 6) walk(DiskScanner.dirOf(d, e), depth + 1) } else { bytes += e.size; files++ }
                    }
                }
                walk(dir, 0)
                bytes to files
            }
            _state.update { it.copy(folderSize = result, sizing = false) }
        }
    }

    /** Runs the disc-set check on the files of this folder. */
    fun checkSets() {
        val (label, dir) = _state.value.path.lastOrNull() ?: return
        val files = _state.value.entries.filter { !it.isDirectory }.map {
            DiskFile(scope = "", consoleId = null, folder = label, name = it.name, size = it.size, uri = it.uri.toString(), dirId = "here", fileId = it.uri.toString())
        }
        viewModelScope.launch {
            val problems = withContext(Dispatchers.IO) {
                SetChecker.check(files) { f ->
                    runCatching { context.contentResolver.openInputStream(android.net.Uri.parse(f.uri))?.use { it.readNBytes(SetChecker.MAX_SHEET_BYTES.toInt()) }?.toString(Charsets.UTF_8) }.getOrNull()
                }
            }
            _state.update { it.copy(problems = problems) }
        }
    }

    fun openFile(context: Context, entry: DiskEntry) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(entry.uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (runCatching { context.startActivity(Intent.createChooser(intent, entry.name)) }.isFailure) {
            ToastUtil.showInfo(context, context.getString(R.string.download_open_none))
        }
    }

    fun delete(context: Context, entry: DiskEntry) {
        val app = context.applicationContext
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { DiskScanner.delete(app, entry.uri) }
            if (ok) ToastUtil.showSuccess(app, app.getString(R.string.duplicates_deleted, entry.name))
            else ToastUtil.showError(app, app.getString(R.string.duplicates_delete_failed, entry.name))
            refresh()
        }
    }
}

@Composable
fun FileExplorerScreen(viewModel: FileExplorerViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<DiskEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<DiskEntry?>(null) }
    BackHandler(enabled = !ui.atRoots) { viewModel.up() }

    selected?.let { entry ->
        FileDetailsDialog(
            entry = entry,
            onOpen = { selected = null; viewModel.openFile(context, entry) },
            onDelete = { selected = null; confirmDelete = entry },
            onDismiss = { selected = null }
        )
    }
    confirmDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.files_delete_title),
            message = if (entry.isDirectory) stringResource(R.string.files_delete_folder_message, entry.name)
            else stringResource(R.string.files_delete_file_message, entry.name, formatBytes(entry.size)),
            confirmText = stringResource(R.string.duplicates_delete),
            onConfirm = { viewModel.delete(context, entry) },
            onDismiss = { confirmDelete = null }
        )
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(ui.path.size, ui.loading) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    val title = if (ui.atRoots) stringResource(R.string.nav_files) else ui.path.joinToString(" / ") { it.first }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(title)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 12.dp), modifier = Modifier.fillMaxSize()) {
            if (ui.atRoots) {
                if (ui.roots.isEmpty()) item { InfoCard(listOf(stringResource(R.string.tools_no_folder))) }
                items(ui.roots, key = { "root:" + it.label }) { root ->
                    val index = ui.roots.indexOf(root)
                    ToolRow(root.label, listOf(stringResource(R.string.files_root_hint)), { viewModel.openRoot(root) },
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier) {
                        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                item(key = "tools") {
                    val folders = ui.entries.count { it.isDirectory }
                    val files = ui.entries.size - folders
                    val summary = if (ui.loading) stringResource(R.string.tools_scanning)
                    else stringResource(R.string.files_summary, folders, files, formatBytes(ui.entries.filter { !it.isDirectory }.sumOf { it.size }))
                    val lines = listOfNotNull(
                        ui.folderSize?.let { (bytes, count) -> stringResource(R.string.files_size_result, formatBytes(bytes), count) },
                        if (ui.sizing) stringResource(R.string.files_sizing) else null
                    )
                    ToolRow(summary, lines, viewModel::up, Modifier.focusRequester(firstFocus)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PillButton(stringResource(R.string.files_up), { viewModel.up() })
                            PillButton(stringResource(if (ui.bySize) R.string.files_sort_name else R.string.files_sort_size), viewModel::toggleSort)
                            PillButton(stringResource(R.string.files_measure), viewModel::measure)
                            PillButton(stringResource(R.string.files_check), viewModel::checkSets)
                        }
                    }
                }
                ui.problems?.let { problems ->
                    item(key = "problems") {
                        InfoCard(
                            if (problems.isEmpty()) listOf(stringResource(R.string.files_check_ok))
                            else listOf(pluralStringResource(R.plurals.files_check_problems, problems.size, problems.size)) +
                                problems.map { p -> p.sheet.name + ": " + when (p.kind) {
                                    SetProblem.Kind.EMPTY_SHEET -> context.getString(R.string.sets_empty_sheet)
                                    SetProblem.Kind.MISSING_DISCS -> context.getString(R.string.sets_missing_discs, p.missing.joinToString(", "))
                                    SetProblem.Kind.MISSING_TRACKS -> context.getString(R.string.sets_missing_tracks, p.missing.joinToString(", "))
                                } },
                            accent = problems.isNotEmpty()
                        )
                    }
                }
                if (!ui.loading && ui.entries.isEmpty()) item(key = "empty") { InfoCard(listOf(stringResource(R.string.files_empty))) }
                items(ui.sorted, key = { it.documentId }) { entry ->
                    if (entry.isDirectory) {
                        ToolRow("📁 " + entry.name, emptyList(), { viewModel.openFolder(entry) }) {
                            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        val game = DuplicateFinder.isGameFile(entry.name)
                        ToolRow(
                            entry.name,
                            listOf(formatBytes(entry.size)),
                            { selected = entry },
                            badge = if (game) ({ Badge(stringResource(R.string.files_badge_game), warning = false) }) else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDetailsDialog(entry: DiskEntry, onOpen: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val openFocus = rememberInitialFocus()
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.files_info_size, formatBytes(entry.size), entry.size))
                Text(stringResource(R.string.files_info_type, ext.ifEmpty { "—" }))
                Text(stringResource(if (DuplicateFinder.isGameFile(entry.name)) R.string.files_info_game else R.string.files_info_not_game),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.download_open), onClick = onOpen, initialFocus = openFocus) },
        dismissButton = {
            DialogButton(text = stringResource(R.string.duplicates_delete), onClick = onDelete)
            DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss)
        }
    )
}
