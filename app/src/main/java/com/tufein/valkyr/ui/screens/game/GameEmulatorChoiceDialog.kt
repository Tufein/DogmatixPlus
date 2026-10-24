package com.tufein.valkyr.ui.screens.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.GameLaunch
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.EmulatorCatalog
import com.tufein.valkyr.util.GameEmulatorOverrides
import com.tufein.valkyr.util.GameLaunchKeys

/** Saving a game exception does not launch it or replace the console preference. */
@Composable
fun GameEmulatorChoiceDialog(
    games: List<GameLaunch>,
    gameOverride: String?,
    consoleDefault: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    resumeSlot: Int? = null,
    onResumeSlot: ((Int?) -> Unit)? = null
) {
    val focus = rememberInitialFocus()
    val handlers = games.flatMap { it.handlers }.distinctBy { it.key }
    val resolved = GameEmulatorOverrides.resolve(gameOverride, consoleDefault, handlers, { it.key }, { it.packageName })
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss), onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.play26_game_title)) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.play26_game_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.play26_effective, resolved.handler?.label ?: stringResource(R.string.play24_settings_ask)))
            if (resolved.missingGameOverride) Text(stringResource(R.string.play26_missing), color = MaterialTheme.colorScheme.error)
            ActionPill(stringResource(R.string.play26_use_console), { onPick(null) }, icon = R.drawable.ic_settings)
            if (handlers.isEmpty()) Text(stringResource(R.string.play_no_handler))
            handlers.firstOrNull()?.let { first ->
                ActionPill(stringResource(R.string.play24_automatic, first.label), { onPick(GameLaunchKeys.AUTOMATIC) },
                    icon = if (gameOverride == GameLaunchKeys.AUTOMATIC) R.drawable.ic_check else R.drawable.ic_controller)
            }
            handlers.forEach { handler ->
                ActionPill(handler.label, { onPick(handler.key) }, icon = if (gameOverride == handler.key) R.drawable.ic_check else R.drawable.ic_controller)
            }
            if (handlers.any { it.core != null }) Text(stringResource(R.string.play24_core_hint), style = MaterialTheme.typography.bodySmall)
            if (onResumeSlot != null) ResumeSlotRow(resumeSlot, EmulatorCatalog.maxResumeSlot(handlers.map { it.emulatorId }), onResumeSlot)
        } },
        confirmButton = {},
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onDismiss, initialFocus = focus) }
    )
}
