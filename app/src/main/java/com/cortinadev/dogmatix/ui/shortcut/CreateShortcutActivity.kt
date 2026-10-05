package com.cortinadev.dogmatix.ui.shortcut

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.LookSettings
import com.cortinadev.dogmatix.data.local.SettingsDataStore
import com.cortinadev.dogmatix.data.service.AppShortcutService
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.components.EmptyState
import com.cortinadev.dogmatix.ui.components.IconTile
import com.cortinadev.dogmatix.ui.components.LocalBoldFocus
import com.cortinadev.dogmatix.ui.components.NavChevron
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.ScreenTitle
import com.cortinadev.dogmatix.ui.components.SectionTitle
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.pillColors
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.theme.AccentPresets
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.ThemeMode
import com.cortinadev.dogmatix.ui.theme.consoleColor
import com.cortinadev.dogmatix.ui.theme.dogmatixBackground
import com.cortinadev.dogmatix.util.AppShortcut
import com.cortinadev.dogmatix.util.ConsoleFormatter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The "create shortcut" picker (`ACTION_CREATE_SHORTCUT`): a launcher or a frontend such as Cocoon
 * opens it when the user adds a Dogmatix+ shortcut, and gets back the chosen console, saved view or
 * the Downloads section. It wears the app's own theme and accent, and works with the D-pad.
 */
@AndroidEntryPoint
class CreateShortcutActivity : AppCompatActivity() {

    @Inject lateinit var shortcuts: AppShortcutService
    @Inject lateinit var settings: SettingsDataStore
    @Inject lateinit var look: LookSettings
    @Inject lateinit var appSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val systemAnimationsOff = runCatching {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        setContent {
            val themeName by settings.themeMode.collectAsState(initial = null)
            val accentHex by settings.accentColor.collectAsState(initial = null)
            val glow by look.glow.collectAsState(initial = true)
            val animations by look.animations.collectAsState(initial = true)
            val boldFocus by appSettings.boldFocus.collectAsState(initial = false)
            val swapFaceButtons by settings.swapFaceButtons.collectAsState(initial = false)
            // This activity can start before the main one: the pad setting is process-wide state.
            LaunchedEffect(swapFaceButtons) { Gamepad.swapFaceButtons.value = swapFaceButtons }
            val name = themeName
            val hex = accentHex
            // The settings arrive in a few milliseconds; nothing is drawn in the default look first.
            if (name != null && hex != null) {
                DogmatixTheme(themeMode = ThemeMode.fromName(name), accent = AccentPresets.fromHex(hex), glow = glow) {
                    CompositionLocalProvider(
                        LocalBoldFocus provides boldFocus,
                        LocalReduceMotion provides (!animations || systemAnimationsOff),
                        LocalContentColor provides MaterialTheme.colorScheme.onBackground
                    ) {
                        var options by remember { mutableStateOf<List<AppShortcut>?>(null) }
                        LaunchedEffect(Unit) { options = runCatching { shortcuts.available() }.getOrDefault(emptyList()) }
                        ShortcutPicker(options, onPick = { choose(it) }, onClose = { finish() })
                    }
                }
            }
        }
    }

    private fun choose(option: AppShortcut) {
        setResult(Activity.RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(this, shortcuts.shortcutFor(option)))
        finish()
    }
}

/** The groups the picker lists, in the order [com.cortinadev.dogmatix.util.AppShortcuts.plan] makes them. */
private enum class Kind(val label: Int, val icon: Int) {
    SECTION(R.string.shortcut_group_sections, R.drawable.ic_download),
    VIEW(R.string.shortcut_group_views, R.drawable.ic_bookmark),
    CONSOLE(R.string.shortcut_group_consoles, R.drawable.ic_controller)
}

private fun kindOf(option: AppShortcut): Kind = when {
    option.id.startsWith("console:") -> Kind.CONSOLE
    option.route != null || option.id.startsWith("section:") -> Kind.SECTION
    else -> Kind.VIEW
}

@Composable
private fun ShortcutPicker(options: List<AppShortcut>?, onPick: (AppShortcut) -> Unit, onClose: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    // Focus lands on the first row once the list is there, so the D-pad works at once.
    LaunchedEffect(options) {
        if (options.isNullOrEmpty()) return@LaunchedEffect
        repeat(8) {
            withFrameNanos { }
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .dogmatixBackground()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .closeOnGamepadB(onClose),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            ScreenTitle(
                text = stringResource(R.string.shortcut_pick_title),
                subtitle = stringResource(R.string.shortcut_pick_subtitle),
                icon = R.drawable.ic_link,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
                trailing = { CloseButton(onClose) }
            )
            when {
                options == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp)
                        Text(stringResource(R.string.shortcut_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                options.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = stringResource(R.string.shortcut_empty_title),
                        message = stringResource(R.string.shortcut_empty_message),
                        icon = R.drawable.ic_link
                    )
                }
                else -> {
                    val groups = remember(options) { options.groupBy { kindOf(it) } }
                    val firstId = options.first().id
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        groups.forEach { (kind, rows) ->
                            item(key = "group:${kind.name}") {
                                SectionTitle(stringResource(kind.label), icon = kind.icon, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                            }
                            items(rows, key = { it.id }) { option ->
                                ShortcutRow(
                                    option = option,
                                    kind = kind,
                                    focus = if (option.id == firstId) firstFocus else null,
                                    onClick = { onPick(option) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A round close button for touch (the gamepad's B and the system back do the same). */
@Composable
private fun CloseButton(onClick: () -> Unit) {
    val source = rememberFocusSource()
    Box(
        modifier = Modifier
            .size(44.dp)
            .focusRing(source, cornerRadius = 22.dp)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_close),
            contentDescription = stringResource(R.string.shortcut_close),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** One choice: its icon (in the console's colour for a console), its name and, for a console, its short name. */
@Composable
private fun ShortcutRow(option: AppShortcut, kind: Kind, focus: FocusRequester?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(12.dp)
    val hairline = LocalDogmatixTokens.current.hairline
    val consoleId = option.consoleId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .background(scheme.surfaceContainer, shape)
            .border(1.dp, hairline, shape)
            .focusRing(source, cornerRadius = 12.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (consoleId != null) {
            val (container, tint) = pillColors(PillTone.Tint(consoleColor(consoleId)))
            IconTile(kind.icon, size = 36.dp, container = container, tint = tint)
        } else {
            IconTile(kind.icon, size = 36.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(option.label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (consoleId != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(consoleColor(consoleId), CircleShape))
                    Text(
                        ConsoleFormatter.getConsoleShortName(consoleId),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        NavChevron()
    }
}
