package com.cortinadev.dogmatix.data.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How far the running source scan is: [done] of [total] sources (a console without enabled
 * sources counts as one step, so it still moves the bar). [startedAt] feeds the time estimate.
 */
data class ScanProgress(val done: Int, val total: Int, val startedAt: Long) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val percent: Int get() = (fraction * 100).toInt()

    /** Milliseconds still to go, extrapolated from the pace so far; null before the first step. */
    fun remainingMillis(now: Long = System.currentTimeMillis()): Long? {
        if (done <= 0 || done >= total) return null
        return (now - startedAt) * (total - done) / done
    }
}

@Singleton
class RescanStateHolder @Inject constructor() {
    private val _isRescanning = MutableStateFlow(false)
    val isRescanning: StateFlow<Boolean> = _isRescanning.asStateFlow()

    private val _lastRescanTime = MutableStateFlow(0L)
    val lastRescanTime: StateFlow<Long> = _lastRescanTime.asStateFlow()

    private val _progressMessage = MutableStateFlow("")
    val progressMessage: StateFlow<String> = _progressMessage.asStateFlow()

    private val _torrentFetchProgress = MutableStateFlow("")
    val torrentFetchProgress: StateFlow<String> = _torrentFetchProgress.asStateFlow()

    private val _progress = MutableStateFlow<ScanProgress?>(null)
    /** Null while no scan runs (or one has not counted its sources yet). */
    val progress: StateFlow<ScanProgress?> = _progress.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun setRescanning(value: Boolean) { 
        _isRescanning.value = value 
        if (!value) {
            _lastRescanTime.value = System.currentTimeMillis()
            _progress.value = null
        }
    }
    fun startProgress(total: Int) { _progress.value = ScanProgress(0, total.coerceAtLeast(1), System.currentTimeMillis()) }
    fun advanceProgress() { _progress.update { it?.copy(done = (it.done + 1).coerceAtMost(it.total)) } }
    fun setProgressMessage(message: String) { _progressMessage.value = message }
    fun clearProgressMessage() { _progressMessage.value = "" }
    fun setTorrentFetchProgress(message: String) { _torrentFetchProgress.value = message }
    fun clearTorrentFetchProgress() { _torrentFetchProgress.value = "" }
    fun setErrorMessage(message: String?) { _errorMessage.value = message }

    /** Sources that failed in the running scan; shown as one report when it ends. */
    private val failures = java.util.Collections.synchronizedList(mutableListOf<com.cortinadev.dogmatix.util.ScanFailure>())
    private val _scanReport = MutableStateFlow<List<com.cortinadev.dogmatix.util.ScanFailure>?>(null)
    /** Non-null after a scan in which some sources failed (one app-wide report, whichever screen started it). */
    val scanReport: StateFlow<List<com.cortinadev.dogmatix.util.ScanFailure>?> = _scanReport.asStateFlow()

    fun beginScanReport() { failures.clear() }
    fun addFailure(failure: com.cortinadev.dogmatix.util.ScanFailure) { failures += failure }
    fun publishScanReport() {
        val failed = synchronized(failures) { failures.toList() }
        if (failed.isNotEmpty()) _scanReport.value = failed
    }
    fun dismissScanReport() { _scanReport.value = null }
}
