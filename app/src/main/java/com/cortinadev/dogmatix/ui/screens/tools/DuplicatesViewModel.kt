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
            val snapshot = runCatching { scanService.scan() }.getOrNull()
            val files = snapshot?.files.orEmpty()
            _uiState.value = DuplicatesUiState(
                scanning = false,
                folderSet = folderSet,
                filesChecked = files.count { DuplicateFinder.isGameFile(it.name) },
                groups = DuplicateFinder.find(files)
            )
        }
    }

    /** Deletes [entry] from disk and drops it from its group (and the group once one copy is left). */
    fun delete(context: Context, entry: GameEntry) {
        viewModelScope.launch {
            val removed = runCatching { scanService.delete(entry) }.getOrDefault(0)
            if (removed == 0) {
                ToastUtil.showError(context, context.getString(R.string.duplicates_delete_failed, entry.baseName))
                return@launch
            }
            ToastUtil.showSuccess(context, context.getString(R.string.duplicates_deleted, entry.baseName))
            _uiState.update { state ->
                state.copy(
                    filesChecked = state.filesChecked - removed,
                    groups = state.groups.mapNotNull { group ->
                        val left = group.entries.filterNot { it == entry }
                        if (left.size < 2) null else group.copy(entries = left)
                    }
                )
            }
        }
    }
}
