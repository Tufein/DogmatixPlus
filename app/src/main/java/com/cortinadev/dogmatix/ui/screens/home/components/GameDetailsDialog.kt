package com.cortinadev.dogmatix.ui.screens.home.components

import android.content.res.Configuration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.CollectionWithCount
import com.cortinadev.dogmatix.data.model.GameDetails
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.TagRow
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.ui.components.swapFaceButtons
import com.cortinadev.dogmatix.ui.screens.home.DetailsState
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.SwitchTitles
import kotlinx.coroutines.launch

/**
 * Card with the online metadata of one library entry. Opens with X, closes with B or X;
 * the D-pad scrolls the synopsis while a button is focused, so nothing needs the touch screen.
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
    onDownloadDlc: (() -> Unit)? = null
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val downloadFocus = remember { FocusRequester() }
    val rom = state.item.file

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The button is not attached on the first frame: retry for a few frames so the first
        // gamepad press acts instead of merely initialising focus.
        LaunchedEffect(Unit) {
            repeat(5) {
                if (runCatching { downloadFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                withFrameNanos { }
            }
        }
        Column(
            modifier = Modifier
                .padding(horizontal = if (isLandscape) 48.dp else 16.dp, vertical = 24.dp)
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surfaceContainer)
                // Its own window: the face-button swap has to be applied here too.
                .swapFaceButtons()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.ButtonX -> { onDismiss(); true }
                        // The dialog is its own window, so Select never reaches the Activity's gamepad bus.
                        Key.ButtonSelect, Key.ButtonThumbLeft -> { onToggleFavourite(); true }
                        Key.DirectionUp -> scroll.maxValue > 0 && scroll.value > 0 && scope.launch { scroll.animateScrollBy(-SCROLL_STEP) }.let { true }
                        Key.DirectionDown -> scroll.maxValue > 0 && scroll.value < scroll.maxValue && scope.launch { scroll.animateScrollBy(SCROLL_STEP) }.let { true }
                        else -> false
                    }
                }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val details = state.details
            val title = details?.title?.takeIf { it.isNotBlank() } ?: stripExtension(rom.name)

            if (isLandscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.heightIn(max = 260.dp)) {
                    Artwork(details, Modifier.width(240.dp).height(180.dp))
                    Body(state, title, consoleName, scroll, Modifier.weight(1f), onRomm)
                }
            } else {
                Artwork(details, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
                Body(state, title, consoleName, scroll, Modifier.heightIn(max = 300.dp), onRomm)
            }

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                if (onDownloadUpdate != null) {
                    val updateSource = rememberFocusSource()
                    TextButton(onClick = onDownloadUpdate, interactionSource = updateSource, modifier = Modifier.focusRing(updateSource, 20.dp)) {
                        Text(stringResource(R.string.details_switch_get_update))
                    }
                }
                if (onDownloadDlc != null) {
                    val dlcSource = rememberFocusSource()
                    TextButton(onClick = onDownloadDlc, interactionSource = dlcSource, modifier = Modifier.focusRing(dlcSource, 20.dp)) {
                        Text(stringResource(R.string.details_switch_get_dlc, state.switch?.missingDlc?.size ?: 0))
                    }
                }
                if (onCollections != null) {
                    val collectionsSource = rememberFocusSource()
                    TextButton(onClick = onCollections, interactionSource = collectionsSource, modifier = Modifier.focusRing(collectionsSource, 20.dp)) {
                        Text(if (state.collectionIds.isEmpty()) stringResource(R.string.details_collections)
                             else pluralStringResource(R.plurals.details_in_collections, state.collectionIds.size, state.collectionIds.size))
                    }
                }
                val favouriteSource = rememberFocusSource()
                TextButton(onClick = onToggleFavourite, interactionSource = favouriteSource, modifier = Modifier.focusRing(favouriteSource, 20.dp)) {
                    Text(stringResource(if (favourite) R.string.details_unfavourite else R.string.details_favourite))
                }
                val closeSource = rememberFocusSource()
                TextButton(onClick = onDismiss, interactionSource = closeSource, modifier = Modifier.focusRing(closeSource, 20.dp)) {
                    Text(stringResource(R.string.details_close))
                }
                if (onDownloadBest != null && state.best != null) {
                    val bestSource = rememberFocusSource()
                    TextButton(onClick = onDownloadBest, interactionSource = bestSource, modifier = Modifier.focusRing(bestSource, 20.dp)) {
                        Text(stringResource(R.string.details_download_best))
                    }
                }
                val downloadSource = rememberFocusSource()
                Button(
                    onClick = onDownload,
                    interactionSource = downloadSource,
                    modifier = Modifier.focusRequester(downloadFocus).focusRing(downloadSource, 20.dp)
                ) {
                    Text(stringResource(R.string.details_download))
                }
            }
        }
    }
}

@Composable
private fun Artwork(details: GameDetails?, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        val url = details?.imageUrl.orEmpty()
        if (url.isNotEmpty()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(400.dp)
            )
        }
    }
}

@Composable
private fun Body(state: DetailsState, title: String, consoleName: String, scroll: ScrollState, modifier: Modifier, onRomm: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val details = state.details
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TagRow(console = consoleName, tags = state.item.tags, extension = state.item.file.fileExtension, maxLines = 1)
        val meta = listOfNotNull(
            details?.released?.takeIf { it.isNotBlank() },
            details?.developer?.takeIf { it.isNotBlank() },
            details?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        ).joinToString("  ·  ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        if (onRomm) Text(stringResource(R.string.details_on_romm), style = MaterialTheme.typography.labelMedium, color = scheme.tertiary)
        if (state.versionCount > 1) {
            val best = state.best
            Text(
                if (best == null) pluralStringResource(R.plurals.details_versions_this_best, state.versionCount, state.versionCount)
                else pluralStringResource(R.plurals.details_versions, state.versionCount, state.versionCount, stripExtension(best.file.fileName.let(FileParsingUtils::decodeUrlEncodedFileName))),
                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant
            )
        }

        state.achievements?.let { (game, byHash) ->
            Text(
                if (byHash) pluralStringResource(R.plurals.details_ra, game.achievements, game.achievements)
                else pluralStringResource(R.plurals.details_ra_probably, game.achievements, game.achievements),
                style = MaterialTheme.typography.labelMedium, color = scheme.primary
            )
        }
        state.switchTitle?.let { title -> SwitchLines(title, state.switch) }

        Box(modifier = Modifier.weight(1f, fill = false)) {
            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.details_loading), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                details == null -> Text(stringResource(R.string.details_not_found), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                else -> Column(modifier = Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        details.description.ifBlank { stringResource(R.string.details_not_found) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface
                    )
                    Text(stringResource(R.string.details_source, details.source), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** What a Switch file is (base game, update, DLC) and how its game's updates and DLC stand. */
@Composable
private fun SwitchLines(title: SwitchTitles.Title, status: SwitchTitles.GameStatus<*>?) {
    val scheme = MaterialTheme.colorScheme
    val kind = when (title.kind) {
        SwitchTitles.Kind.BASE -> stringResource(R.string.switch_kind_base)
        SwitchTitles.Kind.UPDATE -> stringResource(R.string.switch_kind_update, title.release ?: 0L)
        SwitchTitles.Kind.DLC -> stringResource(R.string.switch_kind_dlc)
    }
    Text("$kind · ${title.id}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    if (status == null) return
    val update = status.newestUpdate?.second
    val updateText = when {
        update == null -> stringResource(R.string.switch_no_update_listed)
        status.ownedUpdate == null -> stringResource(R.string.switch_update_none_owned, update / 65_536)
        status.updateAvailable -> stringResource(R.string.switch_update_newer, update / 65_536, status.ownedUpdate / 65_536)
        else -> stringResource(R.string.switch_update_current, status.ownedUpdate / 65_536)
    }
    Text(updateText, style = MaterialTheme.typography.labelMedium, color = if (status.updateAvailable) scheme.primary else scheme.onSurfaceVariant)
    if (status.dlcInLibrary > 0) Text(
        stringResource(R.string.switch_dlc_line, status.dlcInLibrary, status.dlcOwned),
        style = MaterialTheme.typography.labelMedium, color = if (status.missingDlc.isNotEmpty()) scheme.primary else scheme.onSurfaceVariant
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
    val closeFocus = com.cortinadev.dogmatix.ui.components.rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collections_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (collections.isEmpty()) Text(stringResource(R.string.collections_none_yet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                collections.forEach { c ->
                    val source = rememberFocusSource()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .focusRing(source)
                            .toggleRow(c.id in selected, source) { onToggle(c.id) }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Checkbox(checked = c.id in selected, onCheckedChange = null)
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
