package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.local.FrontendCoversSettings
import com.tufein.valkyr.data.service.CoversPreview
import com.tufein.valkyr.data.service.CoversRunState
import com.tufein.valkyr.data.service.FrontendCoversService
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.util.FrontendCovers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The route of the cover export screen (NavRoutes, Tools, search). */
const val FRONTEND_COVERS_ROUTE = "frontend_covers"

data class FrontendCoversUi(
    val target: FrontendCovers.Target = FrontendCovers.Target.IISU,
    val settings: FrontendCoversSettings.TargetSettings? = null,
    val previewLoading: Boolean = true,
    val previewFailed: Boolean = false,
    /** The plan for [target]; null while it is worked out or when it failed. */
    val preview: CoversPreview? = null,
    val run: CoversRunState = CoversRunState(),
    /** The game the layout examples are made from: the last preview's, kept while a new one is worked out. */
    val sample: FrontendCovers.Game? = null
)

private sealed interface PreviewState {
    data object Loading : PreviewState
    data object Failed : PreviewState
    data class Ready(val preview: CoversPreview) : PreviewState
}

/**
 * Tools -> Covers for launchers: which launcher, its folder, layout and overwrite choice,
 * the preview of what a run would write (worked out again whenever one of those changes, the
 * profile changes or a run ends), and the run itself in [FrontendCoversService].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FrontendCoversViewModel @Inject constructor(
    private val service: FrontendCoversService,
    private val settings: FrontendCoversSettings,
    profiles: ProfileService
) : ViewModel() {
    private val target = MutableStateFlow(FrontendCovers.Target.IISU)
    private val refresh = MutableStateFlow(0)
    private val preview = MutableStateFlow<PreviewState>(PreviewState.Loading)
    private val sample = MutableStateFlow<FrontendCovers.Game?>(null)
    private val targetSettings = target.flatMapLatest { settings.of(it) }

    val ui: StateFlow<FrontendCoversUi> = combine(target, targetSettings, preview, service.state, sample) { t, s, p, run, game ->
        FrontendCoversUi(
            target = t,
            settings = s,
            previewLoading = p is PreviewState.Loading,
            previewFailed = p is PreviewState.Failed,
            preview = (p as? PreviewState.Ready)?.preview?.takeIf { it.target == t },
            run = run,
            sample = game
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FrontendCoversUi())

    init {
        viewModelScope.launch {
            val runEnded = service.state.map { !it.running }.distinctUntilChanged()
            combine(target, targetSettings, profiles.activeId, refresh, runEnded) { t, s, _, _, ended -> Triple(t, s, ended) }
                .collectLatest { (t, _, ended) ->
                    // While covers are being written the preview would only count them twice.
                    if (!ended) return@collectLatest
                    preview.value = PreviewState.Loading
                    preview.value = try {
                        PreviewState.Ready(service.preview(t).also { sample.value = it.sample })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        PreviewState.Failed
                    }
                }
        }
    }

    fun selectTarget(t: FrontendCovers.Target) { if (!service.state.value.running) target.value = t }
    fun setFolder(uri: String) { viewModelScope.launch { settings.setFolder(target.value, uri) } }
    fun setLayout(layout: FrontendCovers.Layout) { viewModelScope.launch { settings.setLayout(target.value, layout) } }
    fun setOverwrite(on: Boolean) { viewModelScope.launch { settings.setOverwrite(target.value, on) } }
    /** Saves the user's own cover path and chooses it as the layout (a pattern that cannot be used is refused). */
    fun setPattern(pattern: String) { viewModelScope.launch { settings.setPattern(target.value, pattern) } }
    fun refresh() { refresh.value++ }

    fun write() = service.start(target.value)
    fun stop() = service.cancel()
    fun dismiss() = service.clear()
}
