package com.tufein.valkyr.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.GameShortcutSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecentGamesMenuViewModel @Inject constructor(private val settings: GameShortcutSettings) : ViewModel() {
    val enabled: StateFlow<Boolean> = settings.recentMenu.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setEnabled(on: Boolean) {
        viewModelScope.launch { settings.setRecentMenu(on) }
    }
}

/**
 * 3.5 Settings → Frontends → "Recently played in the icon menu": the last games started from
 * Dogmatix+ in the long-press menu of the app icon (default on).
 */
@Composable
fun RecentGamesMenuSettingRow(viewModel: RecentGamesMenuViewModel = hiltViewModel()) {
    val on by viewModel.enabled.collectAsState()
    SettingRow(
        icon = R.drawable.ic_play_circle,
        title = stringResource(R.string.pin35_setting_title),
        hint = stringResource(R.string.pin35_setting_hint),
        onClick = { viewModel.setEnabled(!on) },
        onAdjust = { viewModel.setEnabled(it > 0) }
    ) { ThemedSwitch(on) { viewModel.setEnabled(it) } }
}
