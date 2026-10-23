package com.tufein.valkyr.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 5.2: tapping the tab that is already open. [switchToTab] posts the tab's route here; the screen
 * behind the tab scrolls its list back to the top (see [OnTabReselect]). A tap on a tab while a
 * deeper screen of it is open pops back to the tab's root instead (see [switchToTab]).
 */
object TabReselect {
    private val bus = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events = bus.asSharedFlow()
    fun post(route: String) { bus.tryEmit(route) }
}

/** Runs [action] each time the tab [route] is tapped while it is already the open screen. */
@Composable
fun OnTabReselect(route: String, action: suspend () -> Unit) {
    val current = rememberUpdatedState(action)
    LaunchedEffect(route) {
        TabReselect.events.collect { if (it == route) current.value() }
    }
}
