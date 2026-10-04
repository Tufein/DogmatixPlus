package com.cortinadev.dogmatix.ui.screens.tools

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.service.ListImportService
import com.cortinadev.dogmatix.data.service.ListMatch
import com.cortinadev.dogmatix.data.state.PendingListImport
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.cortinadev.dogmatix.util.CollectionExport
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.ListImport
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ImportListUi(
    val consoles: List<ConsoleEntity> = emptyList(),
    /** 0 = all consoles, 1..n = [consoles] in order. */
    val consoleIndex: Int = 0,
    val titles: List<String> = emptyList(),
    /** Titles looked up so far, while a lookup runs. */
    val progress: Int? = null,
    val match: ListMatch? = null
) {
    val consoleId: String? get() = consoles.getOrNull(consoleIndex - 1)?.id
}

@HiltViewModel
class ImportListViewModel @Inject constructor(
    private val service: ListImportService,
    private val consoleRepository: ConsoleRepository,
    pending: PendingListImport
) : ViewModel() {
    private val _ui = MutableStateFlow(ImportListUi())
    val ui: StateFlow<ImportListUi> = _ui.asStateFlow()
    private var job: Job? = null

    init {
        val handed = pending.consume()
        viewModelScope.launch {
            val consoles = consoleRepository.getAllConsoles().first().sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) }
            val index = handed?.consoleId?.let { id -> consoles.indexOfFirst { it.id == id } + 1 } ?: 0
            _ui.update { it.copy(consoles = consoles, consoleIndex = index) }
            if (handed != null) { _ui.update { it.copy(titles = handed.titles) }; lookUp() }
        }
    }

    fun stepConsole(delta: Int) {
        _ui.update { s -> val n = s.consoles.size + 1; s.copy(consoleIndex = ((s.consoleIndex + delta) % n + n) % n) }
        lookUp()
    }

    fun readFile(context: Context, uri: Uri) {
        val app = context.applicationContext
        viewModelScope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
            }.getOrNull()
            if (text == null) ToastUtil.showError(app, app.getString(R.string.import_list_read_failed)) else useText(app, text)
        }
    }

    fun paste(context: Context) {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        useText(context.applicationContext, text)
    }

    private fun useText(app: Context, text: String) {
        val titles = ListImport.titles(text)
        if (titles.isEmpty()) { ToastUtil.showInfo(app, app.getString(R.string.import_list_empty)); return }
        _ui.update { it.copy(titles = titles) }
        lookUp()
    }

    private fun lookUp() {
        val titles = _ui.value.titles
        if (titles.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            _ui.update { it.copy(progress = 0, match = null) }
            val match = service.match(titles, _ui.value.consoleId) { done -> _ui.update { it.copy(progress = done) } }
            _ui.update { it.copy(progress = null, match = match) }
        }
    }

    fun download(context: Context) {
        val match = _ui.value.match ?: return
        service.download(match)
        ToastUtil.showSuccess(context, context.getString(R.string.bulk_queued, match.toDownload.size.toString()))
        _ui.update { it.copy(match = match.copy(toDownload = emptyList(), have = match.have + match.toDownload.map { f -> f.name })) }
    }

    fun wishMissing(context: Context) {
        val match = _ui.value.match ?: return
        val app = context.applicationContext
        viewModelScope.launch {
            val added = service.wishMissing(match, _ui.value.consoleId)
            ToastUtil.showSuccess(app, app.resources.getQuantityString(R.plurals.import_list_wished, added, added))
        }
    }
}

/** Download or wish the games of a list (a text file or the clipboard) in one go. */
@Composable
fun ImportListScreen(viewModel: ImportListViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.readFile(context, it) } }
    val pick = { picker.launch(arrayOf("text/plain", "text/csv", "text/*", "application/octet-stream")) }
    val firstFocus = rememberInitialFocus()
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_import_list))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 12.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "file") {
                ToolRow(stringResource(R.string.import_list_file), listOf(stringResource(R.string.import_list_file_hint)), pick, Modifier.focusRequester(firstFocus)) {
                    PillButton(stringResource(R.string.import_list_pick), pick)
                }
            }
            item(key = "paste") {
                ToolRow(stringResource(R.string.import_list_paste_title), listOf(stringResource(R.string.import_list_paste_hint)), { viewModel.paste(context) }) {
                    PillButton(stringResource(R.string.import_list_paste)) { viewModel.paste(context) }
                }
            }
            item(key = "console") {
                ToolRow(stringResource(R.string.import_list_console), listOf(stringResource(R.string.import_list_console_hint)), { viewModel.stepConsole(1) }) {
                    Stepper(
                        value = ui.consoleId?.let { ConsoleFormatter.getConsoleDisplayName(it) } ?: stringResource(R.string.wishlist_any_console),
                        onDecrement = { viewModel.stepConsole(-1) }, onIncrement = { viewModel.stepConsole(1) }, valueWidth = 180.dp
                    )
                }
            }
            ui.progress?.let { done ->
                item(key = "progress") { InfoCard(listOf(stringResource(R.string.import_list_progress, done, ui.titles.size))) }
            }
            ui.match?.let { match -> matchItems(match, ui.titles.size, onDownload = { viewModel.download(context) }, onWish = { viewModel.wishMissing(context) }) }
        }
    }
}

private fun LazyListScope.matchItems(match: ListMatch, total: Int, onDownload: () -> Unit, onWish: () -> Unit) {
    item(key = "summary") {
        InfoCard(listOf(
            stringResource(R.string.import_list_found, match.found, total),
            stringResource(R.string.import_list_have, match.have.size),
            stringResource(R.string.import_list_missing, match.missing.size)
        ), accent = match.toDownload.isNotEmpty())
    }
    if (match.toDownload.isNotEmpty()) item(key = "download") {
        ToolRow(
            stringResource(R.string.import_list_download),
            listOf(stringResource(R.string.import_list_download_hint, match.toDownload.size, CollectionExport.humanSize(match.totalBytes))),
            onDownload
        ) { PillButton(stringResource(R.string.import_list_download_action), onDownload) }
    }
    if (match.missing.isNotEmpty()) item(key = "wish") {
        ToolRow(stringResource(R.string.import_list_wish), listOf(stringResource(R.string.import_list_wish_hint)), onWish) {
            PillButton(stringResource(R.string.import_list_wish_action), onWish)
        }
    }
    if (match.toDownload.isNotEmpty()) {
        item(key = "found-header") { SectionHeader(stringResource(R.string.import_list_section_found)) }
        items(match.toDownload.take(300), key = { "f" + it.id }) { file ->
            ListLine(file.name + " · " + ConsoleFormatter.getConsoleShortName(file.consoleId))
        }
    }
    if (match.missing.isNotEmpty()) {
        item(key = "missing-header") { SectionHeader(stringResource(R.string.import_list_section_missing)) }
        items(match.missing.take(300), key = { "m$it" }) { ListLine(it) }
    }
}

@Composable
private fun ListLine(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
}
