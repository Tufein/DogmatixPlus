package com.cortinadev.dogmatix.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.util.FrontendCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class FrontendCheckViewModel @Inject constructor(settings: SettingsRepository, appSettings: AppSettings) : ViewModel() {
    val findings: StateFlow<List<FrontendCheck.Finding>> = combine(
        combine(settings.downloadDirectory, settings.esdeDirectory, settings.iisuDirectory) { d, e, i -> Triple(d, e, i) },
        combine(appSettings.esdeArtwork, appSettings.pegasusArtwork, appSettings.retroArchThumbnailsDir) { a, p, r -> Triple(a, p, r) }
    ) { (download, esde, iisu), (esdeCovers, pegasus, retroArch) ->
        FrontendCheck.evaluate(
            FrontendCheck.State(
                downloadFolderSet = download.isNotBlank(), esdeFolderSet = esde.isNotBlank(), esdeCovers = esdeCovers,
                iisuFolderSet = iisu.isNotBlank(), pegasusCovers = pegasus, retroArchThumbnailsSet = retroArch.isNotBlank()
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/** How Dogmatix is hooked up to ES-DE, iiSU, Daijishō, Pegasus and RetroArch, with a way to Settings to fix it. */
@Composable
fun FrontendCheckScreen(navController: NavController, viewModel: FrontendCheckViewModel = hiltViewModel()) {
    val findings by viewModel.findings.collectAsState()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_frontends))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(findings, key = { _, f -> f.frontend.name }) { index, f ->
                val name = when (f.frontend) {
                    FrontendCheck.Frontend.ES_DE -> "ES-DE"
                    FrontendCheck.Frontend.IISU -> "iiSU"
                    FrontendCheck.Frontend.DAIJISHO -> "Daijishō"
                    FrontendCheck.Frontend.PEGASUS -> "Pegasus"
                    FrontendCheck.Frontend.RETROARCH -> "RetroArch"
                }
                val text = stringResource(
                    when (f.detail) {
                        FrontendCheck.Detail.ESDE_READY -> R.string.frontend_esde_ready
                        FrontendCheck.Detail.ESDE_NO_FOLDER -> R.string.frontend_esde_no_folder
                        FrontendCheck.Detail.ESDE_NO_COVERS -> R.string.frontend_esde_no_covers
                        FrontendCheck.Detail.IISU_READY -> R.string.frontend_iisu_ready
                        FrontendCheck.Detail.IISU_NO_FOLDER -> R.string.frontend_iisu_no_folder
                        FrontendCheck.Detail.DAIJISHO_MANUAL -> R.string.frontend_daijisho_manual
                        FrontendCheck.Detail.PEGASUS_READY -> R.string.frontend_pegasus_ready
                        FrontendCheck.Detail.PEGASUS_OFF -> R.string.frontend_pegasus_off
                        FrontendCheck.Detail.RETROARCH_READY -> R.string.frontend_retroarch_ready
                        FrontendCheck.Detail.RETROARCH_NO_FOLDER -> R.string.frontend_retroarch_no_folder
                        FrontendCheck.Detail.NEEDS_DOWNLOAD_FOLDER -> R.string.frontend_needs_download_folder
                    }
                )
                val badge = stringResource(
                    when (f.status) {
                        FrontendCheck.Status.READY -> R.string.frontend_status_ready
                        FrontendCheck.Status.TODO -> R.string.frontend_status_todo
                        FrontendCheck.Status.MANUAL -> R.string.frontend_status_manual
                    }
                )
                ToolRow(
                    name, listOf(text), { navController.navigate(NavRoutes.Settings.route) },
                    if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    badge = { Badge(badge, warning = f.status == FrontendCheck.Status.TODO) }
                )
            }
        }
    }
}
