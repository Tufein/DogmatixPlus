package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.service.LibraryRepairService
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.data.service.RepairOutcome
import com.tufein.valkyr.data.service.RepairProgress
import com.tufein.valkyr.data.service.RepairReportData
import com.tufein.valkyr.util.LibraryRepair
import com.tufein.valkyr.util.RepairFinding
import com.tufein.valkyr.util.RepairFix
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The route of the library repair screen (for NavRoutes, Tools and the links from Health / Recovery). */
const val LIBRARY_REPAIR_ROUTE = "library_repair"

/** What the user is asked to confirm before a fix that removes, replaces or downloads something. */
sealed interface RepairConfirm {
    data class One(val finding: RepairFinding, val fix: RepairFix) : RepairConfirm
    data class SafeAll(val items: List<Pair<RepairFinding, RepairFix>>) : RepairConfirm
}

data class LibraryRepairUi(
    val running: Boolean = false,
    val progress: RepairProgress? = null,
    val report: RepairReportData? = null,
    val failed: Boolean = false,
    val cancelled: Boolean = false,
    /** A fix is running (its finding id, or [ALL]); the fix buttons wait. */
    val fixing: String? = null
) {
    val safe: List<Pair<RepairFinding, RepairFix>> by lazy { report?.let { LibraryRepair.safeFixes(it.findings) }.orEmpty() }

    companion object { const val ALL = "*" }
}

/**
 * Tools -> Library repair. The check only starts when asked, can be stopped, and runs off the main
 * thread in [LibraryRepairService]; a fix runs only after the user picked it (and, unless it is
 * safe, confirmed the exact files). Switching profile drops the results of the other profile.
 */
@HiltViewModel
class LibraryRepairViewModel @Inject constructor(
    private val service: LibraryRepairService,
    profiles: ProfileService
) : ViewModel() {
    private val _ui = MutableStateFlow(LibraryRepairUi())
    val ui: StateFlow<LibraryRepairUi> = _ui.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            profiles.activeId.drop(1).collect {
                job?.cancel()
                _ui.value = LibraryRepairUi()
            }
        }
    }

    fun start() {
        if (_ui.value.fixing != null) return
        job?.cancel()
        job = viewModelScope.launch {
            _ui.value = LibraryRepairUi(running = true)
            try {
                val report = service.check { p -> _ui.update { if (it.running) it.copy(progress = p) else it } }
                _ui.value = LibraryRepairUi(report = report)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.value = LibraryRepairUi(failed = true)
            }
        }
    }

    /** Stops a running check; what it found so far is not shown (it would look complete). */
    fun cancel() {
        job?.cancel()
        _ui.value = LibraryRepairUi(cancelled = true)
    }

    /** Runs one fix the user picked; [onDone] gets the outcome on the main thread. */
    fun fix(finding: RepairFinding, fix: RepairFix, onDone: (RepairOutcome) -> Unit) {
        val report = _ui.value.report ?: return
        if (_ui.value.fixing != null) return
        _ui.update { it.copy(fixing = finding.id) }
        viewModelScope.launch {
            val outcome = try { service.apply(report, finding, fix) } finally { _ui.update { it.copy(fixing = null) } }
            if (outcome != RepairOutcome.FAILED) drop(setOf(finding.id))
            onDone(outcome)
        }
    }

    /** Runs the safe fixes one after the other; [onDone] gets (fixed, not fixed). */
    fun fixSafe(items: List<Pair<RepairFinding, RepairFix>>, onDone: (Int, Int) -> Unit) {
        val report = _ui.value.report ?: return
        if (_ui.value.fixing != null || items.isEmpty()) return
        _ui.update { it.copy(fixing = LibraryRepairUi.ALL) }
        viewModelScope.launch {
            val fixed = HashSet<String>()
            var failed = 0
            try {
                for ((finding, fix) in items) {
                    if (!fix.safe) continue
                    if (service.apply(report, finding, fix) == RepairOutcome.DONE) fixed += finding.id else failed++
                }
            } finally {
                _ui.update { it.copy(fixing = null) }
            }
            drop(fixed)
            onDone(fixed.size, failed)
        }
    }

    /** Takes fixed (or no longer current) findings off the list; the next check finds anything new. */
    private fun drop(ids: Set<String>) {
        if (ids.isEmpty()) return
        _ui.update { s -> s.copy(report = s.report?.let { r -> r.copy(findings = r.findings.filterNot { it.id in ids }) }) }
    }
}
