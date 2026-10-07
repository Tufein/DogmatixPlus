package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.GameCover
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
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

    fun fetch(context: Context, files: List<DownloadableFileEntity>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.Default) { downloadService.startDownloads(files) }
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
        ToolsTitle(stringResource(R.string.nav_switch), icon = NavRoutes.Switch.icon)
        val all = ui.rows.flatMap { it.toFetch }
        if (all.isNotEmpty()) ToolsActions {
            ToolAction(stringResource(R.string.switch_fetch_all, all.size), icon = R.drawable.ic_download, tone = ActionTone.Accent) { viewModel.fetch(context, all) }
        }
        when {
            ui.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.rows.isEmpty() -> InfoCard(
                listOf(if (ui.gamesOnDisk == 0) stringResource(R.string.switch_none_on_disk) else stringResource(R.string.switch_all_current, ui.gamesOnDisk)),
                icon = if (ui.gamesOnDisk == 0) R.drawable.ic_joystick else R.drawable.ic_check_circle
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
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
                    val cover = s.base ?: s.newestUpdate?.first ?: s.missingDlc.firstOrNull()
                    ToolRow(
                        row.title, lines, onClick = { viewModel.fetch(context, row.toFetch) },
                        modifier = if (row == ui.rows.first()) Modifier.focusRequester(firstFocus) else Modifier,
                        badge = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (s.updateAvailable) Pill(stringResource(R.string.tools5_update), tone = PillTone.Accent, icon = R.drawable.ic_download)
                                if (s.missingDlc.isNotEmpty()) Pill(stringResource(R.string.switch_kind_dlc), tone = PillTone.Info)
                            }
                        },
                        leading = {
                            if (cover != null) GameCover(row.consoleId, cover.fileName, row.title, Modifier.size(width = 42.dp, height = 56.dp))
                            else ConsoleTile(row.consoleId)
                        }
                    ) {
                        ToolAction(stringResource(R.string.switch_fetch, row.toFetch.size), tone = ActionTone.Accent) { viewModel.fetch(context, row.toFetch) }
                    }
                }
            }
        }
    }
}
