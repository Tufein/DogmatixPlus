package com.cortinadev.dogmatix.ui.screens.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.SectionTitle
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.pillColors
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.screens.settings.SettingsIconTile
import com.cortinadev.dogmatix.ui.screens.settings.SettingsTileGap
import com.cortinadev.dogmatix.ui.screens.settings.settingsInset
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens

/*
 * Building blocks of the cloud backup screen (own file, own names, so the screen does not depend on
 * the Settings screen's private rows): a section = title + panel; a row = a focusable line with a
 * title, a hint and something at the end; a note = a tinted, unfocusable line.
 */

/** A titled group of rows on a panel. [action] sits at the end of the title line. */
@Composable
internal fun DavSection(
    title: String,
    icon: Int,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SettingsTileGap)
        ) {
            SettingsIconTile(icon)
            SectionTitle(title, modifier = Modifier.weight(1f))
            action?.invoke()
        }
        Spacer(Modifier.height(8.dp))
        Panel(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}

/**
 * A focusable line: [title], an optional [hint] under it, and [trailing] at the end (a pill, a
 * switch, a stepper). A and a tap run [onClick]; ◀ ▶ on a focused row run [onAdjust] when given.
 */
@Composable
internal fun DavRow(
    title: String,
    onClick: () -> Unit,
    icon: Int,
    modifier: Modifier = Modifier,
    hint: String? = null,
    hintColor: Color = Color.Unspecified,
    onAdjust: ((Int) -> Unit)? = null,
    trailing: @Composable () -> Unit = {}
) {
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .focusRing(source, cornerRadius = 10.dp)
            .onPreviewKeyEvent { event ->
                if (onAdjust == null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onAdjust(-1); true }
                    Key.DirectionRight -> { onAdjust(1); true }
                    else -> false
                }
            }
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = settingsInset(), vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsTileGap)
    ) {
        SettingsIconTile(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!hint.isNullOrEmpty()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hintColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else hintColor,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        trailing()
    }
}

/** A tinted line of explanation (not focusable): [icon], [text], in the colours of [tone]. */
@Composable
internal fun DavNote(text: String, icon: Int, modifier: Modifier = Modifier, tone: PillTone = PillTone.Neutral) {
    val (bg, fg) = pillColors(tone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = fg, modifier = Modifier.weight(1f))
    }
}

/** The app's switch look (accent track, neutral knob), for the end of a [DavRow]. */
@Composable
internal fun DavSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = scheme.surface,
            checkedTrackColor = scheme.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = scheme.surface,
            uncheckedTrackColor = LocalDogmatixTokens.current.knobOff,
            uncheckedBorderColor = Color.Transparent
        )
    )
}
