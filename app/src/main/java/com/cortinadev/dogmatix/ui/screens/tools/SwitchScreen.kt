package com.cortinadev.dogmatix.ui.screens.tools

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.SwitchTitles
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One game on disk with what the sources still offer for it. */
data class SwitchRow(val consoleId: String, val title: String, val status: SwitchTitles.GameStatus<DownloadableFileEntity>) {
    /** The newest update when it is newer than the one on disk, plus the DLC not on disk. */
    val toFetch: List<DownloadableFileEntity>
        get() = listOfNotNull(status.newestUpdate?.first?.takeIf { status.updateAvailable }) + status.missingDlc
}

data class SwitchUiState(val loading: Boolean = true, val rows: List<SwitchRow> = emptyList(), val gamesOnDisk: Int = 0)

@HiltViewModel
class SwitchViewModel @Inject constructor(
    private val fileDao: DownloadableFileDao,
    private val libraryIndex: LibraryIndexService,
    private val downloadService: DownloadService
) : ViewModel() {
    private val _ui = MutableStateFlow(SwitchUiState())
    val ui: StateFlow<SwitchUiState> = _ui.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            val keys = libraryIndex.ownedKeys.value
            val rows = withContext(Dispatchers.IO) {
                fileDao.switchConsoles().flatMap { consoleId ->
                    val scopes = LibraryKeys.scopesFor(consoleId)
                    val owned = keys.mapNotNull { key -> key.indexOf('|').takeIf { it > 0 && key.substring(0, it) in scopes }?.let { key.substring(it + 1) } }
                    SwitchTitles.analyse(fileDao.filesOf(consoleId), { it.fileName }, owned).map { status ->
                        val name = (status.base ?: status.newestUpdate?.first ?: status.missingDlc.firstOrNull())?.name ?: status.baseId
                        SwitchRow(consoleId, stripExtension(name), status)
                    }
                }
            }
            _ui.value = SwitchUiState(false, rows.filter { it.status.hasWork }.sortedBy { it.title.lowercase() }, rows.size)
        }
    }

    fun fetch(context: android.content.Context, files: List<DownloadableFileEntity>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            downloadService.startDownloads(files)
            ToastUtil.showInfo(context.applicationContext, context.getString(R.string.bulk_queued, files.size.toString()))
        }
    }
}

/** Switch games on disk whose newer update or DLC the sources list: fetch one game's or all. */
@Composable
fun SwitchScreen(viewModel: SwitchViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val firstFocus = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(ui.loading) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) { ToolsTitle(stringResource(R.string.nav_switch)) }
            val all = ui.rows.flatMap { it.toFetch }
            if (all.isNotEmpty()) PillButton(stringResource(R.string.switch_fetch_all, all.size)) { viewModel.fetch(context, all) }
        }
        when {
            ui.loading -> Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator() }
            ui.rows.isEmpty() -> InfoCard(
                listOf(if (ui.gamesOnDisk == 0) stringResource(R.string.switch_none_on_disk) else stringResource(R.string.switch_all_current, ui.gamesOnDisk)),
                Modifier.padding(16.dp)
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(ui.rows, key = { it.consoleId + it.status.baseId }) { row ->
                    val s = row.status
                    val lines = buildList {
                        if (s.updateAvailable) add(
                            if (s.ownedUpdate == null) stringResource(R.string.switch_update_none_owned, s.newestUpdate!!.second / 65_536)
                            else stringResource(R.string.switch_update_newer, s.newestUpdate!!.second / 65_536, s.ownedUpdate / 65_536)
                        )
                        if (s.missingDlc.isNotEmpty()) add(pluralStringResource(R.plurals.switch_dlc_missing, s.missingDlc.size, s.missingDlc.size))
                        add(ConsoleFormatter.getConsoleShortName(row.consoleId) + " · " + s.baseId)
                    }
                    ToolRow(row.title, lines, onClick = { viewModel.fetch(context, row.toFetch) }, modifier = if (row == ui.rows.first()) Modifier.focusRequester(firstFocus) else Modifier) {
                        PillButton(stringResource(R.string.switch_fetch, row.toFetch.size)) { viewModel.fetch(context, row.toFetch) }
                    }
                }
            }
        }
    }
}
