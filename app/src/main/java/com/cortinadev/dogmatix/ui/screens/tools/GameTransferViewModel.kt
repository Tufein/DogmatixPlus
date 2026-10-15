package com.cortinadev.dogmatix.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.TransferLayoutSettings
import com.cortinadev.dogmatix.data.service.GameTransferService
import com.cortinadev.dogmatix.data.service.PackageTransferSnapshot
import com.cortinadev.dogmatix.data.service.TransferPreview
import com.cortinadev.dogmatix.data.service.TransferRun
import com.cortinadev.dogmatix.data.service.TransferTarget
import com.cortinadev.dogmatix.util.CopyConflictChoice
import com.cortinadev.dogmatix.util.TargetFolderLayouts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The route of Tools → Copy or send games (NavRoutes, Tools, search, the package records screen). */
const val GAME_TRANSFER_ROUTE = "game_transfer"

data class GameTransferUi(
    val snapshot: PackageTransferSnapshot? = null,
    val availability: GameTransferService.Availability? = null,
    val loadFailed: Boolean = false,
    val selected: Set<String> = emptySet(),
    val target: TransferTarget = TransferTarget.FOLDER,
    val choice: CopyConflictChoice = CopyConflictChoice.SKIP,
    val readBack: Boolean = true,
    /** Folder names at a folder destination (3.5.0), remembered in the settings. */
    val layout: TargetFolderLayouts.Layout = TargetFolderLayouts.Layout.LIBRARY,
    /** The folder picked last (kept for a retry). */
    val folderUri: String? = null,
    val checking: Boolean = false,
    val preview: TransferPreview? = null,
    val previewError: String? = null
) {
    val targetReady: Boolean get() = when (target) {
        TransferTarget.FOLDER -> true
        TransferTarget.ROMM -> availability?.romm == true
        TransferTarget.NAS -> availability?.nas == true
    }
    val canPreview: Boolean get() = selected.isNotEmpty() && targetReady && !checking && snapshot != null
}

/**
 * Tools → Copy or send games: pick games and a destination, look at the preview, then copy or
 * send. The transfer itself runs in [GameTransferService], so it goes on when the screen is left;
 * [run] is its state. A profile switch drops the selection and the preview.
 */
@HiltViewModel
class GameTransferViewModel @Inject constructor(
    private val service: GameTransferService,
    settings: AppSettings,
    private val layoutSettings: TransferLayoutSettings
) : ViewModel() {
    private val _ui = MutableStateFlow(GameTransferUi())
    val ui: StateFlow<GameTransferUi> = _ui.asStateFlow()
    val run: StateFlow<TransferRun?> = service.run
    private var loadJob: Job? = null
    private var checkJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            combine(settings.activeProfile, settings.profiles) { _, _ -> Unit }.drop(1).collect {
                checkJob?.cancel()
                _ui.update { GameTransferUi(layout = it.layout) }
                load()
            }
        }
        viewModelScope.launch {
            layoutSettings.folderLayout.collect { layout -> _ui.update { it.copy(layout = layout) } }
        }
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val availability = service.availability()
                val snapshot = service.snapshot()
                val keys = snapshot.records.map { it.sourceIdentity }.toSet()
                _ui.update { it.copy(snapshot = snapshot, availability = availability, loadFailed = false, selected = it.selected.filter { k -> k in keys }.toSet()) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.update { it.copy(loadFailed = true) }
            }
        }
    }

    fun toggle(key: String) = _ui.update { if (key in it.selected) it.copy(selected = it.selected - key) else it.copy(selected = it.selected + key) }
    fun selectAll() = _ui.update { it.copy(selected = it.snapshot?.records?.map { r -> r.sourceIdentity }?.toSet().orEmpty()) }
    fun selectNone() = _ui.update { it.copy(selected = emptySet()) }
    fun setTarget(target: TransferTarget) = _ui.update { it.copy(target = target, preview = null, previewError = null) }
    fun setChoice(choice: CopyConflictChoice) = _ui.update { it.copy(choice = choice) }
    fun toggleReadBack() = _ui.update { it.copy(readBack = !it.readBack) }

    /** Chooses (and remembers) the folder names for a folder destination. */
    fun setLayout(layout: TargetFolderLayouts.Layout) {
        _ui.update { it.copy(layout = layout) }
        viewModelScope.launch { layoutSettings.setFolderLayout(layout) }
    }

    /** Builds the preview; for a folder [folderUri] is the folder just picked. Writes nothing. */
    fun check(folderUri: String? = null) {
        val state = _ui.value
        val snapshot = state.snapshot ?: return
        if (state.checking || state.selected.isEmpty()) return
        val uri = folderUri ?: state.folderUri
        if (state.target == TransferTarget.FOLDER && uri == null) return
        _ui.update { it.copy(checking = true, preview = null, previewError = null, folderUri = uri) }
        checkJob = viewModelScope.launch {
            try {
                val layout = if (state.target == TransferTarget.FOLDER) state.layout else TargetFolderLayouts.Layout.LIBRARY
                val preview = service.preview(snapshot, state.selected.toList(), state.target, uri, state.choice, state.readBack, layout)
                _ui.update { it.copy(checking = false, preview = preview) }
            } catch (e: CancellationException) {
                _ui.update { it.copy(checking = false) }
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(checking = false, previewError = service.describe(e)) }
            }
        }
    }

    fun cancelCheck() {
        checkJob?.cancel()
        _ui.update { it.copy(checking = false) }
    }

    fun back() = _ui.update { it.copy(preview = null, previewError = null) }

    /** Starts the transfer of the preview the user looked at; false when another one runs. */
    fun start(): Boolean {
        val preview = _ui.value.preview ?: return false
        if (!preview.canStart) return false
        val started = service.start(preview)
        if (started) _ui.update { it.copy(preview = null) }
        return started
    }

    fun stop() = service.stop()

    /** The games that failed, were not sent or are not listed yet, checked again (a new preview first). */
    fun retry() {
        val finished = run.value ?: return
        if (finished.running) return
        val keys = finished.retryKeys.toSet()
        if (keys.isEmpty()) return
        val previous = finished.preview
        service.clear()
        // A retry keeps the folder names the run had, so the files land next to the ones that made it.
        _ui.update { it.copy(selected = keys, target = previous.target, choice = previous.choice, readBack = previous.readBack,
            folderUri = previous.folderUri ?: it.folderUri,
            layout = if (previous.target == TransferTarget.FOLDER) previous.layout else it.layout) }
        check(previous.folderUri)
    }

    /** Leaves the results and goes back to the selection (refreshed). */
    fun finish() {
        service.clear()
        load()
    }
}
