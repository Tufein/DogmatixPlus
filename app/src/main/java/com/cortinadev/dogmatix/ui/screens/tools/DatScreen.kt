package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.entity.DatSetEntity
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.service.DatProgress
import com.cortinadev.dogmatix.data.service.DatReport
import com.cortinadev.dogmatix.data.service.DatService
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.data.state.ListImportRequest
import com.cortinadev.dogmatix.data.state.PendingListImport
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.MeterBar
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.PanelTone
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DatCheck
import com.cortinadev.dogmatix.util.DatStatus
import com.cortinadev.dogmatix.util.DiskEntry
import com.cortinadev.dogmatix.util.RedumpSystems
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DatViewModel @Inject constructor(
    private val dat: DatService,
    consoleRepository: ConsoleRepository,
    private val libraryIndex: LibraryIndexService,
    private val pendingImport: PendingListImport
) : ViewModel() {
    /** Hands the missing games of [consoleId] to *Import a list*, which looks them up in the sources. */
    fun findMissing(consoleId: String, missing: List<String>) = pendingImport.submit(ListImportRequest(missing, consoleId))

    val consoles: StateFlow<List<ConsoleEntity>> = consoleRepository.getAllConsoles()
        .map { list -> list.sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sets: StateFlow<Map<String, DatSetEntity>> = dat.sets.map { list -> list.associateBy { it.consoleId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val reports: StateFlow<Map<String, DatReport>> = dat.reports
    val progress: StateFlow<DatProgress?> = dat.progress

    private var job: Job? = null

    fun import(context: Context, consoleId: String, uri: Uri) {
        val app = context.applicationContext
        viewModelScope.launch {
            runCatching { dat.import(consoleId, uri) }
                .onSuccess { ToastUtil.showSuccess(app, app.getString(R.string.dat_imported, it)) }
                .onFailure { ToastUtil.showError(app, app.getString(R.string.dat_import_failed, it.message ?: "")) }
        }
    }

    /** The console whose DAT is being fetched from Redump (a 10+ MB download, then parsed), or null. */
    private val _fetching = MutableStateFlow<String?>(null)
    val fetching: StateFlow<String?> = _fetching

    fun fetchRedump(context: Context, consoleId: String) {
        if (_fetching.value != null) return
        val app = context.applicationContext
        _fetching.value = consoleId
        viewModelScope.launch {
            runCatching { dat.importFromRedump(consoleId) }
                .onSuccess { if (it != null) ToastUtil.showSuccess(app, app.getString(R.string.dat_imported, it)) }
                .onFailure { ToastUtil.showError(app, app.getString(R.string.dat_import_failed, it.message ?: "")) }
            _fetching.value = null
        }
    }

    fun verify(context: Context, consoleId: String) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = viewModelScope.launch {
            if (dat.verify(consoleId) == null) ToastUtil.showError(app, app.getString(R.string.dat_no_folder))
        }
    }

    fun cancel() { job?.cancel() }

    fun remove(consoleId: String) { viewModelScope.launch { dat.remove(consoleId) } }

    fun rename(context: Context, consoleId: String, entry: DiskEntry, check: DatCheck) {
        val app = context.applicationContext
        viewModelScope.launch {
            if (!dat.rename(consoleId, entry, check)) ToastUtil.showError(app, app.getString(R.string.dat_rename_failed, entry.name))
            libraryIndex.requestRefresh()
        }
    }

    fun renameAll(context: Context, consoleId: String) {
        val report = reports.value[consoleId] ?: return
        val app = context.applicationContext
        viewModelScope.launch {
            val todo = report.checks.filter { it.second.status == DatStatus.MISNAMED }
            val ok = todo.count { (entry, check) -> dat.rename(consoleId, entry, check) }
            ToastUtil.showInfo(app, app.getString(R.string.dat_renamed, ok, todo.size))
            libraryIndex.requestRefresh()
        }
    }
}

/**
 * DAT check: per console, import a DAT (No-Intro, Redump, …), check the console's folder against
 * it, and give files the DAT's names.
 */
@Composable
fun DatScreen(navController: NavController, viewModel: DatViewModel = hiltViewModel()) {
    val consoles by viewModel.consoles.collectAsState()
    val sets by viewModel.sets.collectAsState()
    val reports by viewModel.reports.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val fetching by viewModel.fetching.collectAsState()
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = selected
        if (uri != null && id != null) viewModel.import(context, id, uri)
    }
    BackHandler(enabled = selected != null) { viewModel.cancel(); selected = null }
    val firstFocus = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(selected, consoles.size) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        val consoleId = selected
        if (consoleId == null) {
            ToolsTitle(stringResource(R.string.nav_dat), icon = NavRoutes.Dat.icon)
            InfoCard(listOf(stringResource(R.string.dat_intro)), icon = R.drawable.ic_verified)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(consoles, key = { it.id }) { console ->
                    val set = sets[console.id]
                    val report = reports[console.id]
                    val lines = buildList {
                        add(set?.let { stringResource(R.string.dat_set_line, it.name, it.version, it.games) } ?: stringResource(R.string.dat_none))
                        report?.let { add(summary(it)) }
                    }
                    ToolRow(ConsoleFormatter.getConsoleDisplayName(console.id), lines, onClick = { selected = console.id },
                        modifier = if (console == consoles.first()) Modifier.focusRequester(firstFocus) else Modifier,
                        leading = { ConsoleTile(console.id) },
                        chevron = true)
                }
            }
            return@Column
        }

        val set = sets[consoleId]
        val report = reports[consoleId]
        val running = progress?.takeIf { it.consoleId == consoleId }
        ToolsTitle(ConsoleFormatter.getConsoleDisplayName(consoleId), icon = NavRoutes.Dat.icon)
        ToolsActions {
            ToolAction(
                stringResource(if (set == null) R.string.dat_import else R.string.dat_replace),
                icon = R.drawable.ic_import,
                tone = if (set == null) ActionTone.Accent else ActionTone.Neutral
            ) {
                picker.launch(arrayOf("application/xml", "text/xml", "application/zip", "application/octet-stream", "text/plain", "*/*"))
            }
            if (RedumpSystems.systemFor(consoleId) != null && fetching == null) {
                ToolAction(stringResource(R.string.dat_redump), icon = R.drawable.ic_cloud_download) { viewModel.fetchRedump(context, consoleId) }
            }
            if (set != null) {
                if (running == null) ToolAction(stringResource(R.string.dat_check), icon = R.drawable.ic_verified, tone = ActionTone.Accent) { viewModel.verify(context, consoleId) }
                else ToolAction(stringResource(R.string.dialog_cancel), icon = R.drawable.ic_close) { viewModel.cancel() }
                ToolAction(stringResource(R.string.dat_remove), icon = R.drawable.ic_trash, tone = ActionTone.Danger) { viewModel.remove(consoleId) }
            }
        }
        Text(
            set?.let { stringResource(R.string.dat_set_line, it.name, it.version, it.games) } ?: stringResource(R.string.dat_none_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )
        if (fetching == consoleId) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.dat_redump_fetching), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        running?.let {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MeterBar(if (it.total == 0) 0f else it.done.toFloat() / it.total)
                Text(stringResource(R.string.dat_checking, it.done + 1, it.total, it.current), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (report != null && running == null) {
            val misnamed = report.checks.filter { it.second.status == DatStatus.MISNAMED }
            val unknown = report.checks.filter { it.second.status == DatStatus.UNKNOWN }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { ReportCard(report, attention = misnamed.isNotEmpty()) }
                if (misnamed.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { SectionHeader(stringResource(R.string.dat_section_misnamed), stringResource(R.string.dat_section_misnamed_hint), icon = R.drawable.ic_edit) }
                            ToolAction(stringResource(R.string.dat_rename_all, misnamed.size), tone = ActionTone.Accent) { viewModel.renameAll(context, consoleId) }
                        }
                    }
                    items(misnamed, key = { "m" + it.first.uri }) { (entry, check) ->
                        ToolRow(entry.name, listOf("→ " + (check.canonicalName ?: "")), onClick = { viewModel.rename(context, consoleId, entry, check) }, icon = R.drawable.ic_edit) {
                            ToolAction(stringResource(R.string.dat_rename)) { viewModel.rename(context, consoleId, entry, check) }
                        }
                    }
                }
                if (unknown.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.dat_section_unknown), stringResource(R.string.dat_section_unknown_hint), icon = R.drawable.ic_warning) }
                    items(unknown, key = { "u" + it.first.uri }) { (entry, _) -> ToolRow(entry.name, emptyList(), onClick = {}, icon = R.drawable.ic_description) }
                }
                if (report.missing.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.dat_section_missing), stringResource(R.string.dat_section_missing_hint, report.missing.size), icon = R.drawable.ic_search) }
                    item {
                        val find = { viewModel.findMissing(consoleId, report.missing); navController.navigate(NavRoutes.ImportList.route) }
                        ToolRow(stringResource(R.string.dat_find_missing), listOf(stringResource(R.string.dat_find_missing_hint)), find, icon = R.drawable.ic_search) {
                            ToolAction(stringResource(R.string.dat_find_missing_action), tone = ActionTone.Accent, onClick = find)
                        }
                    }
                    items(report.missing.take(200), key = { "x$it" }) { name ->
                        Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}

/** The check's result: a pill per outcome and how much of the DAT this folder holds. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportCard(report: DatReport, attention: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val have = (report.gameCount - report.missing.size).coerceAtLeast(0)
    Panel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        tone = if (attention) PanelTone.Accent else PanelTone.Normal,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(stringResource(R.string.tools5_dat_good, report.count(DatStatus.VERIFIED)), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
            Pill(stringResource(R.string.tools5_dat_renamed, report.count(DatStatus.MISNAMED)), tone = PillTone.Warning, icon = R.drawable.ic_edit)
            Pill(stringResource(R.string.tools5_dat_unknown, report.count(DatStatus.UNKNOWN)), tone = PillTone.Neutral)
            Pill(stringResource(R.string.tools5_dat_skipped, report.count(DatStatus.SKIPPED)), tone = PillTone.Neutral)
        }
        Spacer(Modifier.height(10.dp))
        MeterBar(if (report.gameCount == 0) 0f else have.toFloat() / report.gameCount, height = 6.dp, color = scheme.tertiary)
        Text(
            stringResource(R.string.dat_missing_line, report.missing.size, report.gameCount),
            style = MaterialTheme.typography.bodySmall,
            color = if (attention) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun summary(report: DatReport): String = stringResource(
    R.string.dat_summary, report.count(DatStatus.VERIFIED), report.count(DatStatus.MISNAMED), report.count(DatStatus.UNKNOWN), report.count(DatStatus.SKIPPED)
)
