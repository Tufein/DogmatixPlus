package com.cortinadev.dogmatix.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.TvModeSettings
import com.cortinadev.dogmatix.ui.components.LocalTvMode
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.util.TvMode
import com.cortinadev.dogmatix.util.TvModeSetting
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TvModeSettingViewModel @Inject constructor(private val settings: TvModeSettings) : ViewModel() {
    val mode: StateFlow<TvModeSetting> = settings.mode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvModeSetting.AUTO)

    fun shift(delta: Int) {
        val next = TvMode.shift(mode.value, delta)
        viewModelScope.launch { settings.setMode(next) }
    }
}

/**
 * 8.0 Settings → Look → "TV mode": Auto / On / Off, stepped like the gamepad layout row. The hint
 * says whether the TV layout is on right now, so Auto is not a guess.
 */
@Composable
fun TvModeSettingRow(viewModel: TvModeSettingViewModel = hiltViewModel()) {
    val mode by viewModel.mode.collectAsState()
    val active = LocalTvMode.current
    SettingRow(
        icon = R.drawable.ic_tv,
        title = stringResource(R.string.tv8_setting_title),
        hint = stringResource(if (active) R.string.tv8_setting_hint_on else R.string.tv8_setting_hint_off),
        onClick = { viewModel.shift(1) },
        onAdjust = viewModel::shift
    ) {
        Stepper(
            stringResource(
                when (mode) {
                    TvModeSetting.AUTO -> R.string.tv8_mode_auto
                    TvModeSetting.ON -> R.string.tv8_mode_on
                    TvModeSetting.OFF -> R.string.tv8_mode_off
                }
            ),
            onDecrement = { viewModel.shift(-1) },
            onIncrement = { viewModel.shift(1) },
            valueWidth = 110.dp
        )
    }
}
