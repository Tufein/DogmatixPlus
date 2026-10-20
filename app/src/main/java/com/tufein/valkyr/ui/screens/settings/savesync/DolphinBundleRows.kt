package com.tufein.valkyr.ui.screens.settings.savesync

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.screens.settings.SettingRow
import com.tufein.valkyr.ui.screens.settings.ThemedSwitch
import com.tufein.valkyr.util.DolphinBundleKind
import com.tufein.valkyr.util.EmulatorSaveFolder
import com.tufein.valkyr.util.SaveFolderLayout

/**
 * The rows of a Dolphin folder for its opt-in bundles (Settings → Save sync): GCI folders, Wii saves
 * and, once Wii saves are on, the channels discs install. Same pattern as the shared memory cards:
 * off until the user picks a RomM game to keep them with; texts in full.
 */
internal fun dolphinBundleRows(
    folder: EmulatorSaveFolder,
    onChoose: (DolphinBundleKind) -> Unit,
    onChannels: (Boolean) -> Unit
): List<@Composable () -> Unit> {
    if (folder.layout != SaveFolderLayout.GC_MEMCARDS) return emptyList()
    return buildList {
        add {
            val on = folder.gciRomId != null
            SettingRow(
                icon = R.drawable.ic_save,
                title = stringResource(R.string.saves37_gci_title),
                hint = if (on) stringResource(R.string.saves37_gci_on, folder.gciRomName) else stringResource(R.string.saves37_gci_off),
                onClick = { onChoose(DolphinBundleKind.GCI) },
            ) {
                ActionPill(
                    stringResource(if (on) R.string.saves31_shared_change else R.string.saves31_shared_choose),
                    { onChoose(DolphinBundleKind.GCI) },
                    icon = R.drawable.ic_settings
                )
            }
        }
        add {
            val on = folder.wiiRomId != null
            SettingRow(
                icon = R.drawable.ic_save,
                title = stringResource(R.string.saves37_wii_title),
                hint = if (on) stringResource(R.string.saves37_wii_on, folder.wiiRomName) else stringResource(R.string.saves37_wii_off),
                onClick = { onChoose(DolphinBundleKind.WII) },
            ) {
                ActionPill(
                    stringResource(if (on) R.string.saves31_shared_change else R.string.saves31_shared_choose),
                    { onChoose(DolphinBundleKind.WII) },
                    icon = R.drawable.ic_settings
                )
            }
        }
        if (folder.wiiRomId != null) add {
            SettingRow(
                icon = R.drawable.ic_save,
                title = stringResource(R.string.saves37_wii_channels),
                hint = stringResource(R.string.saves37_wii_channels_hint),
                onClick = { onChannels(!folder.wiiChannels) },
                onAdjust = { onChannels(it > 0) },
            ) { ThemedSwitch(folder.wiiChannels) { onChannels(it) } }
        }
    }
}

/**
 * Turns syncing of a Dolphin folder's GCI folders or Wii saves on by choosing the RomM game the
 * bundles are kept with, changes that game, or turns it off. Nothing on the device is touched here.
 */
@Composable
internal fun DolphinBundleDialog(folder: EmulatorSaveFolder, kind: DolphinBundleKind, viewModel: SaveSyncViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val results by viewModel.gameResults.collectAsState()
    val searching by viewModel.searching.collectAsState()
    var term by remember { mutableStateOf("") }
    val fieldFocus = rememberInitialFocus()
    val gci = kind == DolphinBundleKind.GCI
    val chosen = if (gci) folder.gciRomId else folder.wiiRomId
    val chosenName = if (gci) folder.gciRomName else folder.wiiRomName
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDone),
        onDismissRequest = onDone,
        title = { Text(stringResource(if (gci) R.string.saves37_gci_dialog_title else R.string.saves37_wii_dialog_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(if (gci) R.string.saves37_gci_dialog_hint else R.string.saves37_wii_dialog_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(stringResource(R.string.saves37_bundle_whole), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (chosen != null) {
                    Text(stringResource(if (gci) R.string.saves37_gci_on else R.string.saves37_wii_on, chosenName), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text(stringResource(R.string.saves31_shared_search_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                )
                ActionPill(stringResource(R.string.saves31_shared_search), { if (term.isNotBlank()) viewModel.searchGames(context, term) }, icon = R.drawable.ic_search, enabled = !searching)
                results?.let { found ->
                    if (found.isEmpty() && !searching) Text(stringResource(R.string.saves31_shared_none), style = MaterialTheme.typography.bodySmall)
                    found.forEach { rom ->
                        val source = rememberFocusSource()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .focusRing(source)
                                .clickable(interactionSource = source, indication = null) { viewModel.setDolphinBundles(context, folder.label, kind, rom); onDone() }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Text(rom.name.ifBlank { rom.fsName }, style = MaterialTheme.typography.bodyLarge)
                            Text(listOf(rom.fsName, rom.platformSlug).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDone) },
        dismissButton = {
            if (chosen != null) DialogButton(text = stringResource(R.string.saves31_shared_stop), onClick = { viewModel.setDolphinBundles(context, folder.label, kind, null); onDone() })
        }
    )
}
