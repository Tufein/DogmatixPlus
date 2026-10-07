package com.cortinadev.dogmatix.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.local.VersionPreferenceSettings
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.util.ConsoleOverride
import com.cortinadev.dogmatix.util.VersionCompare
import com.cortinadev.dogmatix.util.VersionPreference
import com.cortinadev.dogmatix.util.VersionPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** A console in the picker. */
data class ConsoleChoice(val id: String, val name: String)

/**
 * What the Version preference screen shows. [preference] is what applies to [consoleId] (null =
 * all consoles); [ranked] / [explanation] are the sample game ranked by it.
 */
data class VersionPreferenceUi(
    val consoleId: String? = null,
    val consoles: List<ConsoleChoice> = emptyList(),
    val preference: VersionPreference = VersionPreference(),
    /** A preference is pinned for all consoles (otherwise the default from the favourite languages applies). */
    val pinned: Boolean = false,
    /** [consoleId] has an order of its own. */
    val consoleOwn: Boolean = false,
    val ranked: List<VersionCompare.Ranked> = emptyList(),
    val explanation: VersionCompare.Explanation? = null,
    val loaded: Boolean = false
)

@HiltViewModel
class VersionPreferenceViewModel @Inject constructor(
    private val settings: VersionPreferenceSettings,
    consoleRepository: ConsoleRepository
) : ViewModel() {

    private val scope = MutableStateFlow<String?>(null)
    private val writes = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    val ui: StateFlow<VersionPreferenceUi> = scope.flatMapLatest { id ->
        combine(
            settings.effectiveFlow(id), settings.pinned, settings.overrides,
            consoleRepository.getAllConsoles().map { rows -> rows.map { ConsoleChoice(it.id, it.name.ifBlank { it.id }) }.sortedBy { it.name.lowercase() } }
        ) { effective, pinned, overrides, consoles ->
            val ranked = VersionCompare.rank(VersionPreferences.SAMPLE_GAME, effective)
            VersionPreferenceUi(
                consoleId = id, consoles = consoles, preference = effective, pinned = pinned != null,
                consoleOwn = id != null && overrides[id]?.isEmpty == false,
                ranked = ranked, explanation = VersionCompare.explainBest(ranked), loaded = true
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VersionPreferenceUi())

    /** null = all consoles. */
    fun selectScope(consoleId: String?) { scope.value = consoleId }

    /**
     * Changes what applies to the chosen scope, on the values stored now (so a quick second press
     * works on the result of the first): the pinned preference for all consoles, or, for one console,
     * only the list that changed in its own order (the rules always stay global).
     */
    fun edit(change: (VersionPreference) -> VersionPreference) {
        viewModelScope.launch {
            writes.withLock {
                val id = scope.value
                if (id == null) {
                    settings.pin(change(settings.global.first()))
                } else {
                    val base = settings.effective(id)
                    val next = change(base)
                    val own = settings.overrides.first()[id]
                    settings.setOverride(
                        id,
                        ConsoleOverride(
                            regions = if (next.regions != base.regions) next.regions else own?.regions,
                            languages = if (next.languages != base.languages) next.languages else own?.languages
                        )
                    )
                }
            }
        }
    }

    /** All consoles: back to the default from the favourite languages; one console: follow the order for all consoles again. */
    fun reset() {
        viewModelScope.launch {
            writes.withLock {
                val id = scope.value
                if (id == null) settings.clearPinned() else settings.setOverride(id, null)
            }
        }
    }
}
