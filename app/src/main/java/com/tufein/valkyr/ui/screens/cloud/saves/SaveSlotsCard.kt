package com.tufein.valkyr.ui.screens.cloud.saves

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.util.LocalSaveFile
import com.tufein.valkyr.util.SaveSlots

/** A slot picked to be copied, and (once chosen) where it goes. */
private data class SlotCopy(val group: SaveSlots.Group, val source: SaveSlots.Slot, val target: SaveSlots.Target? = null)

/**
 * The save slots of a game on one card (several states: `Game.state1` … `Game.stateN`): each slot with its
 * time and size, and "Copy to slot…" which asks for the slot, shows what would be replaced and only then
 * copies (the slot it replaces is backed up first; the next sync sends the change on). Shows nothing for a
 * game with fewer than two slots.
 */
@Composable
internal fun SaveSlotsCard(
    files: List<LocalSaveFile>,
    enabled: Boolean,
    workingId: String?,
    onCopy: (source: LocalSaveFile, targetPath: String, expectedTarget: Long?) -> Unit
) {
    val groups = remember(files) { SaveSlots.groups(files) }
    if (groups.isEmpty()) return
    var pending by remember(files) { mutableStateOf<SlotCopy?>(null) }
    GroupLabel(stringResource(R.string.sv55_slots_group))
    InfoLine(stringResource(R.string.sv55_slots_hint))
    groups.forEach { group ->
        if (groups.size > 1) InfoLine(group.base.substringAfterLast('/'))
        group.slots.forEach { slot ->
            val canCopy = SaveSlots.targets(group, slot).isNotEmpty()
            val detail = listOfNotNull(relativeTime(slot.file.modified), slot.file.size.takeIf { it > 0 }?.let { formatBytes(it) }).joinToString(" · ")
            SaveRow(
                title = slotLabel(slot.index),
                detail = detail,
                actionLabel = if (canCopy) stringResource(R.string.sv55_slot_copy) else null,
                actionIcon = R.drawable.ic_content_copy,
                working = workingId == CloudSavesViewModel.rowId(slot.file),
                enabled = enabled,
                onClick = { pending = SlotCopy(group, slot) },
                leading = { IconTile(R.drawable.ic_bookmark, size = 34.dp) }
            )
        }
    }
    pending?.let { p ->
        SlotCopyDialog(
            copy = p,
            onChoose = { target -> pending = p.copy(target = target) },
            onBack = { pending = p.copy(target = null) },
            onConfirm = { target ->
                pending = null
                onCopy(p.source.file, target.path, target.existing?.modified)
            },
            onDismiss = { pending = null }
        )
    }
}

@Composable
private fun slotLabel(index: Int?): String =
    if (index == null) stringResource(R.string.sv55_slot_auto) else stringResource(R.string.sv55_slot_label, index)

/** Step one: which slot to copy into. Step two: the plan in words, then the copy. */
@Composable
private fun SlotCopyDialog(
    copy: SlotCopy,
    onChoose: (SaveSlots.Target) -> Unit,
    onBack: () -> Unit,
    onConfirm: (SaveSlots.Target) -> Unit,
    onDismiss: () -> Unit
) {
    val target = copy.target
    val sourceLabel = slotLabel(copy.source.index)
    val firstFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(if (target == null) onDismiss else onBack),
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null) },
        title = { Text(stringResource(if (target == null) R.string.sv55_slot_pick_title else R.string.sv55_slot_preview_title, sourceLabel)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())
            ) {
                if (target == null) {
                    SaveSlots.targets(copy.group, copy.source).forEach { option ->
                        val source = rememberFocusSource()
                        val existing = option.existing
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .focusRing(source)
                                .clickable(interactionSource = source, indication = null) { onChoose(option) }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Text(
                                if (existing == null) stringResource(R.string.sv55_slot_new, option.index) else slotLabel(option.index),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (existing != null) {
                                Text(
                                    listOfNotNull(relativeTime(existing.modified), existing.size.takeIf { it > 0 }?.let { formatBytes(it) }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    val at = relativeTime(copy.source.file.modified).orEmpty()
                    val size = formatBytes(copy.source.file.size)
                    val replaced = target.existing
                    Text(
                        if (replaced == null) stringResource(R.string.sv55_slot_preview_new, sourceLabel, at, size, target.path.substringAfterLast('/'))
                        else stringResource(
                            R.string.sv55_slot_preview_replace, sourceLabel, at, size,
                            slotLabel(target.index), relativeTime(replaced.modified).orEmpty(), formatBytes(replaced.size)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            if (target != null) DialogButton(stringResource(R.string.sv55_slot_copy_confirm), onClick = { onConfirm(target) })
            else DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = firstFocus)
        },
        // Cancel has the first focus on the preview: two quick presses of A never copy over a slot by accident.
        dismissButton = {
            if (target != null) DialogButton(stringResource(R.string.sv55_slot_back), onClick = onBack, initialFocus = firstFocus)
        }
    )
}
