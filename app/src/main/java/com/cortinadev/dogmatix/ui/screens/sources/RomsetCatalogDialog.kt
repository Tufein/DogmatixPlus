package com.cortinadev.dogmatix.ui.screens.sources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.*
import com.cortinadev.dogmatix.util.RomsetCatalog

@Composable
fun RomsetCatalogDialog(onDismiss: () -> Unit, vm: RomsetCatalogViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val known by vm.known.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(known) {
        selected = selected.filterTo(mutableSetOf()) { id ->
            state.catalog?.entries?.firstOrNull { it.id == id }?.let { it.id to it.url.trimEnd('/') !in known } == true
        }
    }
    val filtered = state.catalog?.entries.orEmpty().filter { query.isBlank() ||
        "${it.consoleName} ${it.shortName} ${it.manufacturerName}".contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        modifier = Modifier.closeOnGamepadB { if (!state.busy) onDismiss() },
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(stringResource(R.string.romset28_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.romset28_hint))
                        state.catalog?.let { Text(stringResource(R.string.romset28_checked, it.publisher, it.checkedAt), style = MaterialTheme.typography.bodySmall) }
                        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.romset28_search)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
                        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.failed) {
                            Text(stringResource(R.string.romset28_failed), color = MaterialTheme.colorScheme.error)
                            if (state.catalog == null) ActionPill(stringResource(R.string.download_retry), vm::reload)
                        }
                        state.added?.let { Text(stringResource(R.string.romset28_added_count, it)) }
                        if (!state.loading && filtered.isEmpty()) Text(stringResource(R.string.romset28_no_match))
                    }
                }
                items(filtered, key = { it.id }) { entry ->
                    val added = entry.id to entry.url.trimEnd('/') in known
                    RomsetChoice(entry, checked = entry.id in selected, alreadyAdded = added, enabled = !state.busy && !added) {
                        selected = if (entry.id in selected) selected - entry.id else selected + entry.id
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.romset28_add, selected.size), { vm.add(selected) },
                enabled = selected.isNotEmpty() && !state.busy && !state.loading)
        },
        dismissButton = { DialogButton(stringResource(R.string.dialog_close), onDismiss, enabled = !state.busy, initialFocus = rememberInitialFocus()) }
    )
}

@Composable
internal fun RomsetChoice(entry: RomsetCatalog.Entry, checked: Boolean, alreadyAdded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked || alreadyAdded, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
        .padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked || alreadyAdded, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(entry.consoleName, style = MaterialTheme.typography.titleSmall)
            Text(if (alreadyAdded) stringResource(R.string.romset28_already_added)
                else stringResource(R.string.romset28_files, entry.fileCount), style = MaterialTheme.typography.bodySmall)
        }
    }
}
