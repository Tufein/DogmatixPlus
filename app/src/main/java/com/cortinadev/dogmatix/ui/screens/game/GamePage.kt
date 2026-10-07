package com.cortinadev.dogmatix.ui.screens.game

import android.content.res.Configuration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.service.GameShare
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.EmptyState
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.PrimaryButton
import com.cortinadev.dogmatix.ui.components.TagRow
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.ui.screens.cloud.saves.CloudSavesSection
import com.cortinadev.dogmatix.ui.screens.cloud.sections.AchievementsSection
import com.cortinadev.dogmatix.ui.screens.cloud.sections.RommGameSection
import com.cortinadev.dogmatix.ui.screens.download.DownloadWhenDialog
import com.cortinadev.dogmatix.ui.screens.home.DetailsState
import com.cortinadev.dogmatix.ui.screens.home.components.CollectionPickerDialog
import com.cortinadev.dogmatix.ui.screens.home.components.GameDescription
import com.cortinadev.dogmatix.ui.screens.home.components.GameFacts
import com.cortinadev.dogmatix.ui.screens.home.components.GameHero
import com.cortinadev.dogmatix.ui.screens.home.components.GameHeroCover
import com.cortinadev.dogmatix.ui.screens.home.components.GameInfoPills
import com.cortinadev.dogmatix.ui.screens.home.components.GameTitle
import com.cortinadev.dogmatix.ui.screens.home.components.SimilarSection
import com.cortinadev.dogmatix.ui.screens.home.components.rememberGameArt
import com.cortinadev.dogmatix.ui.screens.tools.PublishLegend
import com.cortinadev.dogmatix.ui.secondscreen.SecondScreenState
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.tabular
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DownloadCondition
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.GamePageModel
import com.cortinadev.dogmatix.util.GamePageModel.Tab
import com.cortinadev.dogmatix.util.TagClassifier
import kotlinx.coroutines.launch

/**
 * 8.0: the full-screen page of one library game, the main way to look at a game (the library's
 * A / tap opens it). A hero header (the art over the console's colour, the cover, title, info and
 * language pills, the main button and the other actions), then tabs: About (description, facts,
 * the RomM section), Versions (every variant in the sources, each downloadable), Progress
 * (RetroAchievements and cloud saves) and More like this. Portrait scrolls as one column;
 * landscape keeps the hero on the left and the tabs on the right.
 *
 * Gamepad: the D-pad walks the buttons (▲ ▼ scroll where nothing more can take focus), A acts,
 * B goes back, LB / RB switch tabs and Select stars the game.
 *
 * @param onOpenGame opens another game's page ("More like this").
 */
@Composable
fun GamePage(
    consoleId: String,
    fileName: String,
    onBack: () -> Unit,
    onOpenGame: (consoleId: String, fileName: String) -> Unit,
    viewModel: GamePageViewModel = hiltViewModel()
) {
    LaunchedEffect(consoleId, fileName) { viewModel.load(consoleId, fileName) }
    val phase by viewModel.phase.collectAsState()
    val state by viewModel.details.collectAsState()
    val current = state
    when {
        phase == GamePagePhase.MISSING -> {
            PublishLegend(listOf(LegendEntry("A", stringResource(R.string.pad_back)), LegendEntry("B", stringResource(R.string.pad_back))))
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.page8_missing_title),
                    message = stringResource(R.string.page8_missing_message),
                    icon = R.drawable.ic_search_off,
                    actionLabel = stringResource(R.string.page8_back),
                    onAction = onBack,
                    actionFocus = rememberInitialFocus()
                )
            }
        }
        current == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        else -> GamePageContent(current, viewModel, onBack, onOpenGame)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GamePageContent(
    state: DetailsState,
    viewModel: GamePageViewModel,
    onBack: () -> Unit,
    onOpenGame: (String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val item = state.item
    val rom = item.file
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val ownedKeys by viewModel.ownedKeys.collectAsState()
    val active by viewModel.activeDownloads.collectAsState()
    val favouriteKeys by viewModel.favouriteKeys.collectAsState()
    val rommKeys by viewModel.rommKeys.collectAsState()
    val progressAvailable by viewModel.progressAvailable.collectAsState()
    val collections by viewModel.collections.collectAsState()
    val owned = viewModel.isOwned(rom, ownedKeys)
    val downloading = rom.fileName in active
    val favourite = viewModel.isFavourite(rom, favouriteKeys)
    val onRomm = viewModel.isOnRomm(rom, rommKeys)

    // The game on screen also goes to a second display, when there is one.
    LaunchedEffect(item) { SecondScreenState.focus(item) }

    val tabs = GamePageModel.tabs(state.versionCount, progressAvailable || state.achievements != null, state.similar.size)
    var tabName by rememberSaveable(rom.consoleId, rom.fileName) { mutableStateOf(Tab.ABOUT.name) }
    val tab = GamePageModel.visible(tabs, Tab.valueOf(tabName))

    val snackbar = remember { SnackbarHostState() }
    val showMessage: (String) -> Unit = { message ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }
    val startedMessage = stringResource(R.string.download_started, "%s")
    val alreadyMessage = stringResource(R.string.download_already_active, "%s")
    val favouriteAddedMessage = stringResource(R.string.favourite_added, "%s")
    val favouriteRemovedMessage = stringResource(R.string.favourite_removed, "%s")
    val deletedMessage = stringResource(R.string.owned_deleted, "%s")
    val deleteFailedMessage = stringResource(R.string.owned_delete_failed, "%s")
    val collectionAddedMessage = stringResource(R.string.collection_added, "%s")

    val download: (DownloadableFileWithTags, DownloadCondition?) -> Unit = { target, condition ->
        if (target.file.fileName in active) showMessage(alreadyMessage.format(target.file.name))
        else scope.launch { if (viewModel.download(target.file, context, condition)) showMessage(startedMessage.format(target.file.name)) }
    }
    val toggleFavourite: () -> Unit = {
        scope.launch {
            val starred = viewModel.toggleFavourite(item)
            showMessage((if (starred) favouriteAddedMessage else favouriteRemovedMessage).format(stripExtension(rom.name)))
        }
    }

    var showWhen by remember { mutableStateOf(false) }
    var showCollections by remember { mutableStateOf(false) }
    var removalPlan by remember { mutableStateOf<List<com.cortinadev.dogmatix.data.service.RemovalFile>?>(null) }
    var launchChoices by remember { mutableStateOf<List<com.cortinadev.dogmatix.data.service.GameLaunch>?>(null) }
    var rememberEmulator by rememberSaveable { mutableStateOf(true) }
    var staleChoice by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    val planError = stringResource(R.string.recovery_action_failed)
    val missingEmulator = stringResource(R.string.play_no_handler)
    val prepareRemoval: () -> Unit = {
        if (!preparing) scope.launch {
            preparing = true
            try { removalPlan = viewModel.removalPlan(item) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { showMessage(planError) }
            finally { preparing = false }
        }
    }
    val needsExtractMessage = stringResource(R.string.play24_needs_extract, "%s")
    val launchFailedMessage = stringResource(R.string.play24_launch_failed, "%s")
    // Starts [handler]; false when it did not start (the reason is shown).
    val start: (com.cortinadev.dogmatix.data.service.GameLaunch, com.cortinadev.dogmatix.data.service.GameHandler, Boolean, Boolean) -> Boolean = { game, handler, remember, automatic ->
        when (viewModel.gameLauncher.launch(context, rom.consoleId, game, handler, remember, automatic)) {
            com.cortinadev.dogmatix.data.service.LaunchOutcome.STARTED -> true
            com.cortinadev.dogmatix.data.service.LaunchOutcome.NEEDS_EXTRACT -> { showMessage(needsExtractMessage.format(handler.label)); false }
            com.cortinadev.dogmatix.data.service.LaunchOutcome.FAILED -> { showMessage(launchFailedMessage.format(handler.label)); false }
        }
    }
    val play: () -> Unit = {
        if (!preparing) scope.launch {
            preparing = true
            try {
                val choices = viewModel.gameLauncher.choices(rom)
                val preferred = viewModel.gameLauncher.preferred(rom.consoleId)
                // One file, or a playlist that holds every disc: the remembered app starts it directly.
                val single = choices.singleOrNull() ?: choices.firstOrNull()?.takeIf { it.name.endsWith(".m3u", ignoreCase = true) }
                val handler = single?.let { viewModel.gameLauncher.resolve(it, preferred) }
                if (choices.isEmpty() || choices.all { it.handlers.isEmpty() }) showMessage(missingEmulator)
                else if (single != null && handler != null) {
                    // A remembered app that fails is no dead end: the chooser opens.
                    if (!start(single, handler, false, false)) { staleChoice = false; launchChoices = choices }
                } else {
                    // A remembered app that is not offered any more (uninstalled): say so above the choices.
                    staleChoice = preferred != null && choices.none { viewModel.gameLauncher.resolve(it, preferred) != null }
                    launchChoices = choices
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { showMessage(planError) }
            finally { preparing = false }
        }
    }
    if (showWhen) {
        DownloadWhenDialog(onDismiss = { showWhen = false }, onConfirm = { condition -> showWhen = false; download(item, condition) })
    }
    if (showCollections) {
        CollectionPickerDialog(collections = collections, selected = state.collectionIds,
            onToggle = { id -> scope.launch { viewModel.toggleCollection(item, id) } },
            onCreate = { name -> scope.launch { if (viewModel.createCollectionWith(item, name)) showMessage(collectionAddedMessage.format(name.trim())) } },
            onDismiss = { showCollections = false })
    }
    removalPlan?.let { plan ->
        val cancelFocus = rememberInitialFocus()
        AlertDialog(modifier = Modifier.closeOnGamepadB { removalPlan = null }, onDismissRequest = { removalPlan = null },
            title = { Text(stringResource(R.string.recovery_remove_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.recovery_remove_hint))
                plan.forEach { Text("${it.path} · ${formatBytes(it.bytes)}", style = MaterialTheme.typography.bodySmall) }
                if (plan.isEmpty()) Text(stringResource(R.string.recovery_no_files))
            } },
            confirmButton = { DialogButton(stringResource(R.string.recovery_to_trash), onClick = {
                removalPlan = null
                scope.launch {
                    try {
                        val ok = viewModel.remove(plan, rom.name)
                        showMessage((if (ok) deletedMessage else deleteFailedMessage).format(rom.name))
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { showMessage(planError) }
                }
            }) },
            dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), { removalPlan = null }, initialFocus = cancelFocus) })
    }
    launchChoices?.let { choices ->
        val cancelFocus = rememberInitialFocus()
        val pick: (com.cortinadev.dogmatix.data.service.GameLaunch, com.cortinadev.dogmatix.data.service.GameHandler, Boolean) -> Unit = { choice, handler, automatic ->
            try { if (start(choice, handler, rememberEmulator, automatic)) launchChoices = null }
            catch (_: Exception) { showMessage(missingEmulator) }
        }
        AlertDialog(modifier = Modifier.closeOnGamepadB { launchChoices = null }, onDismissRequest = { launchChoices = null },
            title = { Text(stringResource(R.string.play_choose)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (staleChoice) Text(stringResource(R.string.play24_remembered_missing), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberEmulator, { rememberEmulator = it })
                    Text(stringResource(R.string.play_remember), style = MaterialTheme.typography.bodySmall)
                }
                // Automatic: the best app offered (a known emulator first), now and, when remembered, every time.
                val best = choices.firstOrNull { it.handlers.isNotEmpty() }
                best?.let { game -> ActionPill(stringResource(R.string.play24_automatic, game.handlers.first().label), { pick(game, game.handlers.first(), true) }, icon = R.drawable.ic_controller) }
                choices.forEach { choice ->
                    Text(choice.name, style = MaterialTheme.typography.bodyMedium)
                    choice.handlers.forEach { handler ->
                        ActionPill(handler.label, { pick(choice, handler, false) }, icon = R.drawable.ic_controller)
                    }
                }
                if (choices.any { c -> c.handlers.any { it.core != null } }) Text(stringResource(R.string.play24_core_hint), style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = {},
            dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), { launchChoices = null }, initialFocus = cancelFocus) })
    }

    // LB / RB switch tabs, Select stars the game (the shell keeps ZL / ZR for the app's sections).
    val currentTabs by rememberUpdatedState(tabs)
    val currentToggle by rememberUpdatedState(toggleFavourite)
    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            when (button) {
                GamepadButton.PREV_PANEL -> tabName = GamePageModel.step(currentTabs, Tab.valueOf(tabName), -1).name
                GamepadButton.NEXT_PANEL -> tabName = GamePageModel.step(currentTabs, Tab.valueOf(tabName), 1).name
                GamepadButton.FAVOURITE -> currentToggle()
                else -> Unit
            }
        }
    }
    PublishLegend(
        listOfNotNull(
            LegendEntry("A", stringResource(R.string.pad_select)),
            LegendEntry("B", stringResource(R.string.pad_back)),
            if (tabs.size > 1) LegendEntry("LB · RB", stringResource(R.string.page8_pad_tabs)) else null,
            LegendEntry("SELECT", stringResource(R.string.pad_favourite)),
            LegendEntry("▲ ▼", stringResource(R.string.pad_scroll))
        )
    )

    // The main button takes the focus when the page opens (the back pill while it is disabled).
    val primary = GamePageModel.primary(owned, downloading)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(rom.consoleId, rom.fileName) {
        repeat(5) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    val header: @Composable () -> Unit = {
        Hero(
            state = state,
            owned = owned,
            downloading = downloading,
            favourite = favourite,
            onRomm = onRomm,
            isLandscape = isLandscape,
            onBack = onBack,
            backModifier = Modifier
        )
    }
    val actions: @Composable () -> Unit = {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            // One button whose label changes, so focus stays on it when a download starts.
            val busy = primary == GamePageModel.Primary.DOWNLOADING
            PrimaryButton(
                stringResource(
                    when (primary) {
                        GamePageModel.Primary.DOWNLOAD -> R.string.details_download
                        GamePageModel.Primary.DOWNLOAD_AGAIN -> R.string.owned_download_again
                        GamePageModel.Primary.DOWNLOADING -> R.string.page8_downloading
                        GamePageModel.Primary.PLAY -> R.string.play_game
                    }
                ),
                onClick = { if (!busy && !preparing) { if (owned) play() else download(item, null) } },
                modifier = Modifier.focusRequester(firstFocus),
                icon = if (busy) R.drawable.ic_downloading else if (owned) R.drawable.ic_controller else R.drawable.ic_download
            )
            if (owned && !downloading) {
                ActionPill(stringResource(R.string.owned_download_again), { download(item, null) }, icon = R.drawable.ic_download)
                ActionPill(stringResource(R.string.play_change_handler), { viewModel.gameLauncher.clear(rom.consoleId); play() }, icon = R.drawable.ic_settings)
            }
            val switch = state.switch
            val best = state.best
            GamePageModel.actions(
                owned = owned,
                downloading = downloading,
                betterVersion = best != null && best.file.fileName != rom.fileName,
                updateAvailable = switch?.updateAvailable == true && switch.newestUpdate != null,
                missingDlc = switch?.missingDlc?.size ?: 0
            ).forEach { action ->
                when (action) {
                    GamePageModel.Action.DOWNLOAD_BEST -> best?.let {
                        ActionPill(stringResource(R.string.details_download_best), { download(it, null) }, icon = R.drawable.ic_award)
                    }
                    GamePageModel.Action.UPDATE -> switch?.newestUpdate?.first?.let { row ->
                        ActionPill(stringResource(R.string.details_switch_get_update), { download(DownloadableFileWithTags(row, emptyList()), null) }, icon = R.drawable.ic_sync, tone = ActionTone.Accent)
                    }
                    GamePageModel.Action.DLC -> switch?.missingDlc?.let { rows ->
                        ActionPill(
                            stringResource(R.string.details_switch_get_dlc, rows.size),
                            { rows.forEach { row -> download(DownloadableFileWithTags(row, emptyList()), null) } },
                            icon = R.drawable.ic_plus, tone = ActionTone.Accent
                        )
                    }
                    GamePageModel.Action.DOWNLOAD_WHEN -> ActionPill(stringResource(R.string.plan6_wait_for), { showWhen = true }, icon = R.drawable.ic_schedule)
                    GamePageModel.Action.FAVOURITE -> ActionPill(
                        stringResource(if (favourite) R.string.home_details_unfavourite else R.string.home_details_favourite),
                        toggleFavourite,
                        icon = R.drawable.ic_star,
                        tone = if (favourite) ActionTone.Accent else ActionTone.Neutral
                    )
                    GamePageModel.Action.COLLECTIONS -> ActionPill(
                        if (state.collectionIds.isEmpty()) stringResource(R.string.details_collections)
                        else pluralStringResource(R.plurals.details_in_collections, state.collectionIds.size, state.collectionIds.size),
                        { showCollections = true },
                        icon = R.drawable.ic_collections
                    )
                    GamePageModel.Action.SHARE -> ActionPill(
                        stringResource(R.string.lead6_share),
                        { scope.launch { GameShare.share(context, rom.consoleId, rom.fileName, rom.name) } },
                        icon = R.drawable.ic_share
                    )
                    GamePageModel.Action.REMOVE -> ActionPill(stringResource(R.string.page8_remove), { prepareRemoval() }, icon = R.drawable.ic_trash, tone = ActionTone.Danger)
                }
            }
        }
    }
    val tabStrip: @Composable () -> Unit = {
        if (tabs.size > 1) TabStrip(tabs, tab) { tabName = it.name }
    }
    val content: @Composable () -> Unit = {
        TabContent(
            tab = tab,
            state = state,
            ownedOf = { viewModel.isOwned(it.file, ownedKeys) },
            downloadingOf = { it.file.fileName in active },
            onDownload = { download(it, null) },
            onOpenGame = onOpenGame,
            viewModel = viewModel
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLandscape) {
            val heroScroll = rememberScrollState()
            val panelScroll = rememberScrollState()
            // A new tab starts at its top.
            LaunchedEffect(tab) { panelScroll.scrollTo(0) }
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .dpadScroll(heroScroll)
                        .verticalScroll(heroScroll)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    header()
                    actions()
                }
                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .padding(start = 8.dp, end = 16.dp, top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    tabStrip()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .dpadScroll(panelScroll)
                            .verticalScroll(panelScroll)
                            .padding(bottom = 16.dp)
                    ) { content() }
                }
            }
        } else {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .dpadScroll(scroll)
                    .verticalScroll(scroll)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                header()
                actions()
                Box(modifier = Modifier.padding(horizontal = 16.dp)) { tabStrip() }
                Box(modifier = Modifier.padding(horizontal = 16.dp)) { content() }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/**
 * The page's top: the art softened over the console's colour (the same backdrop as the details card),
 * a back pill, the cover, the title, the info pills and the language / region tags.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(
    state: DetailsState,
    owned: Boolean,
    downloading: Boolean,
    favourite: Boolean,
    onRomm: Boolean,
    isLandscape: Boolean,
    onBack: () -> Unit,
    backModifier: Modifier
) {
    val rom = state.item.file
    val (frontUrl, backdropUrl) = rememberGameArt(state)
    val title = state.details?.title?.takeIf { it.isNotBlank() } ?: stripExtension(rom.name)
    val consoleName = ConsoleFormatter.getConsoleShortName(rom.consoleId)
    // The info pills already carry the first region; the other regions and the languages follow.
    val extraTags = remember(state.item.tags) {
        val firstRegion = state.item.tags.firstOrNull { TagClassifier.kindOf(it) == TagClassifier.Kind.REGION }
        GamePageModel.heroTags(state.item.tags).filter { it != firstRegion }
    }
    val pills: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GameInfoPills(state, consoleName, onRomm, owned, downloading, favourite)
            if (extraTags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                extraTags.forEach { Pill(it, tone = PillTone.Neutral, icon = R.drawable.ic_translate) }
            }
        }
    }
    GameHero(backdropUrl, rom.consoleId, Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionPill(stringResource(R.string.page8_back), onBack, modifier = backModifier, icon = R.drawable.ic_chevron_left)
            if (isLandscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    GameHeroCover(frontUrl, rom.consoleId, height = 168.dp, maxRatio = 1.2f)
                    Column(modifier = Modifier.weight(1f)) { GameTitle(title, maxLines = 4) }
                }
                pills()
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    GameHeroCover(frontUrl, rom.consoleId, height = 184.dp, maxRatio = 1.0f)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GameTitle(title, maxLines = 4)
                    }
                }
                pills()
            }
        }
    }
}

/** The tab pills (touch, or LB / RB); the selected one is accent-filled. */
@Composable
private fun TabStrip(tabs: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { t ->
            val (label, icon) = when (t) {
                Tab.ABOUT -> R.string.page8_tab_about to R.drawable.ic_info
                Tab.VERSIONS -> R.string.page8_tab_versions to R.drawable.ic_stacks
                Tab.PROGRESS -> R.string.page8_tab_progress to R.drawable.ic_trophy
                Tab.SIMILAR -> R.string.page8_tab_similar to R.drawable.ic_sparkle
            }
            ActionPill(
                stringResource(label),
                onClick = { onSelect(t) },
                icon = icon,
                tone = if (t == selected) ActionTone.Accent else ActionTone.Neutral
            )
        }
    }
}

@Composable
private fun TabContent(
    tab: Tab,
    state: DetailsState,
    ownedOf: (DownloadableFileWithTags) -> Boolean,
    downloadingOf: (DownloadableFileWithTags) -> Boolean,
    onDownload: (DownloadableFileWithTags) -> Unit,
    onOpenGame: (String, String) -> Unit,
    viewModel: GamePageViewModel
) {
    val rom = state.item.file
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (tab) {
            Tab.ABOUT -> {
                GameFacts(state)
                GameDescription(state)
                // RomM's summary, genres, screenshots, play status and rating (draws nothing without RomM).
                RommGameSection(
                    consoleId = rom.consoleId,
                    fileName = rom.fileName,
                    showSummary = state.details?.description.isNullOrBlank()
                )
                state.details?.let { details ->
                    Text(stringResource(R.string.details_source, details.source), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
            Tab.VERSIONS -> Versions(state, ownedOf, downloadingOf, onDownload, viewModel)
            Tab.PROGRESS -> {
                AchievementsSection(consoleId = rom.consoleId, fileName = rom.fileName, title = rom.name, match = state.achievements)
                CloudSavesSection(consoleId = rom.consoleId, fileName = rom.fileName)
                Text(stringResource(R.string.page8_progress_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Tab.SIMILAR -> SimilarSection(state.similar, onOpen = { onOpenGame(it.file.consoleId, it.file.fileName) })
        }
    }
}

/** Every version of the game in the sources: best match first, then this one; A downloads a row. */
@Composable
private fun Versions(
    state: DetailsState,
    ownedOf: (DownloadableFileWithTags) -> Boolean,
    downloadingOf: (DownloadableFileWithTags) -> Boolean,
    onDownload: (DownloadableFileWithTags) -> Unit,
    viewModel: GamePageViewModel
) {
    val preferred by viewModel.preferred.collectAsState()
    val languages by viewModel.languages.collectAsState()
    val datReports by viewModel.datReports.collectAsState()
    val scope = rememberCoroutineScope()
    val current = state.item.file.fileName
    val ordered = remember(state.versions, state.bestFileName, current) {
        GamePageModel.orderVersions(state.versions.ifEmpty { listOf(state.item) }, { it.file.fileName }, current, state.bestFileName)
    }
    Text(stringResource(R.string.page8_versions_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Panel(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ordered.forEach { version ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            VersionRow(
                version = version,
                isCurrent = version.file.fileName == current,
                isBest = version.file.fileName == state.bestFileName,
                owned = ownedOf(version),
                downloading = downloadingOf(version),
                onClick = { if (!downloadingOf(version)) onDownload(version) }
            )
            val ranking = com.cortinadev.dogmatix.util.VersionPicker.score(
                com.cortinadev.dogmatix.util.VersionPicker.Candidate(version.file.fileName, version.file.fileName, version.tags, version.file.fileSize),
                com.cortinadev.dogmatix.util.VersionPicker.regionPreference(languages), languages)
            val labels = ranking.notes.map { note ->
                when (note) {
                    "language" -> stringResource(R.string.version_language)
                    "verified" -> stringResource(R.string.version_dump_claim)
                    "unwanted" -> stringResource(R.string.version_unwanted)
                    else -> if (note.startsWith("revision:")) stringResource(R.string.version_revision, note.substringAfter(":")) else note
                }
            }
            val dat = datReports[version.file.consoleId]?.checks?.firstOrNull { it.first.name.equals(version.file.fileName, true) }?.second?.status
            Text(labels.joinToString(" · ") + " · " + stringResource(R.string.version_dat, dat?.name ?: stringResource(R.string.version_unknown)),
                modifier = Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall)
            if (version.file.fileName == current && state.achievements != null) Text(stringResource(if (state.achievements.second) R.string.version_ra_hash else R.string.version_ra_title), modifier = Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall)
            ActionPill(stringResource(if (preferred == version.file.fileName) R.string.version_unpin else R.string.version_pin),
                { scope.launch { viewModel.setPreferred(version, preferred != version.file.fileName) } }, icon = R.drawable.ic_star)
            }

        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersionRow(
    version: DownloadableFileWithTags,
    isCurrent: Boolean,
    isBest: Boolean,
    owned: Boolean,
    downloading: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val file = version.file
    val name = remember(file.fileName) { stripExtension(FileParsingUtils.decodeUrlEncodedFileName(file.fileName)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(source, 10.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            TagRow(
                console = ConsoleFormatter.getConsoleShortName(file.consoleId),
                tags = version.tags,
                extension = file.fileExtension,
                maxLines = 1,
                consoleId = file.consoleId
            )
            if (isCurrent || isBest || owned || downloading) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isBest) Pill(stringResource(R.string.page8_version_best), tone = PillTone.Accent, icon = R.drawable.ic_award)
                    if (isCurrent) Pill(stringResource(R.string.page8_version_this), tone = PillTone.Info, icon = R.drawable.ic_check)
                    if (downloading) Pill(stringResource(R.string.downloading_badge), tone = PillTone.Accent, icon = R.drawable.ic_download)
                    else if (owned) Pill(stringResource(R.string.home_pill_on_device), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                }
            }
        }
        if (file.fileSize > 0) {
            Text(formatBytes(file.fileSize), style = MaterialTheme.typography.labelMedium.tabular(), color = scheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
    }
}

/**
 * ▲ ▼ move the focus first; where it cannot move (long text below the last button) they scroll
 * [scroll] instead, so nothing on the page needs the touch screen.
 */
@Composable
private fun Modifier.dpadScroll(scroll: ScrollState): Modifier {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val reduce = LocalReduceMotion.current
    return this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val step = when (event.key) {
            Key.DirectionUp -> -SCROLL_STEP
            Key.DirectionDown -> SCROLL_STEP
            else -> return@onPreviewKeyEvent false
        }
        val direction = if (step < 0) FocusDirection.Up else FocusDirection.Down
        if (focusManager.moveFocus(direction)) return@onPreviewKeyEvent true
        val canScroll = if (step < 0) scroll.value > 0 else scroll.value < scroll.maxValue
        if (!canScroll) return@onPreviewKeyEvent false
        scope.launch { if (reduce) scroll.scrollBy(step) else scroll.animateScrollBy(step) }
        true
    }
}

private const val SCROLL_STEP = 160f
