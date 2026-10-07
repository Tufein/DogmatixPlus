package com.cortinadev.dogmatix.ui.screens.download

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.EmptyState
import com.cortinadev.dogmatix.ui.screens.home.components.SearchField
import com.cortinadev.dogmatix.ui.screens.sources.components.ChoiceChip
import com.cortinadev.dogmatix.util.DownloadQueueFilter
import com.cortinadev.dogmatix.util.QueueFilter

/** Search is activated explicitly, so the D-pad can reach it without opening the keyboard on every pass. */
@Composable
fun QueueFilters(
    view: DownloadQueueFilter.View,
    criteria: DownloadQueueFilter.Criteria,
    allShownSelected: Boolean,
    onSearch: (String) -> Unit,
    onFilter: (QueueFilter) -> Unit,
    onClear: () -> Unit,
    onSelectShown: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchActive by remember { mutableStateOf(false) }
    val searchButton = remember { FocusRequester() }
    BackHandler(enabled = searchActive) {
        searchActive = false
        runCatching { searchButton.requestFocus() }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(
                value = criteria.query,
                onValueChange = onSearch,
                active = searchActive,
                onActivate = { searchActive = true },
                onDismiss = { searchActive = false; runCatching { searchButton.requestFocus() } },
                placeholder = stringResource(R.string.queue23_search_hint),
                modifier = Modifier.weight(1f)
            )
            ActionPill(
                stringResource(R.string.queue23_search),
                onClick = { searchActive = true },
                icon = R.drawable.ic_search,
                modifier = Modifier.focusRequester(searchButton)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QueueFilter.entries.forEach { filter ->
                ChoiceChip(
                    stringResource(labelFor(filter)) + " · " + (view.counts[filter] ?: 0),
                    selected = criteria.status == filter,
                    onClick = { onFilter(filter) }
                )
            }
        }
        Text(
            stringResource(R.string.queue23_showing, view.rows.size, view.total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (view.rows.isNotEmpty()) {
                ActionPill(
                    stringResource(if (allShownSelected) R.string.selection_clear else R.string.queue23_select_shown),
                    onSelectShown,
                    icon = if (allShownSelected) R.drawable.ic_close else R.drawable.ic_check_circle
                )
            }
            if (criteria.query.isNotBlank() || criteria.status != QueueFilter.ALL) {
                ActionPill(stringResource(R.string.queue23_clear_filters), onClear, icon = R.drawable.ic_clear_filter)
            }
        }
    }
}

@Composable
fun QueueNoMatches(onClear: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.queue23_no_matches),
        message = stringResource(R.string.queue23_no_matches_hint),
        icon = R.drawable.ic_filter,
        actionLabel = stringResource(R.string.queue23_clear_filters),
        onAction = onClear,
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)
    )
}

private fun labelFor(filter: QueueFilter): Int = when (filter) {
    QueueFilter.ALL -> R.string.queue23_all
    QueueFilter.ACTIVE -> R.string.queue23_active
    QueueFilter.WAITING -> R.string.queue23_waiting
    QueueFilter.PAUSED -> R.string.queue23_paused
    QueueFilter.PROBLEMS -> R.string.queue23_problems
    QueueFilter.COMPLETED -> R.string.queue23_completed
}
