package com.cortinadev.dogmatix.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.VersionPreferenceSettings
import com.cortinadev.dogmatix.ui.components.NavChevron
import com.cortinadev.dogmatix.util.VersionPreference
import com.cortinadev.dogmatix.util.VersionPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the settings row says about the preference: the preference and whether the user pinned it. */
data class VersionPreferenceSummary(val preference: VersionPreference, val pinned: Boolean)

@HiltViewModel
class VersionPreferenceRowViewModel @Inject constructor(settings: VersionPreferenceSettings) : ViewModel() {
    val summary: StateFlow<VersionPreferenceSummary?> = combine(settings.global, settings.pinned) { global, pinned ->
        VersionPreferenceSummary(global, pinned != null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * Settings row "Version preference": opens [VersionPreferenceScreen]. The hint says what it is and,
 * under it, what applies now ("Set by you: World, Europe, USA · Dutch, English", or "From your
 * favourite languages: …" while nothing is set). The text wraps in full.
 */
@Composable
fun VersionPreferenceRow(onOpen: () -> Unit, viewModel: VersionPreferenceRowViewModel = hiltViewModel()) {
    val summary by viewModel.summary.collectAsState()
    val locale = LocalConfiguration.current.locales[0]
    val base = stringResource(R.string.compare24_row_hint)
    val now = summary?.let {
        val text = VersionPreferences.summary(it.preference, locale)
        stringResource(if (it.pinned) R.string.compare24_row_pinned else R.string.compare24_row_default, text)
    }
    SettingRow(
        icon = R.drawable.ic_compare,
        title = stringResource(R.string.compare24_title),
        hint = if (now == null) base else base + "\n" + now,
        onClick = onOpen
    ) { NavChevron() }
}
