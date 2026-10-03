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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.CollectionWithCount
import com.cortinadev.dogmatix.data.repository.CollectionsRepository
import com.cortinadev.dogmatix.data.state.LibraryFilterRequest
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.cortinadev.dogmatix.ui.screens.sources.components.ConfirmDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val repository: CollectionsRepository,
    private val pendingFilters: PendingLibraryFilters,
    private val appSettings: com.cortinadev.dogmatix.data.local.AppSettings,
    private val shortcuts: com.cortinadev.dogmatix.data.service.FrontendShortcutService,
    private val rommCollections: com.cortinadev.dogmatix.data.service.RommCollectionsService,
    settings: com.cortinadev.dogmatix.data.repository.SettingsRepository
) : ViewModel() {
    val views: StateFlow<List<com.cortinadev.dogmatix.util.LibraryView>> = appSettings.libraryViews
        .map { com.cortinadev.dogmatix.util.LibraryViews.fromJson(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** RomM is set up, so collections can be synced with it. */
    val rommReady: StateFlow<Boolean> = settings.rommUrl.map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Opens a saved view in the library (through the same hand-off as a deep link). */
    fun openView(view: com.cortinadev.dogmatix.util.LibraryView) {
        com.cortinadev.dogmatix.util.DeepLinkParser.parse(view.deepLink())?.let(pendingFilters::submit)
    }

    fun deleteView(id: String) {
        viewModelScope.launch {
            appSettings.setLibraryViews(com.cortinadev.dogmatix.util.LibraryViews.toJson(views.value.filterNot { it.id == id }))
        }
    }

    fun shortcut(context: android.content.Context, view: com.cortinadev.dogmatix.util.LibraryView) {
        val app = context.applicationContext
        viewModelScope.launch {
            if (view.consoles.isEmpty()) { com.cortinadev.dogmatix.util.ToastUtil.showError(app, app.getString(R.string.view_shortcut_needs_console)); return@launch }
            val n = runCatching { shortcuts.deployView(view) }.getOrDefault(0)
            if (n > 0) com.cortinadev.dogmatix.util.ToastUtil.showSuccess(app, app.getString(R.string.view_shortcut_done, n))
            else com.cortinadev.dogmatix.util.ToastUtil.showError(app, app.getString(R.string.view_shortcut_failed))
        }
    }

    fun syncRomm(context: android.content.Context, push: Boolean) {
        val app = context.applicationContext
        viewModelScope.launch {
            runCatching { if (push) rommCollections.push() else rommCollections.pull() }
                .onSuccess { com.cortinadev.dogmatix.util.ToastUtil.showSuccess(app, app.getString(if (push) R.string.romm_collections_pushed else R.string.romm_collections_pulled, it.collections, it.games, it.skipped)) }
                .onFailure { com.cortinadev.dogmatix.util.ToastUtil.showError(app, app.getString(R.string.romm_collections_failed, it.message ?: "")) }
        }
    }
    val collections: StateFlow<List<CollectionWithCount>?> = repository.collections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun create(name: String) { viewModelScope.launch { repository.create(name) } }
    fun rename(id: Long, name: String) { viewModelScope.launch { repository.rename(id, name) } }
    fun delete(id: Long) { viewModelScope.launch { repository.delete(id) } }

    /** Shows the collection in the library (the shell switches to the Library tab). */
    fun open(id: Long) = pendingFilters.submit(LibraryFilterRequest(collectionId = id))
}

/** Own collections: open one in the library, rename or delete it, or start a new one. */
@Composable
fun CollectionsScreen(navController: NavController, viewModel: CollectionsViewModel = hiltViewModel()) {
    val collections by viewModel.collections.collectAsState()
    val views by viewModel.views.collectAsState()
    val rommReady by viewModel.rommReady.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var naming by remember { mutableStateOf<CollectionWithCount?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CollectionWithCount?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(collections?.size) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }

    if (creating || naming != null) {
        NameDialog(
            title = stringResource(if (creating) R.string.collections_new else R.string.collections_rename),
            initial = naming?.name.orEmpty(),
            onSave = { name -> naming?.let { viewModel.rename(it.id, name) } ?: viewModel.create(name); creating = false; naming = null },
            onDismiss = { creating = false; naming = null }
        )
    }
    deleting?.let { c ->
        ConfirmDialog(
            title = stringResource(R.string.collections_delete_title, c.name),
            message = stringResource(R.string.collections_delete_message),
            confirmText = stringResource(R.string.dialog_delete),
            onConfirm = { viewModel.delete(c.id); deleting = null },
            onDismiss = { deleting = null }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) { ToolsTitle(stringResource(R.string.nav_collections)) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rommReady) {
                    PillButton(stringResource(R.string.romm_collections_pull)) { viewModel.syncRomm(context, push = false) }
                    PillButton(stringResource(R.string.romm_collections_push)) { viewModel.syncRomm(context, push = true) }
                }
                PillButton(stringResource(R.string.collections_new)) { creating = true }
            }
        }
        val list = collections
        when {
            list == null -> Unit
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (list.isEmpty()) item { InfoCard(listOf(stringResource(R.string.collections_empty)), Modifier.padding(16.dp)) }
                items(list, key = { it.id }) { c ->
                    ToolRow(
                        title = c.name,
                        lines = listOf(pluralStringResource(R.plurals.collections_games, c.count, c.count)),
                        onClick = { viewModel.open(c.id) },
                        modifier = if (c == list.first()) Modifier.focusRequester(firstFocus) else Modifier
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PillButton(stringResource(R.string.collections_rename)) { naming = c }
                            PillButton(stringResource(R.string.dialog_delete)) { deleting = c }
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.views_title), stringResource(R.string.views_hint)) }
                if (views.isEmpty()) item { InfoCard(listOf(stringResource(R.string.views_empty)), Modifier.padding(16.dp)) }
                items(views, key = { "v" + it.id }) { v ->
                    ToolRow(v.name, listOf(v.consoles.joinToString(", ") { com.cortinadev.dogmatix.util.ConsoleFormatter.getConsoleShortName(it) }.ifEmpty { "—" }), onClick = { viewModel.openView(v) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PillButton(stringResource(R.string.view_shortcut)) { viewModel.shortcut(context, v) }
                            PillButton(stringResource(R.string.dialog_delete)) { viewModel.deleteView(v.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { DialogButton(stringResource(R.string.dialog_save), onClick = { onSave(name) }, enabled = name.isNotBlank()) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}
