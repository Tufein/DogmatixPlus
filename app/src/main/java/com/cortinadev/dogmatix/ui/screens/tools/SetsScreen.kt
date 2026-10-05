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
import androidx.compose.runtime.remember
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
import com.cortinadev.dogmatix.data.service.LibraryToolsService
import com.cortinadev.dogmatix.data.service.SetsReport
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.util.PlaylistPlan
import com.cortinadev.dogmatix.util.SetProblem
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetsUiState(val scanning: Boolean = true, val folderSet: Boolean = true, val report: SetsReport? = null)

@HiltViewModel
class SetsViewModel @Inject constructor(
    private val tools: LibraryToolsService,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private val _ui = MutableStateFlow(SetsUiState())
    val ui: StateFlow<SetsUiState> = _ui.asStateFlow()
    private var job: Job? = null

    init { rescan() }

    fun rescan() {
        job?.cancel()
        job = viewModelScope.launch {
            _ui.update { it.copy(scanning = true) }
            val folderSet = settingsRepository.downloadDirectory.first().isNotBlank() || settingsRepository.consoleDownloadDirectories.first().isNotEmpty()
            val report = try { tools.checkSets() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            _ui.value = SetsUiState(false, folderSet, report)
        }
    }

    fun createPlaylist(context: Context, plan: PlaylistPlan) {
        val app = context.applicationContext
        viewModelScope.launch {
            val ok = tools.createPlaylist(plan)
            if (ok) {
                ToastUtil.showSuccess(app, app.getString(R.string.sets_playlist_created, plan.fileName))
                _ui.update { s -> s.copy(report = s.report?.copy(playlists = s.report.playlists.filterNot { it.id == plan.id })) }
            } else ToastUtil.showError(app, app.getString(R.string.sets_playlist_failed, plan.fileName))
        }
    }

    fun createAll(context: Context) {
        val app = context.applicationContext
        val plans = _ui.value.report?.playlists.orEmpty()
        viewModelScope.launch {
            var done = 0
            plans.forEach { if (tools.createPlaylist(it)) done++ }
            ToastUtil.showSuccess(app, app.getString(R.string.sets_playlists_created, done))
            rescan()
        }
    }
}

/**
 * Disc sets that cannot run (a `.cue` whose track is gone, a playlist naming a deleted disc) and
 * multi-disc games that have no `.m3u` yet; the playlists can be created here.
 */
@Composable
fun SetsScreen(viewModel: SetsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    val report = ui.report
    val summary = when {
        ui.scanning -> stringResource(R.string.tools_scanning)
        !ui.folderSet -> stringResource(R.string.tools_no_folder)
        report == null -> stringResource(R.string.sets_failed)
        report.problems.isEmpty() -> stringResource(R.string.sets_none, report.filesChecked)
        else -> pluralStringResource(R.plurals.sets_problems, report.problems.size, report.problems.size, report.filesChecked)
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_sets), icon = NavRoutes.Sets.icon)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "summary") {
                val ok = !ui.scanning && ui.folderSet && report != null && report.problems.isEmpty()
                ToolRow(
                    summary, emptyList(), viewModel::rescan, Modifier.focusRequester(firstFocus),
                    icon = if (ok) R.drawable.ic_check_circle else R.drawable.ic_stacks
                ) {
                    ToolAction(stringResource(R.string.tools_refresh), icon = R.drawable.ic_retry, onClick = viewModel::rescan)
                }
            }
            if (!ui.scanning && report != null) {
                items(report.problems, key = { "p:" + it.sheet.fileId }) { problem -> ProblemRow(problem) }
                if (report.playlists.isNotEmpty()) {
                    item(key = "plHeader") { SectionHeader(stringResource(R.string.sets_playlists_header, report.playlists.size), stringResource(R.string.sets_playlists_hint), icon = R.drawable.ic_playlist_add) }
                    item(key = "plAll") {
                        ToolRow(
                            stringResource(R.string.sets_playlist_create_all, report.playlists.size), emptyList(),
                            { viewModel.createAll(context) },
                            icon = R.drawable.ic_sparkle
                        ) { ToolAction(stringResource(R.string.sets_playlist_create), tone = ActionTone.Accent) { viewModel.createAll(context) } }
                    }
                    items(report.playlists, key = { "l:" + it.id }) { plan ->
                        ToolRow(
                            plan.fileName,
                            listOf(plan.folder, pluralStringResource(R.plurals.sets_discs, plan.discs.size, plan.discs.size)),
                            { viewModel.createPlaylist(context, plan) },
                            icon = R.drawable.ic_playlist_add
                        ) { ToolAction(stringResource(R.string.sets_playlist_create)) { viewModel.createPlaylist(context, plan) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProblemRow(problem: SetProblem) {
    val detail = when (problem.kind) {
        SetProblem.Kind.MISSING_TRACKS -> stringResource(R.string.sets_missing_tracks, problem.missing.joinToString(", "))
        SetProblem.Kind.MISSING_DISCS -> stringResource(R.string.sets_missing_discs, problem.missing.joinToString(", "))
        SetProblem.Kind.EMPTY_SHEET -> stringResource(R.string.sets_empty_sheet)
    }
    ToolRow(
        problem.sheet.name,
        listOf(problem.sheet.folder, detail),
        onClick = {},
        badge = { Badge(stringResource(R.string.sets_badge_broken), warning = true, icon = R.drawable.ic_warning) },
        icon = R.drawable.ic_stacks
    )
}
