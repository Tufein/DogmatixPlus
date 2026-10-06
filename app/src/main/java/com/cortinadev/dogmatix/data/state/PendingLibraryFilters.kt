package com.cortinadev.dogmatix.data.state

import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.util.QuickAction
import com.cortinadev.dogmatix.util.SharedLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Filters requested through a `dogmatix://library?…` deep link (see [com.cortinadev.dogmatix.util.DeepLinkParser]). */
data class LibraryFilterRequest(
    val consoles: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val query: String? = null,
    val favouritesOnly: Boolean? = null,
    /** Show only what recent rescans found (`new=1`). */
    val newOnly: Boolean? = null,
    /** Show one own collection (`collection=<id>`). */
    val collectionId: Long? = null
)

/**
 * Hand-off between the Activity (which receives the intent) and the library ViewModel (which
 * applies it). The request waits here until Home is on screen, so links opened during
 * onboarding or from another tab are not lost.
 */
@Singleton
class PendingLibraryFilters @Inject constructor() {
    private val _request = MutableStateFlow<LibraryFilterRequest?>(null)
    val request: StateFlow<LibraryFilterRequest?> = _request.asStateFlow()

    /** Bumped on every [submit]; the shell watches it to switch to the Library tab. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun submit(request: LibraryFilterRequest) {
        _request.value = request
        _version.update { it + 1 }
    }

    fun consume(): LibraryFilterRequest? = _request.getAndUpdate { null }

    /** A section the app should switch to (the widget's download line); taken by the shell. */
    private val _openRoute = MutableStateFlow<String?>(null)
    val openRoute: StateFlow<String?> = _openRoute.asStateFlow()

    fun openSection(route: String) { _openRoute.value = route }

    /** A link shared to the app (browser, chat), waiting for the user to say what to do with it. */
    private val _shared = MutableStateFlow<SharedLink?>(null)
    val shared: StateFlow<SharedLink?> = _shared.asStateFlow()
    fun share(link: SharedLink) { _shared.value = link }
    fun dismissShare() { _shared.value = null }
    fun consumeSection(): String? = _openRoute.getAndUpdate { null }

    /** 7.0: a quick action (launcher shortcut) waiting for the Library screen; taken by HomeViewModel. */
    private val _quick = MutableStateFlow<QuickAction?>(null)
    val quick: StateFlow<QuickAction?> = _quick.asStateFlow()

    /**
     * Hands a quick action to the UI. Downloads is a section (the shell opens it); Surprise me and
     * Search belong to the Library screen, so they wait here and the shell switches to that tab.
     */
    fun submitQuick(action: QuickAction) {
        if (action == QuickAction.DOWNLOADS) { openSection(NavRoutes.Downloads.route); return }
        _quick.value = action
        _version.update { it + 1 }
    }

    fun consumeQuick(): QuickAction? = _quick.getAndUpdate { null }

    companion object {
        /** Intent extra with a route to open (see [openSection]). */
        const val EXTRA_OPEN_ROUTE = "com.cortinadev.dogmatix.OPEN_ROUTE"
    }
}
