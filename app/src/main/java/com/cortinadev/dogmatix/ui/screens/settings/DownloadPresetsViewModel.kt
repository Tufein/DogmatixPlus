package com.cortinadev.dogmatix.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.DownloadPresetSnapshot
import com.cortinadev.dogmatix.data.local.DownloadPresetStore
import com.cortinadev.dogmatix.util.DownloadPreset
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadPresetsViewModel @Inject constructor(private val store: DownloadPresetStore) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<Int?>(null)
    val message = _message.asStateFlow()
    val snapshot: StateFlow<DownloadPresetSnapshot?> = store.snapshot
        .catch { error ->
            if (error is CancellationException) throw error
            _message.value = R.string.presets26_failed
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun apply(id: String) = perform(R.string.presets26_applied) { store.apply(id) }
    fun delete(id: String) = perform(R.string.presets26_deleted) { store.delete(id) }
    fun save(preset: DownloadPreset, onSaved: () -> Unit) = perform(R.string.presets26_saved) {
        store.save(preset)
        onSaved()
    }

    private fun perform(success: Int, action: suspend () -> Unit) {
        if (!_busy.compareAndSet(false, true)) return
        _message.value = null
        viewModelScope.launch {
            try {
                action()
                _message.value = success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = R.string.presets26_failed
            } finally { _busy.value = false }
        }
    }
}
