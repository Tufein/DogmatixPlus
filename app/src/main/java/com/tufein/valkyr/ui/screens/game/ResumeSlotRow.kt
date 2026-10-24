package com.tufein.valkyr.ui.screens.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.util.EmulatorCatalog

/**
 * "Resume from save slot": one button that steps off, 1, 2 ... [max], off. Only shown for an
 * emulator that takes a slot at launch (DukeStation); the choice is kept per game by the caller.
 */
@Composable
fun ResumeSlotRow(slot: Int?, max: Int, onChange: (Int?) -> Unit) {
    if (max < 1) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val value = slot?.let { stringResource(R.string.duke55_slot_value, it) } ?: stringResource(R.string.duke55_slot_off)
        ActionPill(stringResource(R.string.duke55_slot_label, value), { onChange(EmulatorCatalog.nextResumeSlot(slot, max)) }, icon = R.drawable.ic_sync)
        Text(stringResource(R.string.duke55_slot_hint, max), style = MaterialTheme.typography.bodySmall)
    }
}
