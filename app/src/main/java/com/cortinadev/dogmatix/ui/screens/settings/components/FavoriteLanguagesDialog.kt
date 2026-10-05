package com.cortinadev.dogmatix.ui.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.defaultFavoriteLanguages
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus

/**
 * Picks which language tags the Library filter lists before "Show more".
 * Changes are saved on every tap, so closing the dialog is enough. Opens on the first language
 * (or on Done when there is none yet); B closes.
 */
@Composable
fun FavoriteLanguagesDialog(
    available: List<String>,
    favorites: Set<String>,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    // Favorites not (yet) in the library are still listed so they can be unticked.
    val options = (available + favorites).distinct().sorted()
    val firstFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_favorite_languages)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_favorite_languages_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (options.isEmpty()) {
                    Text(stringResource(R.string.settings_favorite_languages_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(options, key = { _, tag -> tag }) { index, tag ->
                            val checked = tag in favorites
                            LanguageRow(
                                label = tag,
                                checked = checked,
                                focus = if (index == 0) firstFocus else null
                            ) { onChange(if (checked) favorites - tag else favorites + tag) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(R.string.settings_done),
                onClick = onDismiss,
                initialFocus = if (options.isEmpty()) firstFocus else null
            )
        },
        dismissButton = {
            DialogButton(text = stringResource(R.string.settings_reset_default), onClick = { onChange(defaultFavoriteLanguages()) })
        }
    )
}

@Composable
private fun LanguageRow(label: String, checked: Boolean, focus: FocusRequester?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            // Background first, then the ring: its tonal fill and halo stay visible (no clip).
            .background(if (checked) scheme.primaryContainer else scheme.surfaceContainer, RoundedCornerShape(10.dp))
            .focusRing(source, 10.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            painterResource(if (checked) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox_off),
            contentDescription = null,
            tint = if (checked) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Text(
            label,
            style = if (checked) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            color = if (checked) scheme.onPrimaryContainer else scheme.onSurface
        )
    }
}
