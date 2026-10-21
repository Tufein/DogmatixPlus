package com.tufein.valkyr.ui.screens.settings.covers

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.CoverSourceSettings
import com.tufein.valkyr.data.service.CoverSourceStats
import com.tufein.valkyr.data.service.ScreenScraperService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import com.tufein.valkyr.ui.screens.settings.SettingRow
import com.tufein.valkyr.ui.screens.settings.SettingsCardHeader
import com.tufein.valkyr.ui.screens.settings.SettingsCardsGrid
import com.tufein.valkyr.ui.screens.settings.SettingsSurface
import com.tufein.valkyr.ui.screens.settings.ThemedSwitch
import com.tufein.valkyr.ui.screens.tools.PublishLegend
import com.tufein.valkyr.util.CardGrid
import com.tufein.valkyr.util.CoverSources
import com.tufein.valkyr.util.CoverSources.Source
import com.tufein.valkyr.util.ScreenScraper

/** Where a developer id is asked for, opened from the explanation row. */
private const val SCREENSCRAPER_SITE = "https://www.screenscraper.fr/"

/**
 * Settings → Cover sources (4.3.0): the order of the cover sources with a switch each (RomM stays
 * first), title screens and snaps as a fallback, the ScreenScraper login with "Test login", what
 * every source did today, and "find missing covers again". One column of settings cards; every
 * control works from the D-pad (move up / down buttons, no dragging) and no text is cut off.
 */
@Composable
fun CoverSourcesScreen(viewModel: CoverSourcesViewModel = hiltViewModel()) {
    val chain = viewModel.chain.collectAsState().value ?: CoverSources.Chain()
    val login by viewModel.login.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val testing by viewModel.testing.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val retried by viewModel.retried.collectAsState()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val today = ScreenScraper.dayOf(System.currentTimeMillis())
    var editing by rememberSaveable { mutableStateOf<CoverSourceSettings.Field?>(null) }

    PublishLegend(listOf(LegendEntry("A", stringResource(R.string.pad_change)), LegendEntry("B", stringResource(R.string.pad_back))))

    val sources = listOf(Source.ROMM) + chain.order
    val orderRows: List<@Composable () -> Unit> = buildList {
        sources.forEachIndexed { index, source ->
            add {
                val movable = source != Source.ROMM
                val place = index - 1 // place among the movable sources
                SourceRow(
                    position = index + 1,
                    source = source,
                    hint = sourceHint(source, chain, login, stats),
                    enabled = chain.enabled(source),
                    canUp = movable && place > 0,
                    canDown = movable && place < chain.order.lastIndex,
                    onToggle = { viewModel.setEnabled(source, it) },
                    onUp = { viewModel.move(source, -1) },
                    onDown = { viewModel.move(source, 1) }
                )
            }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_image,
                title = stringResource(R.string.covers43_non_box),
                hint = stringResource(R.string.covers43_non_box_hint),
                onClick = { viewModel.setNonBoxArt(!chain.nonBoxArt) },
                onAdjust = { viewModel.setNonBoxArt(it > 0) }
            ) { ThemedSwitch(chain.nonBoxArt) { viewModel.setNonBoxArt(it) } }
        }
    }

    val loginRows: List<@Composable () -> Unit> = listOf(
        {
            SettingRow(
                icon = R.drawable.ic_info,
                title = stringResource(R.string.covers43_ss_where_title),
                hint = stringResource(R.string.covers43_ss_where_hint),
                onClick = { runCatching { uri.openUri(SCREENSCRAPER_SITE) } }
            ) {
                ActionPill(stringResource(R.string.covers43_ss_open_site), { runCatching { uri.openUri(SCREENSCRAPER_SITE) } }, icon = R.drawable.ic_open_in_new)
            }
        },
        { CredentialRow(R.drawable.ic_key, R.string.covers43_ss_dev_id, login.devId.ifBlank { stringResource(R.string.settings_not_set) }) { editing = CoverSourceSettings.Field.DEV_ID } },
        { CredentialRow(R.drawable.ic_lock, R.string.covers43_ss_dev_password, secretState(login.devPassword)) { editing = CoverSourceSettings.Field.DEV_PASSWORD } },
        {
            CredentialRow(
                R.drawable.ic_label, R.string.covers43_ss_soft_name,
                login.softName.ifBlank { stringResource(R.string.covers43_ss_soft_name_default, ScreenScraper.DEFAULT_SOFT_NAME) }
            ) { editing = CoverSourceSettings.Field.SOFT_NAME }
        },
        { CredentialRow(R.drawable.ic_account, R.string.covers43_ss_user, login.user.ifBlank { stringResource(R.string.covers43_ss_user_none) }) { editing = CoverSourceSettings.Field.USER } },
        { CredentialRow(R.drawable.ic_lock, R.string.covers43_ss_password, secretState(login.password)) { editing = CoverSourceSettings.Field.PASSWORD } },
        {
            val result = testResult
            SettingRow(
                icon = R.drawable.ic_network_check,
                title = stringResource(R.string.covers43_ss_test),
                hint = when {
                    testing -> stringResource(R.string.covers43_ss_testing)
                    result != null -> loginResultText(result)
                    else -> stringResource(R.string.covers43_ss_test_hint)
                },
                hintColor = when (result) {
                    is ScreenScraperService.LoginResult.LoggedIn, ScreenScraperService.LoginResult.DeveloperOnly, null -> null
                    else -> MaterialTheme.colorScheme.error
                },
                onClick = viewModel::testLogin
            ) {
                ActionPill(
                    stringResource(if (testing) R.string.covers43_ss_testing_short else R.string.settings_test),
                    viewModel::testLogin,
                    icon = R.drawable.ic_sync,
                    tone = if (login.ready && result == null) ActionTone.Accent else ActionTone.Neutral,
                    enabled = !testing
                )
            }
        }
    )

    val statusRows: List<@Composable () -> Unit> = buildList {
        chain.order.forEach { source ->
            add {
                val t = stats.tally(source, today)
                SettingRow(
                    icon = sourceIcon(source),
                    title = sourceName(source),
                    hint = stringResource(R.string.covers43_today, t.hits, t.misses, t.errors),
                    onClick = {}
                ) {}
            }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_image_search,
                title = stringResource(R.string.settings_v5_covers_retry),
                hint = retried?.let { pluralStringResource(R.plurals.settings_v5_covers_reset_done, it, it) }
                    ?: stringResource(R.string.covers43_retry_hint),
                onClick = { viewModel.retryMisses(context) }
            ) {
                ActionPill(stringResource(R.string.settings_v5_covers_retry_action), { viewModel.retryMisses(context) }, icon = R.drawable.ic_retry)
            }
        }
    }

    val cells = remember(orderRows.size, loginRows.size, statusRows.size) {
        CardGrid.layout(
            listOf(
                CardGrid.Section(header = true, items = orderRows.size),
                CardGrid.Section(header = true, items = loginRows.size),
                CardGrid.Section(header = true, items = statusRows.size)
            ),
            1
        )
    }
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRow.requestFocusWhenReady() }

    SettingsSurface {
        SettingsCardsGrid(cells = cells, columns = 1, title = { CoverSourcesTitle() }) { _, cell ->
            when (cell.kind) {
                CardGrid.Kind.HEADER -> when (cell.section) {
                    0 -> SettingsCardHeader(stringResource(R.string.covers43_order_title), R.drawable.ic_sort)
                    1 -> SettingsCardHeader(stringResource(R.string.covers43_ss_title), R.drawable.ic_key)
                    else -> SettingsCardHeader(stringResource(R.string.covers43_status_title), R.drawable.ic_bar_chart)
                }
                CardGrid.Kind.ITEM -> {
                    val content = when (cell.section) {
                        0 -> orderRows[cell.item]
                        1 -> loginRows[cell.item]
                        else -> statusRows[cell.item]
                    }
                    Box(
                        modifier = if (cell.section == 0 && cell.item == 0) Modifier.focusRequester(firstRow) else Modifier,
                        propagateMinConstraints = true
                    ) { content() }
                }
            }
        }
    }

    editing?.let { field ->
        val (title, hint, value) = when (field) {
            CoverSourceSettings.Field.DEV_ID -> Triple(R.string.covers43_ss_dev_id, R.string.covers43_ss_dev_hint, login.devId)
            CoverSourceSettings.Field.DEV_PASSWORD -> Triple(R.string.covers43_ss_dev_password, R.string.covers43_ss_dev_hint, login.devPassword)
            CoverSourceSettings.Field.SOFT_NAME -> Triple(R.string.covers43_ss_soft_name, R.string.covers43_ss_soft_name_hint, login.softName)
            CoverSourceSettings.Field.USER -> Triple(R.string.covers43_ss_user, R.string.covers43_ss_user_hint, login.user)
            CoverSourceSettings.Field.PASSWORD -> Triple(R.string.covers43_ss_password, R.string.covers43_ss_user_hint, login.password)
        }
        CredentialDialog(
            title = stringResource(title),
            hint = stringResource(hint),
            value = value,
            secret = field == CoverSourceSettings.Field.DEV_PASSWORD || field == CoverSourceSettings.Field.PASSWORD,
            onSave = { viewModel.set(field, it) },
            onDismiss = { editing = null }
        )
    }
}

/** The screen title: the icon tile and the name, with a short line under it (wraps, never cut off). */
@Composable
private fun CoverSourcesTitle() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(R.drawable.ic_image_search, size = 38.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.covers43_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.covers43_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun sourceName(source: Source): String = stringResource(
    when (source) {
        Source.ROMM -> R.string.covers43_source_romm
        Source.LIBRETRO -> R.string.covers43_source_libretro
        Source.SCREENSCRAPER -> R.string.covers43_source_screenscraper
        Source.METADATA -> R.string.covers43_source_metadata
    }
)

private fun sourceIcon(source: Source): Int = when (source) {
    Source.ROMM -> R.drawable.ic_server
    Source.LIBRETRO -> R.drawable.ic_image
    Source.SCREENSCRAPER -> R.drawable.ic_globe
    Source.METADATA -> R.drawable.ic_description
}

/** What a source is, and below it, whether it can answer now and what it did today. */
@Composable
private fun sourceHint(source: Source, chain: CoverSources.Chain, login: ScreenScraperLogin, stats: CoverSourceStats.State): String {
    val about = stringResource(
        when (source) {
            Source.ROMM -> R.string.covers43_source_romm_hint
            Source.LIBRETRO -> R.string.covers43_source_libretro_hint
            Source.SCREENSCRAPER -> R.string.covers43_source_screenscraper_hint
            Source.METADATA -> R.string.covers43_source_metadata_hint
        }
    )
    val state = when {
        !chain.enabled(source) -> stringResource(R.string.covers43_state_off)
        source == Source.SCREENSCRAPER -> screenScraperState(login, stats)
        else -> null
    }
    return if (state == null) about else about + "\n" + state
}

@Composable
internal fun screenScraperState(login: ScreenScraperLogin, stats: CoverSourceStats.State): String {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    if (!login.ready) return stringResource(R.string.covers43_state_needs_dev)
    if (now < stats.pausedUntil) {
        val until = DateUtils.formatDateTime(context, stats.pausedUntil, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY)
        return stringResource(R.string.covers43_state_quota, until)
    }
    return when (stats.problem) {
        CoverSourceStats.Problem.LOGIN -> stringResource(R.string.covers43_state_login)
        CoverSourceStats.Problem.BUSY -> stringResource(R.string.covers43_state_busy)
        CoverSourceStats.Problem.RATE_LIMITED -> stringResource(R.string.covers43_state_rate)
        CoverSourceStats.Problem.OFFLINE -> stringResource(R.string.covers43_state_offline)
        CoverSourceStats.Problem.QUOTA, null -> {
            val user = stats.user
            val left = user?.remainingToday
            when {
                user != null && left != null && ScreenScraper.dayOf(stats.userAt) == ScreenScraper.dayOf(now) ->
                    stringResource(R.string.covers43_state_ready_quota, left, user.maxRequestsPerDay ?: 0)
                else -> stringResource(R.string.covers43_state_ready)
            }
        }
    }
}

@Composable
private fun loginResultText(result: ScreenScraperService.LoginResult): String {
    val context = LocalContext.current
    return when (result) {
        is ScreenScraperService.LoginResult.LoggedIn -> {
            val left = result.user.remainingToday
            if (left != null) stringResource(R.string.covers43_login_ok_quota, result.user.name, left, result.user.maxRequestsPerDay ?: 0, result.user.maxThreads)
            else stringResource(R.string.covers43_login_ok, result.user.name, result.user.maxThreads)
        }
        ScreenScraperService.LoginResult.DeveloperOnly -> stringResource(R.string.covers43_login_dev_only)
        ScreenScraperService.LoginResult.DeveloperMissing -> stringResource(R.string.covers43_login_dev_missing)
        ScreenScraperService.LoginResult.DeveloperRejected -> stringResource(R.string.covers43_login_dev_rejected)
        ScreenScraperService.LoginResult.WrongLogin -> stringResource(R.string.covers43_login_wrong)
        is ScreenScraperService.LoginResult.QuotaReached -> {
            val until = result.until.takeIf { it > System.currentTimeMillis() } ?: ScreenScraper.nextReset(System.currentTimeMillis())
            stringResource(R.string.covers43_login_quota, DateUtils.formatDateTime(context, until, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY))
        }
        ScreenScraperService.LoginResult.Busy -> stringResource(R.string.covers43_login_busy)
        ScreenScraperService.LoginResult.Offline -> stringResource(R.string.covers43_login_offline)
    }
}

/** One source of the chain: its place, name, what it is and does now; switch, and move up / down. */
@Composable
private fun SourceRow(
    position: Int,
    source: Source,
    hint: String,
    enabled: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit
) {
    val name = sourceName(source)
    SettingRow(
        icon = sourceIcon(source),
        title = stringResource(R.string.covers43_source_place, position, name),
        hint = hint,
        onClick = { onToggle(!enabled) }
    ) {
        if (source != Source.ROMM) {
            MoveButton(R.drawable.ic_arrow_upward, stringResource(R.string.covers43_move_up, name), canUp, onUp)
            MoveButton(R.drawable.ic_arrow_downward, stringResource(R.string.covers43_move_down, name), canDown, onDown)
        }
        ThemedSwitch(enabled, onToggle)
    }
}

/** A 40 dp icon button with the focus ring; not focusable while it can do nothing. */
@Composable
private fun MoveButton(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Box(
        modifier = Modifier
            .size(40.dp)
            .focusRing(source, 10.dp)
            .background(if (enabled) scheme.surfaceContainerHigh else scheme.surfaceContainer, RoundedCornerShape(10.dp))
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(icon), contentDescription = description,
            tint = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** A password row never shows any of its characters: fixed bullets when set, else "Not set". */
@Composable
private fun secretState(value: String): String =
    if (value.isEmpty()) stringResource(R.string.settings_not_set) else "••••••••"

@Composable
private fun CredentialRow(icon: Int, title: Int, value: String, onEdit: () -> Unit) {
    SettingRow(icon = icon, title = stringResource(title), hint = value, onClick = onEdit) {
        ActionPill(stringResource(R.string.settings_change), onEdit, icon = R.drawable.ic_edit)
    }
}

/** Edits one credential; a password is typed hidden. Opens with the field focused, B cancels. */
@Composable
private fun CredentialDialog(title: String, hint: String, value: String, secret: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(value) }
    val fieldFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(title) },
                    singleLine = true,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                )
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_save), onClick = { onSave(text); onDismiss() }) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss) }
    )
}
