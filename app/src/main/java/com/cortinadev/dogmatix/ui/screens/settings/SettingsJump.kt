package com.cortinadev.dogmatix.ui.screens.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * A Settings row to bring into view (8.0, "search everything"): the search screen leaves the row's
 * key (see [com.cortinadev.dogmatix.util.SettingKeys]) here and opens Settings, which scrolls to
 * that row, focuses it and lights it up briefly.
 */
object SettingsJump {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun request(rowKey: String) { _pending.value = rowKey }

    fun consume(): String? = _pending.getAndUpdate { null }
}
