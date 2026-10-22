package com.tufein.valkyr.ui.screens.next

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.UpNextService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.util.GameTitleCleaner
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.VersionPicker
import com.tufein.valkyr.util.UpNext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs [UpNextPill]: whether a game is on the active profile's list, and the toggle. */
@HiltViewModel
class UpNextPillViewModel @Inject constructor(private val service: UpNextService) : ViewModel() {

    val keys: StateFlow<Set<String>> = service.keys

    /** Adds or removes the game; [onDone] gets the new state, or null when the list could not be saved. */
    fun toggle(consoleId: String, fileName: String, onDone: (Boolean?) -> Unit) {
        viewModelScope.launch {
            val wasOn = UpNext.key(consoleId, fileName) in service.keys.value
            service.toggle(consoleId, fileName)
            val isOn = UpNext.key(consoleId, fileName) in service.keys.value
            onDone(if (isOn != wasOn) isOn else null)
        }
    }
}

/**
 * "Add to Up next" / "Remove from Up next" for the game page's action row (3.0.0). One line in
 * GamePage's FlowRow, next to Favourite: `UpNextPill(rom.consoleId, rom.fileName)`. Works on the
 * active profile's list, with a short confirmation.
 */
@Composable
fun UpNextPill(consoleId: String, fileName: String, modifier: Modifier = Modifier, viewModel: UpNextPillViewModel = hiltViewModel()) {
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val on = UpNext.key(consoleId, fileName) in keys
    val title = remember(fileName) { GameTitleCleaner.clean(VersionPicker.readable(fileName)).ifBlank { fileName } }
    val added = stringResource(R.string.next30_added, title)
    val removed = stringResource(R.string.next30_removed, title)
    val failed = stringResource(R.string.next30_save_failed)
    ActionPill(
        stringResource(if (on) R.string.next30_remove else R.string.next30_add),
        onClick = {
            viewModel.toggle(consoleId, fileName) { result ->
                when (result) {
                    true -> ToastUtil.showSuccess(context, added)
                    false -> ToastUtil.showSuccess(context, removed)
                    null -> ToastUtil.showError(context, failed)
                }
            }
        },
        modifier = modifier,
        icon = if (on) R.drawable.ic_check else R.drawable.ic_playlist_add,
        tone = if (on) ActionTone.Accent else ActionTone.Neutral
    )
}
