package com.cortinadev.dogmatix.ui.screens.game

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
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.GameLaunch
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.GameEmulatorOverrides
import com.cortinadev.dogmatix.util.GameLaunchKeys

/** Saving a game exception does not launch it or replace the console preference. */
@Composable
fun GameEmulatorChoiceDialog(
    games: List<GameLaunch>,
    gameOverride: String?,
    consoleDefault: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit
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
        } },
        confirmButton = {},
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onDismiss, initialFocus = focus) }
    )
}
