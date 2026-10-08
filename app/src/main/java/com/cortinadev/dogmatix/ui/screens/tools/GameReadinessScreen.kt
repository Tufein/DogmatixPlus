package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.service.*
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import androidx.compose.ui.focus.focusRequester
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class ReadyRow(val label: Int, val message: Int, val ok: Boolean, val fix: String?)
data class ReadyUi(val loading: Boolean = true, val rows: List<ReadyRow> = emptyList(),
    val games: List<GameLaunch> = emptyList(), val selected: String? = null, val result: Int? = null)

@HiltViewModel
class GameReadinessViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val files: DownloadableFileDao,
    private val library: LibraryIndexService,
    private val launcher: GameLaunchService,
    private val bios: BiosService,
    private val profiles: ProfileService
) : ViewModel() {
    private val _ui = MutableStateFlow(ReadyUi())
    val ui = _ui.asStateFlow()
    val activeProfile = profiles.activeId
    private var job: Job? = null
    fun check(console: String, name: String) {
        job?.cancel()
        job = viewModelScope.launch {
            _ui.value = ReadyUi()
            try {
                val next = withContext(Dispatchers.IO) {
                    val file = files.filesOf(console).firstOrNull { it.fileName == name }
                    if (file == null || !profiles.current().allows(console, files.tagsOf(file.id))) {
                        return@withContext ReadyUi(false, listOf(ReadyRow(R.string.ready25_files, R.string.ready25_unavailable, false, null)))
                    }
                    val plan = library.launchPlan(file)
                    val readable = plan.isNotEmpty() && plan.all { part ->
                        context.contentResolver.openInputStream(Uri.parse(part.uri))?.use { it.read() != -1 } == true
                    }
                    val sheetsOk = plan.filter { SheetParser.isSheet(it.name) }.all { part ->
                        val text = context.contentResolver.openInputStream(Uri.parse(part.uri))?.use {
                            BoundedStreams.read(it, SetChecker.MAX_SHEET_BYTES.toInt()).toString(Charsets.UTF_8)
                        } ?: return@all false
                        GameReadiness.referencesPresent(part.name, text, plan.filter { it.parent == part.parent }.map { it.name })
                    }
                    val games = launcher.choices(file)
                    val biosSystems = BiosCatalog.systemsFor(listOf(console)).map { it.name }.toSet()
                    val report = if (biosSystems.isEmpty()) null else bios.check(true)
                    val biosOk = report == null || report.results.filter { it.system.name in biosSystems }.all { it.allGood }
                    val handlers = games.any { it.handlers.isNotEmpty() }
                    ReadyUi(false, listOf(
                        ReadyRow(R.string.ready25_files, if (readable) R.string.ready25_ok else R.string.ready25_missing, readable, NavRoutes.Files.route),
                        ReadyRow(R.string.ready25_discs, if (sheetsOk) R.string.ready25_ok else R.string.ready25_disc_problem, sheetsOk, NavRoutes.Sets.route),
                        ReadyRow(R.string.ready25_bios, if (biosOk) R.string.ready25_ok else R.string.ready25_bios_problem, biosOk, NavRoutes.Bios.route),
                        ReadyRow(R.string.ready25_emulator, if (handlers) R.string.ready25_choose else R.string.ready25_no_emulator, handlers, NavRoutes.Settings.route)
                    ), games, launcher.preferred(console))
                }
                _ui.value = next
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _ui.value = ReadyUi(false, listOf(ReadyRow(R.string.ready25_files, R.string.ready25_missing, false, NavRoutes.Files.route))) }
        }
    }
    fun select(console: String, key: String) {
        if (_ui.value.games.none { game -> game.handlers.any { it.key == key } }) return
        launcher.setPreferred(console, key)
        _ui.update { it.copy(selected = key, result = R.string.ready25_saved) }
    }
    fun test(context: Context, console: String, game: GameLaunch, handler: GameHandler) {
        if (_ui.value.loading || _ui.value.rows.any { !it.ok && it.label != R.string.ready25_bios }) return
        val result = runCatching { launcher.launch(context, console, game, handler, false) }.getOrDefault(LaunchOutcome.FAILED)
        _ui.update { it.copy(result = when(result) {
            LaunchOutcome.STARTED -> R.string.ready25_opened
            LaunchOutcome.NEEDS_EXTRACT -> R.string.ready25_extract
            LaunchOutcome.FAILED -> R.string.ready25_launch_failed
        }) }
    }
}

object ReadinessRoute {
    const val route = "game_ready/{consoleId}/{fileName}"
    fun of(console: String, name: String) = "game_ready/" + Uri.encode(console) + "/" + Uri.encode(name)
}

@Composable
fun GameReadinessScreen(console: String, name: String, onNavigate: (String) -> Unit, vm: GameReadinessViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val first = rememberInitialFocus()
    val active by vm.activeProfile.collectAsState()
    LaunchedEffect(console, name, active) { vm.check(console, name) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        ToolsTitle(stringResource(R.string.ready25_title), icon = R.drawable.ic_controller, subtitle = name)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            item { ActionPill(stringResource(R.string.tools_refresh), { vm.check(console, name) }, modifier = Modifier.focusRequester(first), icon = R.drawable.ic_retry) }
            if (ui.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            ui.rows.forEach { row -> item {
                ToolRow(stringResource(row.label), listOf(stringResource(row.message)),
                    onClick = { row.fix?.let(onNavigate) }, icon = if (row.ok) R.drawable.ic_check_circle else R.drawable.ic_warning)
            } }
            if (!ui.loading) item {
                Text(stringResource(R.string.ready25_wizard), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.ready25_core_hint), style = MaterialTheme.typography.bodySmall)
            }
            ui.games.forEach { game -> item {
                Text(game.name, style = MaterialTheme.typography.titleSmall)
                game.handlers.forEach { handler ->
                    ToolRow(handler.label, emptyList(), onClick = { vm.select(console, handler.key) },
                        icon = if (ui.selected == handler.key) R.drawable.ic_check else R.drawable.ic_controller) {
                        ActionPill(stringResource(R.string.ready25_test), { vm.test(context, console, game, handler) }, icon = R.drawable.ic_controller, enabled = !ui.loading && ui.rows.filter { it.label != R.string.ready25_bios }.all { it.ok })
                    }
                }
            } }
            ui.result?.let { message -> item { Text(stringResource(message)) } }
        }
    }
}
