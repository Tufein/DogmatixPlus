package com.cortinadev.dogmatix.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.service.ProblemReportService
import com.cortinadev.dogmatix.util.ProblemReport
import com.cortinadev.dogmatix.util.ReportCategory
import com.cortinadev.dogmatix.util.ReportFacts
import com.cortinadev.dogmatix.util.ReportSection
import com.cortinadev.dogmatix.util.ReportText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A field the player types into; [PREVIEW] is the finished report text itself. */
enum class ReportField { WHAT, EXPECTED, STEPS, STORAGE, PREVIEW }

data class ReportProblemUi(
    val category: ReportCategory = ReportCategory.BUG,
    val text: ReportText = ReportText(),
    val sections: Set<ReportSection> = ReportSection.entries.toSet(),
    /** Details are being gathered (the local health checks may take a few seconds). */
    val collecting: Boolean = true,
    /** Gathering failed; the report holds only what the player typed. */
    val failed: Boolean = false,
    /** The cleaned report; empty until the details are in. */
    val preview: String = "",
    /** The player changed [preview] by hand (cleaned again before it leaves the screen). */
    val edited: Boolean = false
)

/**
 * "Report a problem": the player's words, the category and the switched-on sections become one
 * English report ([ProblemReport.build]) that they read, edit, copy or share. Nothing leaves the
 * device from here; the text is cleaned again ([ProblemReport.clean]) right before copy or share,
 * so hand edits cannot bring a path or a key back in.
 */
@HiltViewModel
class ReportProblemViewModel @Inject constructor(
    private val service: ProblemReportService
) : ViewModel() {
    private val _ui = MutableStateFlow(ReportProblemUi())
    val ui: StateFlow<ReportProblemUi> = _ui.asStateFlow()

    private var facts: ReportFacts? = null
    private var secrets: List<String> = emptyList()
    private var collectJob: Job? = null
    private var buildJob: Job? = null

    init { collect() }

    /** Gathers the details again (after fixing something, or when the first attempt failed). */
    fun collect() {
        collectJob?.cancel()
        _ui.update { it.copy(collecting = true, failed = false) }
        collectJob = viewModelScope.launch {
            try {
                secrets = service.secrets()
                facts = service.collect(includeHealth = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                facts = ReportFacts(generatedAt = System.currentTimeMillis())
                _ui.update { it.copy(failed = true) }
            }
            _ui.update { it.copy(collecting = false, edited = false) }
            rebuild()
        }
    }

    fun setCategory(category: ReportCategory) {
        _ui.update { it.copy(category = category, edited = false) }
        rebuild()
    }

    fun toggle(section: ReportSection) {
        _ui.update { it.copy(sections = if (section in it.sections) it.sections - section else it.sections + section, edited = false) }
        rebuild()
    }

    fun valueOf(field: ReportField): String = _ui.value.let {
        when (field) {
            ReportField.WHAT -> it.text.whatHappened
            ReportField.EXPECTED -> it.text.expected
            ReportField.STEPS -> it.text.steps
            ReportField.STORAGE -> it.text.storage
            ReportField.PREVIEW -> it.preview
        }
    }

    fun setField(field: ReportField, value: String) {
        if (field == ReportField.PREVIEW) {
            _ui.update { it.copy(preview = value.take(MAX_REPORT), edited = true) }
            return
        }
        val v = value.take(MAX_FIELD)
        _ui.update {
            val t = it.text
            it.copy(edited = false, text = when (field) {
                ReportField.WHAT -> t.copy(whatHappened = v)
                ReportField.EXPECTED -> t.copy(expected = v)
                ReportField.STEPS -> t.copy(steps = v)
                else -> t.copy(storage = v)
            })
        }
        rebuild()
    }

    /**
     * The text to copy or share, cleaned once more. When cleaning changed the player's edit, the
     * preview shows the cleaned text and [onReady] says so, so what leaves is what they saw.
     */
    fun prepare(onReady: (text: String, cleaned: Boolean) -> Unit) {
        val current = _ui.value.preview
        if (current.isBlank()) return
        viewModelScope.launch {
            val clean = withContext(Dispatchers.Default) { ProblemReport.clean(current, secrets) }
            val changed = clean != current
            if (changed) _ui.update { if (it.preview == current) it.copy(preview = clean) else it }
            onReady(clean, changed)
        }
    }

    private fun rebuild() {
        val f = facts ?: return
        val state = _ui.value
        buildJob?.cancel()
        buildJob = viewModelScope.launch {
            // Without the details, no section is written rather than empty placeholders that look like facts.
            val sections = if (state.failed) emptySet() else state.sections
            val text = withContext(Dispatchers.Default) { ProblemReport.build(f, state.text, state.category, sections, secrets) }
            _ui.update { if (it.text == state.text && it.category == state.category && it.sections == state.sections) it.copy(preview = text, edited = false) else it }
        }
    }

    private companion object {
        const val MAX_FIELD = 4_000
        const val MAX_REPORT = 40_000
    }
}
