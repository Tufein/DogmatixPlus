package com.tufein.valkyr.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.NewHandheldSettings
import com.tufein.valkyr.data.local.dao.ConsoleDao
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.util.SourcesJson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Whether the empty library shows the "Set up a new handheld" hint: only on a fresh install (no
 * console has a source) and until the user waves it away.
 */
@HiltViewModel
class NewHandheldHintViewModel @Inject constructor(
    consoleDao: ConsoleDao,
    private val store: NewHandheldSettings
) : ViewModel() {

    val show: StateFlow<Boolean> = combine(consoleDao.getAllConsoles(), store.homeHintDismissed) { consoles, dismissed ->
        !dismissed && consoles.none { SourcesJson.parseUrlEntries(it.urls).isNotEmpty() }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun dismiss() {
        viewModelScope.launch { store.dismissHomeHint() }
    }
}

/**
 * A small card under the empty-library message that points a fresh install at the checklist.
 * "Not now" hides it for good on this handheld; the checklist stays in Tools.
 */
@Composable
fun NewHandheldHint(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NewHandheldHintViewModel = hiltViewModel()
) {
    val show by viewModel.show.collectAsState()
    if (!show) return
    val scheme = MaterialTheme.colorScheme
    Panel(
        modifier = modifier.widthIn(max = 440.dp).fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        tone = PanelTone.Raised,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(R.drawable.ic_devices, size = 36.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.wizard35_hint_title), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(
                    stringResource(R.string.wizard35_hint_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        ToolsActions(horizontalPadding = 0.dp, modifier = Modifier.padding(top = 10.dp)) {
            ActionPill(stringResource(R.string.wizard35_hint_open), onOpen, icon = R.drawable.ic_arrow_forward, tone = ActionTone.Accent)
            ActionPill(stringResource(R.string.wizard35_hint_dismiss), viewModel::dismiss, icon = R.drawable.ic_close)
        }
    }
}
