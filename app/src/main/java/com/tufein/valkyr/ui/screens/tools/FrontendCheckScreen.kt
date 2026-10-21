package com.tufein.valkyr.ui.screens.tools

import com.tufein.valkyr.ui.components.requestFocusWhenReady
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
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.util.FrontendCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import com.tufein.valkyr.ui.screens.settings.SettingsJump
import com.tufein.valkyr.util.SettingKeys

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
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_frontends), icon = NavRoutes.Frontends.icon)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
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
                val tone = when (f.status) {
                    FrontendCheck.Status.READY -> PillTone.Success
                    FrontendCheck.Status.TODO -> PillTone.Warning
                    FrontendCheck.Status.MANUAL -> PillTone.Info
                }
                val icon = when (f.status) {
                    FrontendCheck.Status.READY -> R.drawable.ic_check_circle
                    FrontendCheck.Status.TODO -> R.drawable.ic_warning
                    FrontendCheck.Status.MANUAL -> R.drawable.ic_info
                }
                ToolRow(
                    name, listOf(text), {
                        // Straight to the row that sets this frontend up.
                        SettingsJump.request(settingFor(f.detail))
                        navController.navigate(NavRoutes.Settings.route)
                    },
                    if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    badge = { Badge(badge, warning = f.status == FrontendCheck.Status.TODO, tone = tone, icon = icon) },
                    icon = R.drawable.ic_frontends,
                    chevron = true
                )
            }
        }
    }
}

/** The Settings row that fixes or sets up what [detail] reports. */
private fun settingFor(detail: FrontendCheck.Detail): String = when (detail) {
    FrontendCheck.Detail.ESDE_READY, FrontendCheck.Detail.ESDE_NO_FOLDER -> SettingKeys.ESDE
    FrontendCheck.Detail.ESDE_NO_COVERS -> SettingKeys.ESDE_ARTWORK
    FrontendCheck.Detail.IISU_READY, FrontendCheck.Detail.IISU_NO_FOLDER -> SettingKeys.IISU
    FrontendCheck.Detail.DAIJISHO_MANUAL -> SettingKeys.DAIJISHO
    FrontendCheck.Detail.PEGASUS_READY, FrontendCheck.Detail.PEGASUS_OFF -> SettingKeys.PEGASUS_ARTWORK
    FrontendCheck.Detail.RETROARCH_READY, FrontendCheck.Detail.RETROARCH_NO_FOLDER -> SettingKeys.RETROARCH_ARTWORK
    FrontendCheck.Detail.NEEDS_DOWNLOAD_FOLDER -> SettingKeys.DOWNLOAD_DIR
}
