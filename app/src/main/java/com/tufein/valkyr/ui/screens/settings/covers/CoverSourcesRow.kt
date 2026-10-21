package com.tufein.valkyr.ui.screens.settings.covers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.NavChevron
import com.tufein.valkyr.ui.screens.settings.SettingRow
import com.tufein.valkyr.util.CoverSources

/**
 * Settings row "Cover sources" (4.3.0, in the place of the old "find missing covers" row): opens
 * [CoverSourcesScreen]. The hint says what the screen is for, the sources in their order and,
 * when ScreenScraper is set up, its state (so a used-up daily limit is visible from Settings).
 */
@Composable
fun CoverSourcesRow(onOpen: () -> Unit, viewModel: CoverSourcesViewModel = hiltViewModel()) {
    val chain = viewModel.chain.collectAsState().value
    val login by viewModel.login.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val base = stringResource(R.string.covers43_row_hint)
    val order = chain?.active?.map { sourceName(it) }?.joinToString(" → ")
    val lines = buildList {
        add(base)
        if (!order.isNullOrEmpty()) add(stringResource(R.string.covers43_row_order, order))
        if (chain != null && chain.enabled(CoverSources.Source.SCREENSCRAPER) && login.ready) {
            add(sourceName(CoverSources.Source.SCREENSCRAPER) + ": " + screenScraperState(login, stats))
        }
    }
    SettingRow(
        icon = R.drawable.ic_image_search,
        title = stringResource(R.string.covers43_title),
        hint = lines.joinToString("\n"),
        onClick = onOpen
    ) { NavChevron() }
}
