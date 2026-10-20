package com.tufein.valkyr.ui.screens.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.service.GameShortcutService
import com.tufein.valkyr.data.service.GameShortcutService.PinResult
import com.tufein.valkyr.ui.components.ActionPill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeScreenPillViewModel @Inject constructor(private val games: GameShortcutService) : ViewModel() {
    private var busy = false

    /** Asks the launcher for a home-screen icon of [file]; [onResult] runs on the main thread. */
    fun pin(file: DownloadableFileEntity, onResult: (PinResult) -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val result = try { games.pin(file) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { PinResult.FAILED }
            finally { busy = false }
            onResult(result)
        }
    }

    fun labelOf(file: DownloadableFileEntity): String = games.labelOf(file)
}

/**
 * 3.5 game page action "Add to home screen": a launcher icon that starts the game straight in its
 * emulator ([GameShortcutService]). The page shows it where it shows Play; [onMessage] is the
 * page's own snackbar.
 */
@Composable
fun HomeScreenPill(file: DownloadableFileEntity, onMessage: (String) -> Unit, modifier: Modifier = Modifier, viewModel: HomeScreenPillViewModel = hiltViewModel()) {
    ActionPill(
        stringResource(R.string.pin35_action),
        onClick = rememberHomeScreenPin(file, onMessage, viewModel),
        modifier = modifier,
        icon = R.drawable.ic_shortcut
    )
}

/**
 * 3.8: the "Add to home screen" action on its own, for the game page's round button: asks the
 * launcher for the icon and reports the outcome through [onMessage].
 */
@Composable
fun rememberHomeScreenPin(file: DownloadableFileEntity, onMessage: (String) -> Unit, viewModel: HomeScreenPillViewModel = hiltViewModel()): () -> Unit {
    val title = remember(file.consoleId, file.fileName, file.name) { viewModel.labelOf(file) }
    val requested = stringResource(R.string.pin35_requested, title)
    val unsupported = stringResource(R.string.pin35_unsupported)
    val noEmulator = stringResource(R.string.play_no_handler)
    val failed = stringResource(R.string.pin35_failed)
    return {
        viewModel.pin(file) { result ->
            onMessage(
                when (result) {
                    PinResult.REQUESTED -> requested
                    PinResult.UNSUPPORTED -> unsupported
                    PinResult.NO_EMULATOR -> noEmulator
                    PinResult.FAILED -> failed
                }
            )
        }
    }
}
