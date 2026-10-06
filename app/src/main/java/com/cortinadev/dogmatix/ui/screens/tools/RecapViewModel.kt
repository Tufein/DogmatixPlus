package com.cortinadev.dogmatix.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.service.RecapService
import com.cortinadev.dogmatix.util.Recap
import com.cortinadev.dogmatix.util.RecapInput
import com.cortinadev.dogmatix.util.RecapPeriod
import com.cortinadev.dogmatix.util.YearRecap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

/** What the recap screen shows. */
data class RecapUi(
    val loading: Boolean = true,
    /** The years the selector offers, newest (the current one) first; "Last 12 months" comes after them. */
    val years: List<Int> = emptyList(),
    val period: RecapPeriod? = null,
    val recap: YearRecap? = null,
    /** Whether anything at all was recorded, whatever the period. */
    val hasAny: Boolean = false
)

@HiltViewModel
class RecapViewModel @Inject constructor(private val service: RecapService) : ViewModel() {

    private val input = MutableStateFlow<RecapInput?>(null)
    /** Null until the user picks one: the current year. */
    private val chosen = MutableStateFlow<RecapPeriod?>(null)

    init { viewModelScope.launch { input.value = service.load() } }

    fun select(period: RecapPeriod) { chosen.value = period }

    val ui: StateFlow<RecapUi> = combine(input, chosen) { data, pick ->
        if (data == null) return@combine RecapUi()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val years = Recap.years(data, now, zone)
        val period = pick ?: RecapPeriod.Year(years.first())
        RecapUi(
            loading = false, years = years, period = period, recap = Recap.build(data, period, now, zone),
            hasAny = data.downloads.isNotEmpty() || data.log.isNotEmpty() || !data.plays.isNullOrEmpty()
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecapUi())
}
