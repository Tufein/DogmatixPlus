package com.cortinadev.dogmatix.ui.screens.cloud.sections

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.RommSettings
import com.cortinadev.dogmatix.data.service.RommCollectionsService
import com.cortinadev.dogmatix.data.service.RommFavouritesState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * Stage B: in RommScreen (Settings → RomM), next to "Mark games in RomM":
 *   val fav: RommFavouritesViewModel = hiltViewModel()
 *   val favOn by fav.enabled.collectAsState(); val favState by fav.state.collectAsState()
 *   SettingRow(title = stringResource(R.string.romm5_fav_two_way), hint = rommFavouritesHint(favOn, favState),
 *       onClick = { fav.setEnabled(!favOn) }, onAdjust = { fav.setEnabled(it > 0) }) { ThemedSwitch(favOn) { fav.setEnabled(it) } }
 */

/** "Keep favourites in step with RomM" (Settings → RomM): the switch and how the last sync went. */
@HiltViewModel
class RommFavouritesViewModel @Inject constructor(
    private val settings: RommSettings,
    private val collections: RommCollectionsService
) : ViewModel() {

    val enabled: StateFlow<Boolean> = settings.favouritesTwoWay.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val state: StateFlow<RommFavouritesState> = collections.favouritesState

    /** Turning it on starts a first merge (it only adds, on both sides) a moment later. */
    fun setEnabled(on: Boolean) {
        viewModelScope.launch { settings.setFavouritesTwoWay(on) }
    }

    fun syncNow() = collections.syncFavouritesNow()
}

/** The second line of the favourites switch: what it does, or how the last sync went. */
@Composable
fun rommFavouritesHint(enabled: Boolean, state: RommFavouritesState): String = when {
    !enabled -> stringResource(R.string.romm5_fav_two_way_hint)
    state.running -> stringResource(R.string.romm5_fav_syncing)
    state.errorKind != null -> stringResource(R.string.romm5_fav_failed, stringResource(rommErrorText(state.errorKind)))
    state.syncedAt > 0 -> stringResource(
        R.string.romm5_fav_synced,
        DateUtils.getRelativeTimeSpanString(state.syncedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    )
    else -> stringResource(R.string.romm5_fav_two_way_hint)
}
