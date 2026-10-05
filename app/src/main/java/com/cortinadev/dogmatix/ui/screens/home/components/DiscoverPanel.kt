package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.screens.home.FETCH_CAP
import com.cortinadev.dogmatix.ui.screens.home.HomeViewModel
import java.text.NumberFormat

/**
 * Under the Genre / Decade rows of the filter panel: says honestly how many titles those filters
 * can know about, and offers the opt-in "Fetch details for shown games" action (a capped, rate
 * limited lookup; nothing is fetched until it is chosen).
 */
@Composable
fun DiscoverBlock(
    knownTitles: Int,
    libraryFiles: Int,
    fetch: HomeViewModel.FetchState,
    canFetch: Boolean,
    onFetch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            if (knownTitles > 0) pluralStringResource(R.plurals.disc6_known, knownTitles, numbers.format(knownTitles), numbers.format(libraryFiles))
            else stringResource(R.string.disc6_known_none),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant
        )
        if (canFetch) {
            val running = fetch as? HomeViewModel.FetchState.Running
            ActionPill(
                label = if (running != null) stringResource(R.string.disc6_fetch_running, running.done, running.total) else stringResource(R.string.disc6_fetch),
                onClick = onFetch,
                icon = R.drawable.ic_download,
                tone = if (running != null) ActionTone.Accent else ActionTone.Neutral
            )
            val note = when (fetch) {
                is HomeViewModel.FetchState.Done ->
                    if (fetch.total == 0) stringResource(R.string.disc6_fetch_nothing)
                    else pluralStringResource(R.plurals.disc6_fetch_done, fetch.total, fetch.found, fetch.total)
                HomeViewModel.FetchState.NoKeys -> stringResource(R.string.disc6_fetch_nokeys)
                else -> stringResource(R.string.disc6_fetch_hint, FETCH_CAP)
            }
            Text(note, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}
