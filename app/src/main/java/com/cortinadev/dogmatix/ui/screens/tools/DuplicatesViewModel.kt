package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.LibraryScanService
import com.cortinadev.dogmatix.util.DuplicateFinder
import com.cortinadev.dogmatix.util.DuplicateGroup
import com.cortinadev.dogmatix.util.GameEntry
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DuplicatesUiState(
    val scanning: Boolean = true,
    val folderSet: Boolean = true,
    val filesChecked: Int = 0,
    val groups: List<DuplicateGroup> = emptyList()
) {
    val reclaimable: Long get() = groups.sumOf { it.reclaimable }
}

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val scanService: LibraryScanService,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DuplicatesUiState())
    val uiState: StateFlow<DuplicatesUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init { rescan() }

    fun rescan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.update { it.copy(scanning = true) }
            val folderSet = settingsRepository.downloadDirectory.first().isNotBlank() ||
                settingsRepository.consoleDownloadDirectories.first().isNotEmpty()
            // A newer rescan cancels this one: stop here instead of publishing an empty result.
            val snapshot = try {
                scanService.scan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val files = snapshot?.files.orEmpty()
            _uiState.value = DuplicatesUiState(
                scanning = false,
                folderSet = folderSet,
                filesChecked = files.count { DuplicateFinder.isGameFile(it) },
                groups = DuplicateFinder.find(files)
            )
        }
    }

    /**
     * Deletes [entry] from disk. The row leaves the list right away (so it cannot be confirmed
     * twice); the delete itself finishes even if the screen is closed meanwhile. If nothing
     * could be removed the list is read again from disk.
     */
    fun delete(context: Context, entry: GameEntry) {
        // Messages are resolved now, with the screen's context (it carries the in-app language);
        // the toasts use the application context because the screen may be gone by then.
        val appContext = context.applicationContext
        val failed = context.getString(R.string.duplicates_delete_failed, entry.baseName)
        val deleted = context.getString(R.string.duplicates_deleted, entry.baseName)
        _uiState.update { state -> state.copy(groups = state.groups.without(entry)) }
        viewModelScope.launch {
            val removed = try {
                scanService.delete(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                0
            }
            if (removed == 0) {
                ToastUtil.showError(appContext, failed)
                rescan()
                return@launch
            }
            ToastUtil.showSuccess(appContext, deleted)
            _uiState.update { it.copy(filesChecked = (it.filesChecked - removed).coerceAtLeast(0)) }
        }
    }

    /** The groups without [entry]; a group left with a single copy is no longer a duplicate. */
    private fun List<DuplicateGroup>.without(entry: GameEntry): List<DuplicateGroup> = mapNotNull { group ->
        val left = group.entries.filterNot { it.id == entry.id }
        if (left.size < 2) null else group.copy(entries = left)
    }
}
