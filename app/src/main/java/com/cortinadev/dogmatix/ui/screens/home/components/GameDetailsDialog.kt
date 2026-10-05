package com.cortinadev.dogmatix.ui.screens.home.components

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.CollectionWithCount
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.PrimaryButton
import com.cortinadev.dogmatix.ui.components.TagRow
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.coverPlaceholder
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberCoverRepository
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.ui.components.swapFaceButtons
import com.cortinadev.dogmatix.ui.screens.home.DetailsState
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.consoleColor
import com.cortinadev.dogmatix.ui.theme.tabular
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.NewGames
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.SwitchTitles
import com.cortinadev.dogmatix.util.TagClassifier
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Card with the online metadata of one library entry. Opens with X, closes with B or X;
 * the D-pad scrolls the synopsis while a button is focused, so nothing needs the touch screen.
 *
 * 5.0: a hero header — the game's art blurred and dimmed behind the card's top, the cover in front,
 * the title in large type and a row of info pills (console, size, region, source, on device, on
 * RomM, verified, found date) — then the description and [extraSections].
 */
@Composable
fun GameDetailsDialog(
    state: DetailsState,
    consoleName: String,
    favourite: Boolean,
    onToggleFavourite: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    /** The RomM server already has this game. */
    onRomm: Boolean = false,
    /** Downloads the version the library ranks best for the user (see [DetailsState.best]). */
    onDownloadBest: (() -> Unit)? = null,
    /** Opens the collection picker. */
    onCollections: (() -> Unit)? = null,
    /** Switch: downloads the newest update / the DLC that are not on disk. */
    onDownloadUpdate: (() -> Unit)? = null,
    onDownloadDlc: (() -> Unit)? = null,
    /** 5.0: the file is already in the download folder. */
    owned: Boolean = false,
    /** 5.0: a download of this file is in flight. */
    downloading: Boolean = false,
    /**
     * 5.0 EXTENSION POINT for the cloud features: sections shown under the description (cloud
     * saves and states, RetroAchievements progress, RomM play status / rating). They live inside the
     * scrolling column, so ▲ ▼ reach them; keep each one a self-contained block.
     */
    extraSections: @Composable ColumnScope.() -> Unit = {}
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val downloadFocus = remember { FocusRequester() }
    val rom = state.item.file

    // The cover from the shared repository (RomM, libretro box art, cached metadata); the metadata
    // image (often a screenshot) is the better backdrop, the box art the better front cover.
    val covers = rememberCoverRepository()
    val coverUrl by produceState(initialValue = covers.cached(rom.consoleId, rom.fileName), rom.consoleId, rom.fileName) {
        if (value == null) value = runCatching { covers.coverUrl(rom.consoleId, rom.fileName, rom.name) }.getOrNull()
    }
    val artUrl = state.details?.imageUrl?.takeIf { it.isNotBlank() }
    val frontUrl = coverUrl ?: artUrl
    val backdropUrl = artUrl ?: coverUrl

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The button is not attached on the first frame: retry for a few frames so the first
        // gamepad press acts instead of merely initialising focus.
        LaunchedEffect(Unit) {
            repeat(5) {
                if (runCatching { downloadFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                withFrameNanos { }
            }
        }
        val shape = RoundedCornerShape(24.dp)
        Column(
            modifier = Modifier
                .padding(horizontal = if (isLandscape) 48.dp else 16.dp, vertical = 24.dp)
                .widthIn(max = 720.dp)
                .shadow(16.dp, shape)
                .clip(shape)
                .background(scheme.surfaceContainer)
                .border(1.dp, tokens.hairline, shape)
                // Its own window: the face-button swap has to be applied here too.
                .swapFaceButtons()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.ButtonX -> { onDismiss(); true }
                        // The dialog is its own window, so Select never reaches the Activity's gamepad bus.
                        Key.ButtonSelect, Key.ButtonThumbLeft -> { onToggleFavourite(); true }
                        // Focus first (the cloud sections have rows to reach); where it cannot move, ▲ ▼ scroll the text.
                        Key.DirectionUp ->
                            focusManager.moveFocus(FocusDirection.Up) ||
                                (scroll.maxValue > 0 && scroll.value > 0 && scope.launch { scroll.animateScrollBy(-SCROLL_STEP) }.let { true })
                        Key.DirectionDown ->
                            focusManager.moveFocus(FocusDirection.Down) ||
                                (scroll.maxValue > 0 && scroll.value < scroll.maxValue && scope.launch { scroll.animateScrollBy(SCROLL_STEP) }.let { true })
                        else -> false
                    }
                }
        ) {
            val details = state.details
            val title = details?.title?.takeIf { it.isNotBlank() } ?: stripExtension(rom.name)
            val pills: @Composable () -> Unit = { InfoPills(state, consoleName, onRomm, owned, downloading, favourite) }

            if (isLandscape) {
                Hero(backdropUrl, rom.consoleId, Modifier.fillMaxWidth()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier
                            .heightIn(max = 270.dp)
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp)
                    ) {
                        HeroCover(frontUrl, rom.consoleId, height = 186.dp, maxRatio = 1.45f)
                        Body(state, title, scroll, Modifier.weight(1f), header = pills, extraSections = extraSections)
                    }
                }
            } else {
                Hero(backdropUrl, rom.consoleId, Modifier.fillMaxWidth()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
                    ) {
                        HeroCover(frontUrl, rom.consoleId, height = 148.dp, maxRatio = 1.0f)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Title(title)
                            pills()
                        }
                    }
                }
                Body(state, title, scroll, Modifier.padding(horizontal = 16.dp).heightIn(max = 300.dp), header = null, extraSections = extraSections)
            }

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)
            ) {
                if (onDownloadUpdate != null) {
                    ActionPill(stringResource(R.string.details_switch_get_update), onDownloadUpdate, icon = R.drawable.ic_sync, tone = ActionTone.Accent)
                }
                if (onDownloadDlc != null) {
                    ActionPill(stringResource(R.string.details_switch_get_dlc, state.switch?.missingDlc?.size ?: 0), onDownloadDlc, icon = R.drawable.ic_plus, tone = ActionTone.Accent)
                }
                if (onCollections != null) {
                    ActionPill(
                        if (state.collectionIds.isEmpty()) stringResource(R.string.details_collections)
                        else pluralStringResource(R.plurals.details_in_collections, state.collectionIds.size, state.collectionIds.size),
                        onCollections,
                        icon = R.drawable.ic_collections
                    )
                }
                ActionPill(
                    stringResource(if (favourite) R.string.home_details_unfavourite else R.string.home_details_favourite),
                    onToggleFavourite,
                    icon = R.drawable.ic_star,
                    tone = if (favourite) ActionTone.Accent else ActionTone.Neutral
                )
                ActionPill(stringResource(R.string.details_close), onDismiss, icon = R.drawable.ic_close)
                if (onDownloadBest != null && state.best != null) {
                    ActionPill(stringResource(R.string.details_download_best), onDownloadBest, icon = R.drawable.ic_award)
                }
                PrimaryButton(
                    stringResource(R.string.details_download),
                    onClick = onDownload,
                    modifier = Modifier.focusRequester(downloadFocus),
                    icon = R.drawable.ic_download
                )
            }
        }
    }
}

/**
 * The card's top: [url] blurred (Android 12+; just dimmed before that) over the console's colour,
 * fading into the card colour at the bottom so the text over it stays readable.
 */
@Composable
private fun Hero(url: String?, consoleId: String, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier) {
        Backdrop(url, consoleId)
        content()
    }
}

@Composable
private fun BoxScope.Backdrop(url: String?, consoleId: String) {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val ground = MaterialTheme.colorScheme.surfaceContainer
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(Modifier.matchParentSize().coverPlaceholder(consoleId))
    if (!url.isNullOrBlank()) {
        val request = remember(url, reduce) {
            ImageRequest.Builder(context).data(url).crossfade(if (reduce) 0 else 300).build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Painter alpha (no offscreen layer). Unblurred art is kept fainter so it never competes with the text.
            alpha = if (canBlur) 0.9f else 0.5f,
            modifier = Modifier
                .matchParentSize()
                .then(if (canBlur) Modifier.blur(22.dp) else Modifier)
        )
    }
    Box(
        Modifier
            .matchParentSize()
            .drawWithCache {
                val scrim = Brush.verticalGradient(
                    0f to ground.copy(alpha = 0.58f),
                    0.55f to ground.copy(alpha = 0.80f),
                    1f to ground
                )
                onDrawBehind { drawRect(scrim) }
            }
    )
}

/**
 * The cover in front of the backdrop: portrait 3:4 until the picture arrives, then the picture's own
 * shape (box art of some consoles is landscape), between 0.62 and [maxRatio].
 */
@Composable
private fun HeroCover(url: String?, consoleId: String, height: Dp, maxRatio: Float) {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    var ratio by remember(url) { mutableFloatStateOf(3f / 4f) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .height(height)
            .aspectRatio(ratio)
            .shadow(10.dp, shape)
            .clip(shape)
            .coverPlaceholder(consoleId),
        contentAlignment = Alignment.Center
    ) {
        Text(
            ConsoleFormatter.getConsoleShortName(consoleId),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp)
        )
        if (!url.isNullOrBlank()) {
            val request = remember(url, reduce) {
                ImageRequest.Builder(context).data(url).crossfade(if (reduce) 0 else 180).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { success ->
                    val size = success.painter.intrinsicSize
                    if (size.isSpecified && size.width > 0f && size.height > 0f) {
                        ratio = (size.width / size.height).coerceIn(0.62f, maxRatio)
                    }
                }
            )
        }
    }
}

@Composable
private fun Title(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/** Console, size, region, source, on device / on RomM, verified, found date, favourite. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoPills(
    state: DetailsState,
    consoleName: String,
    onRomm: Boolean,
    owned: Boolean,
    downloading: Boolean,
    favourite: Boolean
) {
    val rom = state.item.file
    val region = remember(state.item.tags) { state.item.tags.firstOrNull { TagClassifier.kindOf(it) == TagClassifier.Kind.REGION } }
    val fromRomm = remember(rom.downloadUrl) { RommSource.romIdOf(rom.downloadUrl) != null }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Pill(consoleName, tone = PillTone.Tint(consoleColor(rom.consoleId)), icon = R.drawable.ic_gamepad)
        if (rom.fileSize > 0) Pill(formatBytes(rom.fileSize), tone = PillTone.Neutral, icon = R.drawable.ic_storage)
        region?.let { Pill(it, tone = PillTone.Neutral, icon = R.drawable.ic_globe) }
        when {
            rom.isTorrent -> Pill(stringResource(R.string.source_torrent), tone = PillTone.Neutral, icon = R.drawable.ic_p2p)
            fromRomm -> if (!onRomm) Pill(stringResource(R.string.source_romm), tone = PillTone.Info, icon = R.drawable.ic_cloud)
            else -> Pill(stringResource(R.string.source_direct), tone = PillTone.Neutral, icon = R.drawable.ic_link)
        }
        if (downloading) Pill(stringResource(R.string.downloading_badge), tone = PillTone.Accent, icon = R.drawable.ic_download)
        else if (owned) Pill(stringResource(R.string.home_pill_on_device), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
        if (onRomm) Pill(stringResource(R.string.home_pill_on_romm), tone = PillTone.Info, icon = R.drawable.ic_cloud_done)
        if (!rom.expectedHash.isNullOrBlank()) Pill(stringResource(R.string.home_pill_verified), tone = PillTone.Success, icon = R.drawable.ic_verified)
        if (rom.firstSeenAt > 0L) {
            val date = remember(rom.firstSeenAt) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(rom.firstSeenAt)) }
            val fresh = remember(rom.firstSeenAt) { NewGames.isNew(rom.firstSeenAt, System.currentTimeMillis()) }
            Pill(
                stringResource(R.string.home_pill_found, date),
                tone = if (fresh) PillTone.Accent else PillTone.Neutral,
                icon = R.drawable.ic_calendar_today
            )
        }
        if (favourite) Pill(stringResource(R.string.favourite), tone = PillTone.Accent, icon = R.drawable.ic_star)
    }
}

@Composable
private fun Body(
    state: DetailsState,
    title: String,
    scroll: ScrollState,
    modifier: Modifier,
    /** Title and pills on top (landscape); null when the hero already shows them (portrait). */
    header: (@Composable () -> Unit)?,
    extraSections: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val details = state.details
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (header != null) {
            Title(title)
            header()
        }
        TagRow(
            console = ConsoleFormatter.getConsoleShortName(state.item.file.consoleId),
            tags = state.item.tags,
            extension = state.item.file.fileExtension,
            maxLines = 1,
            consoleId = state.item.file.consoleId
        )
        val meta = listOfNotNull(
            details?.released?.takeIf { it.isNotBlank() },
            details?.developer?.takeIf { it.isNotBlank() },
            details?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        ).joinToString("  ·  ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelLarge, color = scheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (state.versionCount > 1) {
            val best = state.best
            InfoLine(
                R.drawable.ic_stacks,
                if (best == null) pluralStringResource(R.plurals.details_versions_this_best, state.versionCount, state.versionCount)
                else pluralStringResource(R.plurals.details_versions, state.versionCount, state.versionCount, stripExtension(best.file.fileName.let(FileParsingUtils::decodeUrlEncodedFileName)))
            )
        }
        state.achievements?.let { (game, byHash) ->
            InfoLine(
                R.drawable.ic_trophy,
                if (byHash) pluralStringResource(R.plurals.details_ra, game.achievements, game.achievements)
                else pluralStringResource(R.plurals.details_ra_probably, game.achievements, game.achievements),
                accent = true
            )
        }
        state.switchTitle?.let { switchTitle -> SwitchLines(switchTitle, state.switch) }

        Box(modifier = Modifier.weight(1f, fill = false)) {
            Column(modifier = Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    state.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.details_loading), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                    details == null -> Text(stringResource(R.string.details_not_found), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    else -> Text(
                        details.description.ifBlank { stringResource(R.string.details_not_found) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface
                    )
                }
                // ---- 5.0 extension point: cloud sections (see GameDetailsDialog.extraSections) ----
                extraSections()
                if (details != null) {
                    Text(stringResource(R.string.details_source, details.source), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A small icon and one line of facts (versions, achievements, Switch status). */
@Composable
private fun InfoLine(icon: Int, text: String, accent: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val color = if (accent) scheme.primary else scheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** What a Switch file is (base game, update, DLC) and how its game's updates and DLC stand. */
@Composable
private fun SwitchLines(title: SwitchTitles.Title, status: SwitchTitles.GameStatus<*>?) {
    val kind = when (title.kind) {
        SwitchTitles.Kind.BASE -> stringResource(R.string.switch_kind_base)
        SwitchTitles.Kind.UPDATE -> stringResource(R.string.switch_kind_update, title.release ?: 0L)
        SwitchTitles.Kind.DLC -> stringResource(R.string.switch_kind_dlc)
    }
    InfoLine(R.drawable.ic_label, "$kind · ${title.id}")
    if (status == null) return
    val update = status.newestUpdate?.second
    val updateText = when {
        update == null -> stringResource(R.string.switch_no_update_listed)
        status.ownedUpdate == null -> stringResource(R.string.switch_update_none_owned, update / 65_536)
        status.updateAvailable -> stringResource(R.string.switch_update_newer, update / 65_536, status.ownedUpdate / 65_536)
        else -> stringResource(R.string.switch_update_current, status.ownedUpdate / 65_536)
    }
    InfoLine(R.drawable.ic_sync, updateText, accent = status.updateAvailable)
    if (status.dlcInLibrary > 0) InfoLine(
        R.drawable.ic_plus,
        stringResource(R.string.switch_dlc_line, status.dlcInLibrary, status.dlcOwned),
        accent = status.missingDlc.isNotEmpty()
    )
}

/**
 * The own collections, ticked where [state]'s game is in them, plus a field to start a new one.
 * A tap puts the game in or takes it out right away.
 */
@Composable
fun CollectionPickerDialog(
    collections: List<CollectionWithCount>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val closeFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_collections), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.collections_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (collections.isEmpty()) Text(stringResource(R.string.collections_none_yet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                collections.forEach { c ->
                    val source = rememberFocusSource()
                    val checked = c.id in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(10.dp))
                            .focusRing(source, 10.dp)
                            .toggleRow(checked, source) { onToggle(c.id) }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            painterResource(if (checked) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox_off),
                            contentDescription = null,
                            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.count.toString(), style = MaterialTheme.typography.labelMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                        label = { Text(stringResource(R.string.collections_new)) }, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { if (name.isNotBlank()) { onCreate(name); name = "" } }, enabled = name.isNotBlank()) {
                        Text(stringResource(R.string.collections_add))
                    }
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss, initialFocus = closeFocus) }
    )
}

private fun Modifier.toggleRow(value: Boolean, source: MutableInteractionSource, onToggle: () -> Unit): Modifier =
    this.toggleable(value = value, interactionSource = source, indication = null, onValueChange = { onToggle() })

private const val SCROLL_STEP = 160f
