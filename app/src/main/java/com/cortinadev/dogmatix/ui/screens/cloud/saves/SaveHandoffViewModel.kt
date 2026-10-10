package com.cortinadev.dogmatix.ui.screens.cloud.saves

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.service.CloudSavesService
import com.cortinadev.dogmatix.data.service.ProfileService
import com.cortinadev.dogmatix.util.JournalKey
import com.cortinadev.dogmatix.util.SaveHandoff
import com.cortinadev.dogmatix.util.SaveHandoffPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SaveHandoffUi(val key: JournalKey? = null, val preview: SaveHandoffPreview? = null, val busy: Boolean = false, val failed: Boolean = false, val tooLarge: Boolean = false)

@HiltViewModel
class SaveHandoffViewModel @Inject constructor(private val cloud: CloudSavesService, profiles: ProfileService) : ViewModel() {
    val activeProfile = profiles.activeId
    private val _ui = MutableStateFlow(SaveHandoffUi())
    val ui = _ui.asStateFlow()
    private var job: Job? = null
    private var expiry: Job? = null
    fun show(key: JournalKey) { if (_ui.value.key == key) return; job?.cancel(); expiry?.cancel(); _ui.value = SaveHandoffUi(key) }
    fun expireReady() { _ui.value = _ui.value.copy(preview = _ui.value.preview?.copy(backupVerified = false)) }
    fun check() { val key = _ui.value.key ?: return; act(key) { cloud.previewHandoff(key) } }
    fun transfer(preview: SaveHandoffPreview) { if (_ui.value.preview != preview) return; act(preview.key) { cloud.transferHandoff(preview) } }
    private fun act(key: JournalKey, block: suspend () -> SaveHandoffPreview) {
        if (_ui.value.busy) return
        expiry?.cancel()
        _ui.value = _ui.value.copy(busy = true, failed = false, preview = null)
        job = viewModelScope.launch {
            try {
                val preview = block()
                if (_ui.value.key == key) {
                    _ui.value = SaveHandoffUi(key, preview)
                    expiry = viewModelScope.launch { delay(SaveHandoff.PREVIEW_TTL_MS); if (_ui.value.key == key) _ui.value = _ui.value.copy(preview = null) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (_ui.value.key == key) _ui.value = SaveHandoffUi(key, failed = true, tooLarge = e is SaveHandoff.SaveTooLargeException) }
        }
    }
}
