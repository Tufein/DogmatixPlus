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
import com.cortinadev.dogmatix.ui.screens.sources.components.ChoiceChip
import com.cortinadev.dogmatix.util.RomsetCatalog

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RomsetCatalogDialog(onDismiss: () -> Unit, vm: RomsetCatalogViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val known by vm.known.collectAsState()
    val consoles by vm.consoles.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val catalog = state.catalog
    LaunchedEffect(known) {
        selected = selected.filterTo(mutableSetOf()) { key ->
            catalog?.entries?.firstOrNull { it.key == key }?.let { e -> e.urls.any { it.trimEnd('/') !in known } } == true
        }
    }
    // A provider that is not in the catalogue (any more) shows everything.
    val shownProvider = provider?.takeIf { id -> catalog?.providers?.any { it.id == id } == true }
    val filtered = catalog?.let { RomsetCatalog.filter(it, shownProvider, query) }.orEmpty()
    val groups = catalog?.let { RomsetCatalog.groups(it, filtered) }.orEmpty()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB { if (!state.busy) onDismiss() },
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(stringResource(R.string.romset28_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "head") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.src31_hint))
                        if (catalog != null && catalog.providers.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                ChoiceChip(stringResource(R.string.src31_all_providers), shownProvider == null, { provider = null })
                                catalog.providers.forEach { p ->
                                    ChoiceChip(p.name, shownProvider == p.id, { provider = p.id })
                                }
                            }
                        }
                        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.src31_search)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
                        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.failed) {
                            Text(stringResource(R.string.romset28_failed), color = MaterialTheme.colorScheme.error)
                            if (catalog == null) ActionPill(stringResource(R.string.download_retry), vm::reload)
                        }
                        state.added?.let { Text(stringResource(R.string.romset28_added_count, it)) }
                        if (!state.loading && filtered.isEmpty()) Text(stringResource(R.string.romset28_no_match))
                    }
                }
                groups.forEach { (p, entries) ->
                    item(key = "provider-" + p.id) { ProviderHeader(p) }
                    items(entries, key = { it.key }) { entry ->
                        val added = entry.urls.all { it.trimEnd('/') in known }
                        RomsetChoice(entry, p.name, RomsetCatalog.mapConsole(entry, consoles), checked = entry.key in selected,
                            alreadyAdded = added, enabled = !state.busy && !added) {
                            selected = if (entry.key in selected) selected - entry.key else selected + entry.key
                        }
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

/** Who publishes the entries below it, on what terms, where the list comes from, and when it was checked. */
@Composable
private fun ProviderHeader(provider: RomsetCatalog.Provider) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(provider.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(licenceText(provider.licence)), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.src31_provider_site, provider.homepage), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.src31_provider_list, provider.provenance), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.src31_provider_checked, provider.checkedAt), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun licenceText(licence: RomsetCatalog.Licence): Int = when (licence) {
    RomsetCatalog.Licence.PUBLISHED -> R.string.src31_licence_published
    RomsetCatalog.Licence.PUBLIC_DOMAIN -> R.string.src31_licence_public_domain
    RomsetCatalog.Licence.FREE_LICENCE -> R.string.src31_licence_free
}

@Composable
internal fun RomsetChoice(
    entry: RomsetCatalog.Entry,
    providerName: String,
    mapping: RomsetCatalog.Mapping,
    checked: Boolean,
    alreadyAdded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(Modifier.fillMaxWidth().toggleable(checked || alreadyAdded, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
        .padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked || alreadyAdded, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(entry.consoleName, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.src31_entry_files, providerName, entry.fileCount), style = MaterialTheme.typography.bodySmall)
            Text(
                when {
                    alreadyAdded -> stringResource(R.string.romset28_already_added)
                    mapping.existing -> stringResource(R.string.src31_maps_existing, mapping.consoleName)
                    else -> stringResource(R.string.src31_maps_new, mapping.consoleName)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
