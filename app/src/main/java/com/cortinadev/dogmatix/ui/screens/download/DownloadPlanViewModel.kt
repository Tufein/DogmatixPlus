package com.cortinadev.dogmatix.ui.screens.download

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.DownloadPlanPreview
import com.cortinadev.dogmatix.data.service.DownloadPlanService
import com.cortinadev.dogmatix.util.DownloadPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadPlanUi(
    val busy: Boolean = false,
    val preview: DownloadPlanPreview? = null,
    val error: Int? = null,
    val imported: Int? = null,
    val saved: Boolean = false
)

@HiltViewModel
class DownloadPlanViewModel @Inject constructor(private val service: DownloadPlanService) : ViewModel() {
    private val _ui = MutableStateFlow(DownloadPlanUi())
    val ui = _ui.asStateFlow()
    private var pendingExport: DownloadPlan? = null

    fun exportFile(selected: Set<String>, onReady: () -> Unit) = operate(R.string.plan26_export_failed) {
        pendingExport = service.export(selected)
        onReady()
    }

    fun savePending(uri: Uri?) {
        val plan = pendingExport
        pendingExport = null
        if (uri == null || plan == null) return
        operate(R.string.plan26_export_failed) {
            service.save(plan, uri)
            _ui.value = _ui.value.copy(saved = true)
        }
    }

    fun share(selected: Set<String>, onReady: (Uri) -> Unit) = operate(R.string.plan26_export_failed) {
        val plan = service.export(selected)
        onReady(service.shareUri(plan))
    }

    fun read(uri: Uri) = operate(R.string.plan26_import_failed) {
        _ui.value = _ui.value.copy(preview = service.read(uri))
    }

    fun confirm() {
        val plan = _ui.value.preview?.plan ?: return
        operate(R.string.plan26_import_failed) {
            val reviewed = service.confirm(plan)
            _ui.value = _ui.value.copy(preview = null, imported = reviewed.readyCount)
        }
    }

    fun dismissPreview() { if (!_ui.value.busy) _ui.value = _ui.value.copy(preview = null) }
    fun clearMessage() { _ui.value = _ui.value.copy(error = null, imported = null, saved = false) }

    private fun operate(error: Int, work: suspend () -> Unit) {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, error = null, imported = null, saved = false)
        viewModelScope.launch {
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _ui.value = _ui.value.copy(error = error) }
            finally { _ui.value = _ui.value.copy(busy = false) }
        }
    }
}
