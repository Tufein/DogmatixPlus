package com.cortinadev.dogmatix.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.service.LibraryOverview
import com.cortinadev.dogmatix.data.service.LibraryScanService
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.data.state.ScanProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryOverviewUiState(
    val loading: Boolean = true,
    val overview: LibraryOverview? = null
)

@HiltViewModel
class LibraryOverviewViewModel @Inject constructor(
    private val scanService: LibraryScanService,
    rescanStateHolder: RescanStateHolder
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryOverviewUiState())
    val uiState: StateFlow<LibraryOverviewUiState> = _uiState.asStateFlow()

    val isRescanning: StateFlow<Boolean> = rescanStateHolder.isRescanning
    val progressMessage: StateFlow<String> = rescanStateHolder.progressMessage
    val progress: StateFlow<ScanProgress?> = rescanStateHolder.progress

    private var loadJob: Job? = null

    init {
        refresh()
        // A source scan that finishes while the overview is open changes every number: reload.
        viewModelScope.launch {
            rescanStateHolder.isRescanning.drop(1).filter { !it }.collect { refresh() }
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val overview = runCatching { scanService.overview() }.getOrNull()
            _uiState.value = LibraryOverviewUiState(loading = false, overview = overview ?: _uiState.value.overview)
        }
    }
}
