package com.cortinadev.dogmatix.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.local.FrontendMetadataSettings
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.FrontendMetadataService
import com.cortinadev.dogmatix.data.service.LibraryScanService
import com.cortinadev.dogmatix.data.service.MetaRunState
import com.cortinadev.dogmatix.data.service.MetaTargets
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A console with games on the device. */
data class MetaConsoleRow(val id: String, val name: String, val games: Int)

data class FrontendMetadataUi(
    val loading: Boolean = true,
    val consoles: List<MetaConsoleRow> = emptyList(),
    val targets: MetaTargets = MetaTargets(esdeOn = true, esdeFolderSet = false, esdeWritable = false, pegasusOn = false),
    /** Write after every download. */
    val auto: Boolean = false,
    val run: MetaRunState = MetaRunState()
)

/** Descriptions for your launcher (7.0): which frontends, the automatic write, and the runs. */
@HiltViewModel
class FrontendMetadataViewModel @Inject constructor(
    private val service: FrontendMetadataService,
    private val settings: FrontendMetadataSettings,
    private val scanService: LibraryScanService,
    settingsRepository: SettingsRepository
) : ViewModel() {

    /** Null while the library is read. */
    private val consoles = MutableStateFlow<List<MetaConsoleRow>?>(null)

    private val targets = combine(settings.esde, settings.pegasus, settingsRepository.esdeDirectory) { _, _, _ -> }
        .map { service.targets() }

    val ui: StateFlow<FrontendMetadataUi> = combine(consoles, targets, settings.autoWrite, service.state) { rows, t, auto, run ->
        FrontendMetadataUi(loading = rows == null, consoles = rows.orEmpty(), targets = t, auto = auto, run = run)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FrontendMetadataUi())

    init {
        viewModelScope.launch {
            consoles.value = runCatching {
                scanService.overview().consoles.filter { it.onDisk > 0 }.map { MetaConsoleRow(it.id, it.name, it.onDisk) }
            }.getOrDefault(emptyList())
        }
    }

    fun setEsde(on: Boolean) { viewModelScope.launch { settings.setEsde(on) } }
    fun setPegasus(on: Boolean) { viewModelScope.launch { settings.setPegasus(on) } }
    fun setAuto(on: Boolean) { viewModelScope.launch { settings.setAutoWrite(on) } }

    fun writeAll() = service.start(null)
    fun writeConsole(consoleId: String) = service.start(consoleId)
    fun stop() = service.cancel()
    fun dismiss() = service.clear()
}
