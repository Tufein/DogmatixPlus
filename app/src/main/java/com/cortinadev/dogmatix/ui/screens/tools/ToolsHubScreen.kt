package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.LibraryToolsService
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.cortinadev.dogmatix.util.CollectionExport
import com.cortinadev.dogmatix.util.ExportLabels
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class ToolsHubViewModel @Inject constructor(private val tools: LibraryToolsService) : ViewModel() {

    /** Writes the whole collection to [uri] as a CSV file or a web page. */
    fun export(context: Context, uri: String, html: Boolean) {
        val app = context.applicationContext
        val labels = ExportLabels(
            title = context.getString(R.string.export_title), console = context.getString(R.string.export_console),
            game = context.getString(R.string.export_game), files = context.getString(R.string.export_files),
            size = context.getString(R.string.export_size), folder = context.getString(R.string.export_folder),
            total = context.getString(R.string.export_total), generated = context.getString(R.string.export_generated)
        )
        val unknown = { name: String -> context.getString(R.string.tools_unknown_folder, name) }
        val root = context.getString(R.string.tools_root_folder)
        ToastUtil.showInfo(app, context.getString(R.string.export_working))
        viewModelScope.launch {
            val result = runCatching {
                val games = tools.exportGames(unknown, root)
                val text = if (html) CollectionExport.html(games, labels, LocalDate.now().toString()) else CollectionExport.csv(games, labels)
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri.toUri(), "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("cannot write")
                }
                games.size
            }
            result.onSuccess { ToastUtil.showSuccess(app, app.getString(R.string.export_done, it)) }
                .onFailure { ToastUtil.showError(app, app.getString(R.string.export_failed)) }
        }
    }
}

/** The library tools in one place: overview, duplicates, set check, storage, wishlist and the export. */
@Composable
fun ToolsHubScreen(navController: NavController, viewModel: ToolsHubViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.export(context, it.toString(), html = false) }
    }
    val htmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        uri?.let { viewModel.export(context, it.toString(), html = true) }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    fun go(route: NavRoutes) = navController.navigate(route.route)
    val chevron: @Composable () -> Unit = { Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.settings_tools))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item { ToolRow(stringResource(R.string.settings_overview), listOf(stringResource(R.string.settings_overview_hint)), { go(NavRoutes.Overview) }, Modifier.focusRequester(firstFocus), trailing = chevron) }
            item { ToolRow(stringResource(R.string.settings_duplicates), listOf(stringResource(R.string.settings_duplicates_hint)), { go(NavRoutes.Duplicates) }, trailing = chevron) }
            item { ToolRow(stringResource(R.string.nav_sets), listOf(stringResource(R.string.tools_sets_hint)), { go(NavRoutes.Sets) }, trailing = chevron) }
            item { ToolRow(stringResource(R.string.nav_storage), listOf(stringResource(R.string.tools_storage_hint)), { go(NavRoutes.Storage) }, trailing = chevron) }
            item { ToolRow(stringResource(R.string.nav_wishlist), listOf(stringResource(R.string.tools_wishlist_hint)), { go(NavRoutes.Wishlist) }, trailing = chevron) }
            item {
                val launch = { csvLauncher.launch("dogmatixplus-collection-${LocalDate.now()}.csv") }
                ToolRow(stringResource(R.string.tools_export_csv), listOf(stringResource(R.string.tools_export_csv_hint)), launch) { PillButton(stringResource(R.string.tools_export_action), launch) }
            }
            item {
                val launch = { htmlLauncher.launch("dogmatixplus-collection-${LocalDate.now()}.html") }
                ToolRow(stringResource(R.string.tools_export_html), listOf(stringResource(R.string.tools_export_html_hint)), launch) { PillButton(stringResource(R.string.tools_export_action), launch) }
            }
        }
    }
}
