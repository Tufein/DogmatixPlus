package com.cortinadev.dogmatix.ui.screens.home

import com.cortinadev.dogmatix.ui.theme.accentInk
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.cortinadev.dogmatix.ui.screens.cloud.saves.CloudSavesSection
import com.cortinadev.dogmatix.ui.screens.cloud.saves.ContinuePlayingShelf
import com.cortinadev.dogmatix.ui.screens.cloud.sections.AchievementsSection
import com.cortinadev.dogmatix.ui.screens.cloud.sections.RommGameSection
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.EmptyState
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.PrimaryButton
import com.cortinadev.dogmatix.ui.components.pillColors
import com.cortinadev.dogmatix.ui.screens.home.components.CoverGap
import com.cortinadev.dogmatix.ui.screens.home.components.DenseRowGap
import com.cortinadev.dogmatix.ui.screens.home.components.DiscoverBlock
import com.cortinadev.dogmatix.ui.screens.home.components.RowGap
import com.cortinadev.dogmatix.util.LibraryDiscovery
import com.cortinadev.dogmatix.ui.screens.home.components.TableCoverWidth
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.consoleColor
import com.cortinadev.dogmatix.ui.theme.tabular
import com.cortinadev.dogmatix.util.Constants
import java.text.NumberFormat
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layoutId
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Spacer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.SortOption
import com.cortinadev.dogmatix.data.model.SourceFilter
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.common.Legend
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.swapFaceButtons
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.screens.home.components.BulkDownloadDialog
import com.cortinadev.dogmatix.ui.screens.home.components.CollectionPickerDialog
import com.cortinadev.dogmatix.ui.screens.home.components.FilterOption
import com.cortinadev.dogmatix.ui.screens.home.components.FilterPanel
import com.cortinadev.dogmatix.ui.screens.home.components.FilterRowSpec
import com.cortinadev.dogmatix.ui.screens.home.components.GameDetailsDialog
import com.cortinadev.dogmatix.ui.screens.home.components.RomRow
import com.cortinadev.dogmatix.ui.screens.home.components.SaveViewDialog
import com.cortinadev.dogmatix.ui.screens.home.components.SearchField
import com.cortinadev.dogmatix.ui.secondscreen.SecondScreenState
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.LetterJump
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val FAV_ALL = "all"
private const val FAV_ONLY = "only"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val results by viewModel.results.collectAsState()
    val hasMoreResults by viewModel.hasMoreResults.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val recentSearches by viewModel.recentSearches.collectAsState()
    val selectedConsoles by viewModel.selectedConsoles.collectAsState()
    val activeTags by viewModel.activeTags.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val consolesWithFiles by viewModel.consolesWithFiles.collectAsState()
    val categorizedTags by viewModel.categorizedTags.collectAsState()
    val ownedKeys by viewModel.ownedKeys.collectAsState()
    val rommKeys by viewModel.rommKeys.collectAsState()
    val rommBase by viewModel.rommBase.collectAsState()
    val favouriteKeys by viewModel.favouriteKeys.collectAsState()
    val favouritesOnly by viewModel.favouritesOnly.collectAsState()
    val sourceFilter by viewModel.source.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val favoriteLanguages by viewModel.favoriteLanguages.collectAsState()
    val detailsState by viewModel.details.collectAsState()
    val raMarks by viewModel.raMarks.collectAsState()
    val newOnly by viewModel.newOnly.collectAsState()
    val collectionId by viewModel.collectionId.collectAsState()
    val collections by viewModel.collections.collectAsState()
    // 5.0: covers in the rows, the live download ring, placeholder rows and the empty-library state.
    val listCovers by viewModel.listCovers.collectAsState()
    val loadMoreSize by viewModel.loadMoreSize.collectAsState()
    val compactLists by viewModel.compactLists.collectAsState()
    // 6.0 search by feel: genre / decade from the cached details, and the opt-in fetch.
    val genres by viewModel.genres.collectAsState()
    val decades by viewModel.decades.collectAsState()
    val discoverIndex by viewModel.discoverIndex.collectAsState()
    val fetchState by viewModel.fetchState.collectAsState()
    // These three change often (or only matter in one corner): they are read where they are drawn
    // or in ResultList's empty branch, never here, so a change does not recompose this screen.
    val downloadProgressState = viewModel.downloadProgress.collectAsState()
    val isSearchingState = viewModel.isSearching.collectAsState()
    val libraryEmptyState = viewModel.libraryEmpty.collectAsState()
    val progressOf: (String) -> Float = remember(downloadProgressState) { { name: String -> downloadProgressState.value[name] ?: 0f } }
    val isSearching: () -> Boolean = remember(isSearchingState) { { isSearchingState.value } }
    val libraryEmpty: () -> Boolean = remember(libraryEmptyState) { { libraryEmptyState.value } }
    var showBulk by remember { mutableStateOf(false) }
    val views by viewModel.views.collectAsState()
    var savingView by remember { mutableStateOf(false) }
    var showCollectionPicker by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarJob by remember { mutableStateOf<Job?>(null) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val searchFocus = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    var searchActive by remember { mutableStateOf(false) }
    var filtersHaveFocus by remember { mutableStateOf(false) }
    var listHasFocus by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    // Landscape: the filter panel folds into a thin rail (chevron or R3) to give the list more room.
    var filtersCollapsed by rememberSaveable { mutableStateOf(false) }
    var railHasFocus by remember { mutableStateOf(false) }
    // The list is laid out at its wide (rail) width while the panel is folded *and* while it
    // is sliding back in, so the panel covers it progressively; it narrows once the slide ends.
    var listWide by rememberSaveable { mutableStateOf(filtersCollapsed) }
    var expandedFilter by remember { mutableStateOf<String?>(null) }
    // Row under the D-pad cursor: X opens its details card.
    var focusedItem by remember { mutableStateOf<DownloadableFileWithTags?>(null) }
    val focusManager = LocalFocusManager.current
    // The game under the cursor also goes to a second screen, when there is one.
    LaunchedEffect(focusedItem) { SecondScreenState.focus(focusedItem) }
    // With touch there is no cursor: the game whose details are open goes to the second screen.
    LaunchedEffect(detailsState?.item) { detailsState?.item?.let { SecondScreenState.focus(it) } }
    // Folding hides whichever side holds the focus, so it is handed over explicitly
    // (two frames later when expanding: the panel has to be laid out first).
    val collapseFilters = {
        filtersCollapsed = true
        listWide = true
        if (filtersHaveFocus) runCatching { listFocus.requestFocus() }
    }
    val expandFilters = {
        filtersCollapsed = false
        if (railHasFocus) scope.launch { withFrameNanos { }; withFrameNanos { }; runCatching { filterFocus.requestFocus() } }
    }

    val consoleOptions = remember(consolesWithFiles) {
        consolesWithFiles.map {
            FilterOption(
                it.id, ConsoleFormatter.getConsoleFolderName(it.id), ConsoleFormatter.getConsoleShortName(it.id),
                count = it.fileCount, color = consoleColor(it.id)
            )
        }
    }
    fun tagRow(label: String, tags: List<String>, featured: Set<String>? = null) = FilterRowSpec(
        label = label,
        options = tags.map { FilterOption(it, it) },
        selected = activeTags.filter { it in tags }.toSet(),
        featured = featured,
        onSelectionChange = { viewModel.setTagsInCategory(tags, it) }
    )
    // 6.0: only listed when at least one title has cached details; the options are what is really known.
    val genreLabel = stringResource(R.string.disc6_filter_genre)
    val decadeLabel = stringResource(R.string.disc6_filter_decade)
    val discoverRows = if (discoverIndex.knownTitles == 0) emptyList() else listOf(
        FilterRowSpec(
            label = genreLabel,
            options = discoverIndex.genres.take(80).map { (g, n) -> FilterOption(g, g, count = n) },
            selected = genres,
            onSelectionChange = viewModel::setGenres
        ),
        FilterRowSpec(
            label = decadeLabel,
            options = discoverIndex.decades.map { (d, n) -> FilterOption(d.toString(), stringResource(R.string.disc6_decade_label, d), count = n) },
            selected = decades.map { it.toString() }.toSet(),
            onSelectionChange = { sel -> viewModel.setDecades(sel.mapNotNull { it.toIntOrNull() }.toSet()) }
        )
    )
    val filterRows = listOf(
        FilterRowSpec(
            label = stringResource(R.string.filter_console),
            options = consoleOptions,
            selected = selectedConsoles,
            onSelectionChange = viewModel::setConsoleSelection
        ),
        tagRow(stringResource(R.string.filter_region), categorizedTags?.regions?.tags.orEmpty()),
        tagRow(stringResource(R.string.filter_language), categorizedTags?.languages?.tags.orEmpty(), featured = favoriteLanguages),
        tagRow(stringResource(R.string.filter_tag), categorizedTags?.contentTypes?.tags.orEmpty()),
    ) + discoverRows + listOf(
        FilterRowSpec(
            label = stringResource(R.string.filter_favourites),
            options = listOf(
                FilterOption(FAV_ALL, stringResource(R.string.filter_all)),
                FilterOption(FAV_ONLY, stringResource(R.string.favourites_only), stringResource(R.string.favourites_only_short))
            ),
            selected = setOf(if (favouritesOnly) FAV_ONLY else FAV_ALL),
            single = true,
            onSelectionChange = { viewModel.setFavouritesOnly(FAV_ONLY in it) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.filter_new),
            options = listOf(
                FilterOption(FAV_ALL, stringResource(R.string.filter_all)),
                FilterOption(FAV_ONLY, stringResource(R.string.filter_new_only), stringResource(R.string.filter_new_only_short))
            ),
            selected = setOf(if (newOnly) FAV_ONLY else FAV_ALL),
            single = true,
            onSelectionChange = { viewModel.setNewOnly(FAV_ONLY in it) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.filter_collection),
            options = listOf(FilterOption("0", stringResource(R.string.filter_all))) + collections.map { FilterOption(it.id.toString(), it.name) },
            selected = setOf(collectionId.toString()),
            single = true,
            onSelectionChange = { sel -> viewModel.setCollection(sel.firstOrNull()?.toLongOrNull() ?: 0L) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.filter_view),
            options = listOf(FilterOption("", "—")) + views.map { FilterOption(it.id, it.name) },
            selected = setOf(viewModel.matchingViewId().orEmpty()),
            single = true,
            onSelectionChange = { sel -> views.firstOrNull { it.id == sel.firstOrNull() }?.let(viewModel::applyView) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.filter_source),
            options = listOf(
                FilterOption(SourceFilter.ALL.name, stringResource(R.string.filter_all)),
                FilterOption(SourceFilter.TORRENT.name, stringResource(R.string.source_torrent)),
                FilterOption(SourceFilter.ROMM.name, stringResource(R.string.source_romm)),
                FilterOption(SourceFilter.DIRECT.name, stringResource(R.string.source_direct))
            ),
            selected = setOf(sourceFilter.name),
            single = true,
            onSelectionChange = { sel -> viewModel.setSource(sel.firstOrNull()?.let { SourceFilter.valueOf(it) } ?: SourceFilter.ALL) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.filter_sort),
            options = listOf(
                FilterOption(SortOption.NAME_ASC.name, stringResource(R.string.sort_name_asc)),
                FilterOption(SortOption.NAME_DESC.name, stringResource(R.string.sort_name_desc)),
                FilterOption(SortOption.SIZE_DESC.name, stringResource(R.string.sort_size_desc), stringResource(R.string.sort_size_desc_short)),
                FilterOption(SortOption.SIZE_ASC.name, stringResource(R.string.sort_size_asc), stringResource(R.string.sort_size_asc_short)),
                FilterOption(SortOption.NEWEST.name, stringResource(R.string.sort_newest), stringResource(R.string.sort_newest_short))
            ),
            selected = setOf(sort.name),
            single = true,
            onSelectionChange = { sel -> viewModel.setSort(sel.firstOrNull()?.let { SortOption.valueOf(it) } ?: SortOption.NAME_ASC) }
        )
    )
    val activeFilterCount = selectedConsoles.size + activeTags.size + (if (favouritesOnly) 1 else 0) + (if (sourceFilter != SourceFilter.ALL) 1 else 0) +
        (if (newOnly) 1 else 0) + (if (collectionId != 0L) 1 else 0) +
        LibraryDiscovery.Filter(genres, decades).activeCount
    // 5.0 empty states: the library has nothing yet → Sources (as a tab switch, like the top tabs do);
    // nothing matches → clear what narrows the list.
    val goToSources: () -> Unit = {
        navController.navigate(NavRoutes.Sources.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val discoverBlock: @Composable () -> Unit = {
        DiscoverBlock(
            knownTitles = discoverIndex.knownTitles,
            libraryFiles = consolesWithFiles.sumOf { it.fileCount },
            fetch = fetchState,
            canFetch = results.isNotEmpty(),
            onFetch = viewModel::fetchDetailsForShown
        )
    }
    val clearFilters: (() -> Unit)? =
        if (activeFilterCount > 0 || query.isNotEmpty() || sort != SortOption.NAME_ASC) viewModel::clearAllFilters else null

    val startedMessage = stringResource(R.string.download_started, "%s")
    val favouriteAddedMessage = stringResource(R.string.favourite_added, "%s")
    val favouriteRemovedMessage = stringResource(R.string.favourite_removed, "%s")
    val deletedMessage = stringResource(R.string.owned_deleted, "%s")
    val deleteFailedMessage = stringResource(R.string.owned_delete_failed, "%s")
    val alreadyDownloadingMessage = stringResource(R.string.download_already_active, "%s")
    val showMessage: (String) -> Unit = { message ->
        snackbarJob?.cancel()
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarJob = scope.launch {
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }
    val download: (DownloadableFileWithTags) -> Unit = { item ->
        scope.launch { viewModel.startDownload(item, context) }
        showMessage(startedMessage.format(item.file.name))
    }
    val toggleFavourite: (DownloadableFileWithTags) -> Unit = { item ->
        scope.launch {
            val starred = viewModel.toggleFavourite(item)
            showMessage((if (starred) favouriteAddedMessage else favouriteRemovedMessage).format(stripExtension(item.file.name)))
        }
    }
    val wishAddedMessage = stringResource(R.string.wishlist_added, "%s")
    val wishExistsMessage = stringResource(R.string.wishlist_exists)
    val addToWishlist: () -> Unit = {
        val title = query.trim()
        scope.launch { showMessage(if (viewModel.addToWishlist(title)) wishAddedMessage.format(title) else wishExistsMessage) }
    }
    // Tapping a game already on disk asks whether to fetch it again or remove it.
    var ownedDialogItem by remember { mutableStateOf<DownloadableFileWithTags?>(null) }
    val onFileClick: (DownloadableFileWithTags) -> Unit = { item ->
        when {
            viewModel.isDownloading(item.file, activeDownloads) -> showMessage(alreadyDownloadingMessage.format(item.file.name))
            viewModel.isOwned(item.file, ownedKeys) -> ownedDialogItem = item
            else -> download(item)
        }
    }

    ownedDialogItem?.let { item ->
        AlertDialog(
            onDismissRequest = { ownedDialogItem = null },
            title = { Text(stripExtension(item.file.name)) },
            text = { Text(stringResource(R.string.owned_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    ownedDialogItem = null
                    download(item)
                }) { Text(stringResource(R.string.owned_download_again)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    ownedDialogItem = null
                    scope.launch {
                        val ok = viewModel.deleteOwned(item)
                        showMessage((if (ok) deletedMessage else deleteFailedMessage).format(item.file.name))
                    }
                }) { Text(stringResource(R.string.owned_delete)) }
            }
        )
    }

    val viewSavedMessage = stringResource(R.string.view_saved, "%s")
    if (savingView) {
        SaveViewDialog(
            onSave = { name -> savingView = false; scope.launch { if (viewModel.saveView(name)) showMessage(viewSavedMessage.format(name.trim())) } },
            onDismiss = { savingView = false }
        )
    }
    val bulkQueuedMessage = stringResource(R.string.bulk_queued, "%d")
    if (showBulk) {
        BulkDownloadDialog(
            plan = { viewModel.planBulk(it) },
            onConfirm = { plan ->
                showBulk = false
                scope.launch { showMessage(bulkQueuedMessage.replace("%d", viewModel.startBulk(plan, context).toString())) }
            },
            onDismiss = { showBulk = false },
            reclaimable = { viewModel.reclaimableBytes() },
            onFreeUp = { showBulk = false; navController.navigate(NavRoutes.Duplicates.route) }
        )
    }
    val collectionAddedMessage = stringResource(R.string.collection_added, "%s")
    if (showCollectionPicker) detailsState?.let { state ->
        CollectionPickerDialog(
            collections = collections,
            selected = state.collectionIds,
            onToggle = { id -> scope.launch { viewModel.toggleCollection(state.item, id) } },
            onCreate = { name -> scope.launch { if (viewModel.createCollectionWith(state.item, name)) showMessage(collectionAddedMessage.format(name.trim())) } },
            onDismiss = { showCollectionPicker = false }
        )
    }
    val updateQueuedMessage = stringResource(R.string.download_started, "%s")
    detailsState?.let { state ->
        val switch = state.switch
        GameDetailsDialog(
            onCollections = { showCollectionPicker = true },
            onDownloadUpdate = switch?.takeIf { it.updateAvailable }?.newestUpdate?.first?.let { row ->
                { viewModel.downloadRow(row); showMessage(updateQueuedMessage.format(row.name)) }
            },
            onDownloadDlc = switch?.missingDlc?.takeIf { it.isNotEmpty() }?.let { rows ->
                { rows.forEach(viewModel::downloadRow); showMessage(bulkQueuedMessage.replace("%d", rows.size.toString())) }
            },
            state = state,
            consoleName = ConsoleFormatter.getConsoleShortName(state.item.file.consoleId),
            favourite = viewModel.isFavourite(state.item.file, favouriteKeys),
            onToggleFavourite = { toggleFavourite(state.item) },
            onDownload = { viewModel.closeDetails(); onFileClick(state.item) },
            onDismiss = viewModel::closeDetails,
            onRomm = viewModel.isOnRomm(state.item.file, rommKeys, rommBase),
            similar = state.similar,
            onOpenSimilar = viewModel::openDetails,
            onShare = {
                val file = state.item.file
                scope.launch { com.cortinadev.dogmatix.data.service.GameShare.share(context, file.consoleId, file.fileName, file.name) }
            },
            onDownloadWhen = { condition ->
                viewModel.closeDetails()
                viewModel.downloadWhen(state.item.file, condition)
                showMessage(updateQueuedMessage.format(state.item.file.name))
            },
            onDownloadBest = state.best?.let { best -> { viewModel.closeDetails(); onFileClick(best) } },
            owned = viewModel.isOwned(state.item.file, ownedKeys),
            downloading = viewModel.isDownloading(state.item.file, activeDownloads),
            extraSections = {
                val file = state.item.file
                RommGameSection(
                    consoleId = file.consoleId,
                    fileName = file.fileName,
                    showSummary = state.details?.description.isNullOrBlank()
                )
                CloudSavesSection(consoleId = file.consoleId, fileName = file.fileName)
                AchievementsSection(
                    consoleId = file.consoleId,
                    fileName = file.fileName,
                    title = file.name,
                    match = state.achievements
                )
            }
        )
    }

    // Button legend follows where the focus is. Kept short: B (back) and LB / RB are left out
    // of the list and filter legends so they fit on one line even on 4:3 screens.
    val filtersKey = if (isLandscape) listOf(LegendEntry("R3", stringResource(R.string.pad_filters))) else emptyList()
    val section = LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
    val legendList = listOf(
        LegendEntry("A", stringResource(R.string.pad_download)), LegendEntry("X", stringResource(R.string.pad_details)),
        LegendEntry("Y", stringResource(R.string.pad_search)), LegendEntry("SELECT", stringResource(R.string.pad_favourite)),
        LegendEntry("◀ ▶", stringResource(R.string.pad_letters))
    ) + filtersKey + section + LegendEntry("START", stringResource(R.string.disc6_pad_surprise))
    val legendFilters = listOf(
        LegendEntry("A", stringResource(R.string.pad_options)), LegendEntry("◀ ▶", stringResource(R.string.pad_change))
    ) + filtersKey + section
    val legendDetails = listOf(
        LegendEntry("A", stringResource(R.string.pad_select)), LegendEntry("B", stringResource(R.string.pad_close)),
        LegendEntry("SELECT", stringResource(R.string.pad_favourite)), LegendEntry("▲ ▼", stringResource(R.string.pad_scroll))
    )
    val legendSearch = listOf(
        LegendEntry("A", stringResource(R.string.pad_keyboard)), LegendEntry("B", stringResource(R.string.pad_close_keyboard))
    )
    val legend = when {
        detailsState != null -> legendDetails
        searchActive -> legendSearch
        filtersHaveFocus || showFilterSheet -> legendFilters
        else -> legendList
    }
    val published = remember(legend) { Legend(legend) }
    LaunchedEffect(published) { Gamepad.legendOverride.value = published }
    // Only clear our own legend: another screen may already have published its own during the transition.
    DisposableEffect(published) { onDispose { if (Gamepad.legendOverride.value === published) Gamepad.legendOverride.value = null } }

    // B / Back undoes one layer at a time and never leaves the app from here (Home is the root).
    BackHandler {
        when {
            showFilterSheet -> showFilterSheet = false
            expandedFilter != null -> expandedFilter = null
            searchActive -> { focusManager.clearFocus(); searchActive = false }
            query.isNotEmpty() -> viewModel.setSearch("")
            showCollectionPicker -> showCollectionPicker = false
            activeFilterCount > 0 || sort != SortOption.NAME_ASC -> viewModel.clearAllFilters()
            else -> runCatching { Gamepad.sectionFocus.requestFocus() }
        }
    }

    LaunchedEffect(isLandscape) {
        Gamepad.presses.collect { button ->
            when (button) {
                // X shows the details card of the row under the cursor (LB / RB move between panels).
                GamepadButton.X -> focusedItem?.takeIf { listHasFocus }?.let { viewModel.openDetails(it) }
                // Select stars the row under the cursor, or the game whose details card is open.
                GamepadButton.FAVOURITE -> (viewModel.details.value?.item ?: focusedItem?.takeIf { listHasFocus })?.let(toggleFavourite)
                GamepadButton.Y -> searchActive = true
                // 6.0: Start = surprise me (a random game of the current list opens its details card).
                GamepadButton.START -> if (viewModel.details.value == null && !searchActive) results.randomOrNull()?.let(viewModel::openDetails)
                GamepadButton.PREV_PANEL -> if (isLandscape) {
                    filtersCollapsed = false
                    scope.launch { withFrameNanos { }; withFrameNanos { }; runCatching { filterFocus.requestFocus() } }
                } else showFilterSheet = true
                GamepadButton.TOGGLE_FILTERS -> if (isLandscape) {
                    if (filtersCollapsed) expandFilters() else collapseFilters()
                } else showFilterSheet = !showFilterSheet
                GamepadButton.NEXT_PANEL -> if (isLandscape) {
                    if (runCatching { listFocus.requestFocus() }.isFailure) focusManager.moveFocus(FocusDirection.Right)
                } else showFilterSheet = false
                else -> Unit
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLandscape) BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The panel folds into a thin rail. A full recomposition + relayout of this screen
            // costs ~150 ms on the K56 (debug build), so a toggle must trigger exactly one: panel
            // and rail stay composed (the hidden one sits off-screen and cannot take focus), every
            // child is measured once at a fixed size and the animated width is read only while
            // placing. No alpha fade either: an alpha graphicsLayer renders the panel to an
            // offscreen buffer and the GPU wait alone was ~190 ms per frame.
            val panelWidth = 216.dp
            val railWidth = 48.dp
            val dividerWidth = 1.dp
            val targetWidth = if (filtersCollapsed) railWidth else panelWidth
            val currentWidth = remember { Animatable(targetWidth, Dp.VectorConverter) }
            // Re-laying out the list at its new width is one heavy frame; let it land before
            // the slide starts so the animation itself runs at full frame rate.
            LaunchedEffect(targetWidth) {
                withFrameNanos { }
                withFrameNanos { }
                currentWidth.animateTo(targetWidth, tween(250))
                listWide = filtersCollapsed
            }
            val listWidth = maxWidth - (if (listWide) railWidth else panelWidth) - dividerWidth
            // The list is anchored to the right edge and only uncovered / covered by the panel:
            // tags and sizes are right-aligned so they never move, and the name column is
            // revealed under the sliding edge. Clipping happens in the draw phase (cheap).
            val listStart = maxWidth - listWidth
            // Left-anchored content (names, "Name" header) starts where it was and slides to its
            // final place along with the panel edge; right-anchored tags and sizes never move.
            val density = LocalDensity.current
            val contentShift = { with(density) { (currentWidth.value + dividerWidth - listStart).roundToPx() } }
            val listClip = Modifier.drawWithContent {
                val left = (currentWidth.value + dividerWidth - listStart).toPx().coerceAtLeast(0f)
                clipRect(left = left) { this@drawWithContent.drawContent() }
            }
            // The single-line table needs room for name + tags + size; on 4:3 screens
            // (or with the panel open on narrow ones) rows stack name over tags instead.
            val tableRows = listWidth >= 560.dp

            Layout(
                modifier = Modifier.fillMaxSize().clipToBounds(),
                content = {
                    // Rail underneath, panel sliding over it. Both fade over the last stretch of
                    // the slide. ModulateAlpha applies the opacity per draw call instead of
                    // rendering the panel to an offscreen buffer (that GPU wait cost ~190 ms a
                    // frame on the K56); the lambdas run in the draw phase only. The hidden one
                    // refuses focus: onEnter is evaluated per focus query, no recomposition.
                    val fadeSpan = (panelWidth - railWidth) * 0.4f
                    val panelAlpha = { ((currentWidth.value - railWidth) / fadeSpan).coerceIn(0f, 1f) }
                    // 5.0: panel and rail sit on a translucent panel tone (the glow still shows through);
                    // a plain rect drawn in the draw phase, inside the ModulateAlpha layer.
                    val sideFill = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = if (LocalDogmatixTokens.current.isDark) 0.55f else 0.65f)
                    Box(
                        modifier = Modifier
                            .layoutId("rail")
                            .graphicsLayer { alpha = 1f - panelAlpha(); compositingStrategy = CompositingStrategy.ModulateAlpha }
                            .drawBehind { drawRect(sideFill) }
                            .focusProperties { onEnter = { if (!filtersCollapsed) cancelFocusChange() } }
                            .onFocusChanged { railHasFocus = it.hasFocus }
                            .focusGroup()
                    ) {
                        FilterRail(count = activeFilterCount, onExpand = { expandFilters() })
                    }
                    Column(
                        modifier = Modifier
                            .layoutId("panel")
                            .graphicsLayer { alpha = panelAlpha(); compositingStrategy = CompositingStrategy.ModulateAlpha }
                            .drawBehind { drawRect(sideFill) }
                            .focusProperties { onEnter = { if (filtersCollapsed) cancelFocusChange() } }
                            .focusGroup()
                    ) {
                    FilterPanel(
                        rows = filterRows,
                        onClear = viewModel::clearAllFilters,
                        compact = true,
                        firstRowFocus = filterFocus,
                        expandedRow = expandedFilter,
                        onExpandedRowChange = { expandedFilter = it },
                        afterRows = discoverBlock,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { filtersHaveFocus = it.hasFocus }
                            .focusGroup()
                    )
                    // Result count on the left, collapse control at the bottom-right corner (R3 does the same).
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            resultsLabel(results.size, hasMoreResults),
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        if (activeFilterCount > 0 || query.isNotBlank()) PanelArrow(R.drawable.ic_star, stringResource(R.string.view_save)) { savingView = true }
                        if (results.isNotEmpty()) PanelArrow(R.drawable.ic_arrow_down, stringResource(R.string.bulk_title)) { showBulk = true }
                        if (results.isNotEmpty()) PanelArrow(R.drawable.ic_shuffle, stringResource(R.string.surprise_me)) { results.randomOrNull()?.let(viewModel::openDetails) }
                        PanelArrow(R.drawable.ic_arrow_left, stringResource(R.string.collapse_filters)) { collapseFilters() }
                    }
                }
                    VerticalDivider(color = LocalDogmatixTokens.current.hairline, modifier = Modifier.layoutId("divider"))
                    Box(modifier = Modifier.layoutId("list").then(listClip)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                    ) {
                        SearchField(
                            value = query,
                            onValueChange = viewModel::setSearch,
                            active = searchActive,
                            onActivate = { searchActive = true },
                            onDismiss = { searchActive = false; runCatching { listFocus.requestFocus() } },
                            focusRequester = searchFocus,
                            contentShift = contentShift
                        )
                        if (query.isEmpty() && recentSearches.isNotEmpty()) RecentSearchesRow(
                            recentSearches, onPick = viewModel::setSearch, onClear = viewModel::clearRecentSearches,
                            modifier = Modifier.padding(start = 6.dp, top = 8.dp)
                        )
                        if (query.isEmpty() && activeFilterCount == 0) ContinuePlayingShelf(
                            onOpenGame = viewModel::openDetails,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        if (tableRows) TableHeader(contentShift, showCover = listCovers) else Text(
                            resultsLabel(results.size, hasMoreResults),
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 6.dp)
                        )
                        ResultList(
                            results = results,
                            compact = tableRows,
                            hasMore = hasMoreResults,
                            isLoadingMore = isLoadingMore,
                            onLoadMore = { scope.launch { viewModel.loadMore() } },
                            getConsoleName = { ConsoleFormatter.getConsoleShortName(it) },
                            onFileClick = onFileClick,
                            isOwned = { viewModel.isOwned(it.file, ownedKeys) },
                            isOnRomm = { viewModel.isOnRomm(it.file, rommKeys, rommBase) },
                            isFavourite = { viewModel.isFavourite(it.file, favouriteKeys) },
                            isDownloading = { viewModel.isDownloading(it.file, activeDownloads) },
                            isNew = { viewModel.isNew(it.file) },
                            hasAchievements = { raMarks.gameFor(it.file.consoleId, it.file.fileName) != null },
                            achievementCount = { raMarks.gameFor(it.file.consoleId, it.file.fileName)?.achievements ?: 0 },
                            onRowFocused = { focusedItem = it },
                            onRowLongClick = viewModel::openDetails,
                            query = query,
                            onAddWish = addToWishlist,
                            byName = sort == SortOption.NAME_ASC || sort == SortOption.NAME_DESC,
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { listHasFocus = it.hasFocus }
                                .focusGroup(),
                            firstRowFocus = listFocus,
                            contentShift = contentShift,
                            showCover = listCovers,
                            dense = compactLists,
                            downloadProgress = progressOf,
                            isSearching = isSearching,
                            libraryEmpty = libraryEmpty,
                            onGoToSources = goToSources,
                            onClearFilters = clearFilters,
                            loadMoreSize = loadMoreSize,
                            illustrated = false
                        )
                    }
                    }
                }
            ) { measurables, constraints ->
                val height = constraints.maxHeight
                val widths = mapOf(
                    "panel" to panelWidth.roundToPx(), "rail" to railWidth.roundToPx(),
                    "divider" to dividerWidth.roundToPx(),
                    "list" to listWidth.roundToPx()
                )
                val placed = measurables.associate { val id = it.layoutId as String; id to it.measure(Constraints.fixed(widths.getValue(id), height)) }
                layout(constraints.maxWidth, height) {
                    val edge = currentWidth.value.roundToPx()
                    placed["list"]?.placeRelative(constraints.maxWidth - widths.getValue("list"), 0)
                    placed["rail"]?.placeRelative(0, 0)
                    placed["panel"]?.placeRelative(edge - widths.getValue("panel"), 0)
                    placed["divider"]?.placeRelative(edge, 0)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchField(
                        value = query,
                        onValueChange = viewModel::setSearch,
                        active = searchActive,
                        onActivate = { searchActive = true },
                        onDismiss = { searchActive = false; runCatching { listFocus.requestFocus() } },
                        focusRequester = searchFocus,
                        modifier = Modifier.weight(1f)
                    )
                    FilterButton(count = activeFilterCount) { showFilterSheet = true }
                }
                if (query.isEmpty() && recentSearches.isNotEmpty()) RecentSearchesRow(
                    recentSearches, onPick = viewModel::setSearch, onClear = viewModel::clearRecentSearches,
                    modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 6.dp)
                )
                if (query.isEmpty() && activeFilterCount == 0) ContinuePlayingShelf(
                    onOpenGame = viewModel::openDetails,
                    modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 6.dp)
                )
                ConsoleChips(
                    options = consoleOptions,
                    selected = selectedConsoles,
                    onSelect = viewModel::setConsoleSelection,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        resultsLabel(results.size, hasMoreResults),
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    // The links keep their full labels; on a narrow screen they scroll instead of squeezing.
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            if (activeFilterCount > 0 || query.isNotBlank()) BulkLink(stringResource(R.string.view_save), R.drawable.ic_star) { savingView = true }
                            if (results.isNotEmpty()) BulkLink(stringResource(R.string.bulk_link), R.drawable.ic_download) { showBulk = true }
                            if (results.isNotEmpty()) BulkLink(stringResource(R.string.surprise_me), R.drawable.ic_shuffle) { results.randomOrNull()?.let(viewModel::openDetails) }
                        }
                    }
                }
                ResultList(
                    results = results,
                    compact = false,
                    hasMore = hasMoreResults,
                    isLoadingMore = isLoadingMore,
                    onLoadMore = { scope.launch { viewModel.loadMore() } },
                    getConsoleName = { ConsoleFormatter.getConsoleShortName(it) },
                    onFileClick = onFileClick,
                    isOwned = { viewModel.isOwned(it.file, ownedKeys) },
                    isOnRomm = { viewModel.isOnRomm(it.file, rommKeys, rommBase) },
                    isFavourite = { viewModel.isFavourite(it.file, favouriteKeys) },
                    isDownloading = { viewModel.isDownloading(it.file, activeDownloads) },
                    isNew = { viewModel.isNew(it.file) },
                    hasAchievements = { raMarks.gameFor(it.file.consoleId, it.file.fileName) != null },
                    achievementCount = { raMarks.gameFor(it.file.consoleId, it.file.fileName)?.achievements ?: 0 },
                    onRowFocused = { focusedItem = it },
                    onRowLongClick = viewModel::openDetails,
                    query = query,
                    onAddWish = addToWishlist,
                    byName = sort == SortOption.NAME_ASC || sort == SortOption.NAME_DESC,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .onFocusChanged { listHasFocus = it.hasFocus },
                    firstRowFocus = listFocus,
                    showCover = listCovers,
                    dense = compactLists,
                    downloadProgress = progressOf,
                    isSearching = isSearching,
                    libraryEmpty = libraryEmpty,
                    onGoToSources = goToSources,
                    onClearFilters = clearFilters,
                    loadMoreSize = loadMoreSize,
                    illustrated = true
                )
            }

            if (showFilterSheet) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showFilterSheet = false },
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    dragHandle = {
                        Box(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        )
                    }
                ) {
                    // The sheet is its own window: gamepad keys never reach the Activity while it
                    // is open, so the shortcuts that leave the sheet are handled right here.
                    LaunchedEffect(Unit) { runCatching { filterFocus.requestFocus() } }
                    Box(
                        modifier = Modifier.swapFaceButtons().onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.ButtonR1, Key.ButtonThumbRight -> { showFilterSheet = false; true }
                                Key.ButtonY -> { showFilterSheet = false; searchActive = true; true }
                                Key.ButtonL2 -> { showFilterSheet = false; Gamepad.presses.tryEmit(GamepadButton.PREV_TAB); true }
                                Key.ButtonR2 -> { showFilterSheet = false; Gamepad.presses.tryEmit(GamepadButton.NEXT_TAB); true }
                                else -> false
                            }
                        }
                    ) {
                    FilterPanel(
                        rows = filterRows,
                        onClear = viewModel::clearAllFilters,
                        compact = false,
                        firstRowFocus = filterFocus,
                        expandedRow = expandedFilter,
                        onExpandedRowChange = { expandedFilter = it },
                        afterRows = discoverBlock,
                        footer = {
                            PrimaryButton(
                                stringResource(R.string.show_results),
                                onClick = { showFilterSheet = false },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                                    .height(46.dp),
                                icon = R.drawable.ic_check
                            )
                        }
                    )
                    }
                }
            }
        }

        SnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}


@Composable
private fun resultsLabel(count: Int, hasMore: Boolean): String =
    stringResource(if (hasMore) R.string.results_count_more else R.string.results_count, count)

/** Column titles of the landscape table, over a hairline; the name column follows the panel slide. */
@Composable
private fun TableHeader(contentShift: () -> Int = { 0 }, showCover: Boolean = false) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelMedium
    val hairline = LocalDogmatixTokens.current.hairline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.weight(1f).clipToBounds().offset { IntOffset(contentShift(), 0) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The cover column of the rows (cover + its gap), so "Name" stands over the names.
            if (showCover) Spacer(Modifier.width(TableCoverWidth + CoverGap))
            Text(stringResource(R.string.column_name), style = style, color = color, maxLines = 1)
        }
        Text(stringResource(R.string.column_tags), style = style, color = color, modifier = Modifier.width(300.dp))
        Text(stringResource(R.string.column_size), style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
    }
}

@Composable
private fun ResultList(
    results: List<DownloadableFileWithTags>,
    compact: Boolean,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    getConsoleName: (String) -> String,
    onFileClick: (DownloadableFileWithTags) -> Unit,
    isOwned: (DownloadableFileWithTags) -> Boolean,
    isFavourite: (DownloadableFileWithTags) -> Boolean,
    isDownloading: (DownloadableFileWithTags) -> Boolean,
    isNew: (DownloadableFileWithTags) -> Boolean,
    onRowFocused: (DownloadableFileWithTags) -> Unit,
    onRowLongClick: (DownloadableFileWithTags) -> Unit,
    modifier: Modifier = Modifier,
    isOnRomm: (DownloadableFileWithTags) -> Boolean = { false },
    hasAchievements: (DownloadableFileWithTags) -> Boolean = { false },
    /** What the user searched for, offered for the wishlist when nothing is found. */
    query: String = "",
    onAddWish: (() -> Unit)? = null,
    /** The list is sorted by name, so ◀ ▶ jumps by first letter (otherwise by ten rows). */
    byName: Boolean = true,
    firstRowFocus: FocusRequester? = null,
    contentShift: () -> Int = { 0 },
    /** 5.0: RA achievement count for the badge (0 = unknown). */
    achievementCount: (DownloadableFileWithTags) -> Int = { 0 },
    /** 5.0: cover thumbnails in front of the names. */
    showCover: Boolean = false,
    /** 5.0: download progress by file name, read while drawing the badge ring only. */
    downloadProgress: (String) -> Float = { 0f },
    /** 5.0: a search is running (placeholder rows instead of "nothing matches"). */
    isSearching: () -> Boolean = { false },
    /** 5.0: nothing is indexed at all (the empty state then points to Sources). */
    libraryEmpty: () -> Boolean = { false },
    onGoToSources: (() -> Unit)? = null,
    /** Clears filters and search; null when nothing narrows the list. */
    onClearFilters: (() -> Unit)? = null,
    /** How many rows "Load more" adds. */
    loadMoreSize: Int = Constants.DEFAULT_MAX_SEARCH_RESULTS,
    /** Room for the Milou illustration in the empty library state (portrait). */
    illustrated: Boolean = false,
    /** 6.0: compact lists (tighter rows). */
    dense: Boolean = false
) {
    if (results.isEmpty()) {
        // The flags are read only here: while the list has rows, a new search or a library change
        // does not recompose it.
        when {
            isSearching() -> SkeletonRows(compact, showCover, modifier.fillMaxWidth())
            libraryEmpty() -> EmptyBox(modifier) {
                EmptyState(
                    title = stringResource(R.string.home_empty_library_title),
                    message = stringResource(R.string.home_empty_library_message),
                    icon = if (illustrated) null else R.drawable.ic_library,
                    illustration = if (illustrated) R.drawable.milou else null,
                    actionLabel = if (onGoToSources != null) stringResource(R.string.home_go_to_sources) else null,
                    onAction = onGoToSources,
                    actionFocus = firstRowFocus
                )
            }
            else -> EmptyBox(modifier) {
                val wish = onAddWish != null && query.trim().length >= 2
                EmptyState(
                    title = stringResource(R.string.no_results),
                    message = stringResource(R.string.home_no_results_message),
                    icon = R.drawable.ic_search_off,
                    actionLabel = if (wish) stringResource(R.string.wishlist_add_query, query.trim()) else null,
                    onAction = if (wish) onAddWish else null,
                    actionFocus = if (wish) firstRowFocus else null
                )
                if (onClearFilters != null) {
                    ActionPill(
                        stringResource(R.string.home_clear_filters),
                        onClick = onClearFilters,
                        icon = R.drawable.ic_clear_filter,
                        // RB from the filters lands here when there is no wishlist button.
                        modifier = if (!wish && firstRowFocus != null) Modifier.focusRequester(firstRowFocus) else Modifier
                    )
                }
            }
        }
        return
    }
    val listState = rememberLazyListState()
    val focusIndex = listState.firstVisibleItemIndex
    // ◀ ▶ jump through a long list by first letter: scroll there, then focus that row.
    val jumpScope = rememberCoroutineScope()
    val jumpFocus = remember { FocusRequester() }
    var jumpTarget by remember { mutableStateOf<Int?>(null) }
    var currentIndex by remember { mutableStateOf(0) }
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    // After "Load more" the button is replaced by a spinner and focus is lost:
    // remember where the new page starts and land on its first row once it arrives.
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    val newPageFocus = remember { FocusRequester() }
    LaunchedEffect(results.size) {
        val target = pendingFocusIndex ?: return@LaunchedEffect
        if (target >= results.size) {
            pendingFocusIndex = null
            return@LaunchedEffect
        }
        listState.scrollToItem(target)
        withFrameNanos { }
        runCatching { newPageFocus.requestFocus() }
        pendingFocusIndex = null
    }
    LazyColumn(
        state = listState,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || (event.key != Key.DirectionLeft && event.key != Key.DirectionRight)) return@onPreviewKeyEvent false
            val forward = event.key == Key.DirectionRight
            val target = if (byName) LetterJump.target(results.map { stripExtension(it.file.name) }, currentIndex, forward)
            else LetterJump.step(results.size, currentIndex, forward)
            if (target != currentIndex) {
                // Quick presses chain: the next one starts from where this one is going, and
                // only the last jump is carried out.
                currentIndex = target
                jumpJob?.cancel()
                jumpJob = jumpScope.launch {
                    jumpTarget = target
                    try {
                        listState.scrollToItem(target)
                        withFrameNanos { }
                        withFrameNanos { }
                        runCatching { jumpFocus.requestFocus() }
                    } finally {
                        if (jumpTarget == target) jumpTarget = null
                    }
                }
            }
            true
        },
        contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (dense) DenseRowGap else RowGap)
    ) {
        itemsIndexed(results, key = { _, it -> it.file.id }) { index, item ->
            RomRow(
                item = item,
                consoleName = getConsoleName(item.file.consoleId),
                compact = compact,
                onClick = { onFileClick(item) },
                onLongClick = { onRowLongClick(item) },
                owned = isOwned(item),
                onRomm = isOnRomm(item),
                favourite = isFavourite(item),
                downloading = isDownloading(item),
                isNew = isNew(item),
                achievements = hasAchievements(item),
                contentShift = contentShift,
                achievementCount = achievementCount(item),
                showCover = showCover,
                dense = dense,
                downloadProgress = { downloadProgress(item.file.fileName) },
                // RB from the filters lands on the first row currently on screen.
                modifier = when {
                    index == pendingFocusIndex -> Modifier.focusRequester(newPageFocus)
                    index == jumpTarget -> Modifier.focusRequester(jumpFocus)
                    index == focusIndex && firstRowFocus != null -> Modifier.focusRequester(firstRowFocus)
                    else -> Modifier
                }.onFocusChanged { if (it.isFocused) { currentIndex = index; onRowFocused(item) } }
            )
        }
        if (hasMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                    } else {
                        LoadMoreButton(loadMoreSize) { pendingFocusIndex = results.size; onLoadMore() }
                    }
                }
            }
        }
    }
}

/** Centres an empty state in the list's place, scrolling it when the screen is too short for it. */
@Composable
private fun EmptyBox(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

/** Widths of the name bars of [SkeletonRows]: varied, so the placeholder reads as a list of names. */
private val SkeletonWidths = listOf(0.62f, 0.44f, 0.71f, 0.52f, 0.58f, 0.38f, 0.66f, 0.49f, 0.6f, 0.42f)

/**
 * Placeholder rows while the first page of a search loads: static bars in the panel tones, laid out
 * like the real rows (no shimmer: nothing animates, nothing recomposes).
 */
@Composable
private fun SkeletonRows(compact: Boolean, showCover: Boolean, modifier: Modifier) {
    val strong = MaterialTheme.colorScheme.surfaceContainerHigh
    val soft = MaterialTheme.colorScheme.surfaceContainer
    val bar = RoundedCornerShape(6.dp)
    val chip = RoundedCornerShape(4.dp)
    val rowHeight = when {
        compact && showCover -> 48.dp
        compact -> 46.dp
        showCover -> 72.dp
        else -> 64.dp
    }
    Column(modifier = modifier.clipToBounds().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SkeletonWidths.forEach { w ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .padding(horizontal = if (compact) 14.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showCover) {
                    Box(
                        Modifier
                            .size(if (compact) TableCoverWidth else 42.dp, if (compact) 40.dp else 56.dp)
                            .background(strong, RoundedCornerShape(if (compact) 5.dp else 7.dp))
                    )
                }
                if (compact) {
                    Box(Modifier.weight(1f)) { Box(Modifier.fillMaxWidth(w).height(12.dp).background(strong, bar)) }
                    Row(Modifier.width(300.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width(34.dp).height(18.dp).background(strong, chip))
                        Box(Modifier.width(42.dp).height(18.dp).background(soft, chip))
                        Box(Modifier.width(28.dp).height(18.dp).background(soft, chip))
                    }
                    Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.width(40.dp).height(10.dp).background(soft, bar))
                    }
                } else {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.fillMaxWidth(w).height(13.dp).background(strong, bar))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.width(34.dp).height(18.dp).background(strong, chip))
                            Box(Modifier.width(42.dp).height(18.dp).background(soft, chip))
                            Box(Modifier.width(28.dp).height(18.dp).background(soft, chip))
                        }
                    }
                    Box(Modifier.width(40.dp).height(10.dp).background(soft, bar))
                }
            }
        }
    }
}

/** "Load N more" at the end of a page: a tonal accent bar, full width. */
@Composable
private fun LoadMoreButton(count: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .background(scheme.primaryContainer, shape)
            .border(1.dp, scheme.primary.copy(alpha = 0.30f), shape)
            .focusRing(source, 12.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
        Text(
            if (count in 1 until Int.MAX_VALUE) pluralStringResource(R.plurals.home_load_more, count, count) else stringResource(R.string.load_more),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onPrimaryContainer,
            maxLines = 1
        )
    }
}

/** The last searches under the search field, while nothing is typed: a tap searches again. */
@Composable
private fun RecentSearchesRow(searches: List<String>, onPick: (String) -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(painterResource(R.drawable.ic_history), contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.home_recent),
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            modifier = Modifier.padding(end = 2.dp)
        )
        searches.forEach { search -> RecentPill(search) { onPick(search) } }
        PanelArrow(R.drawable.ic_close, stringResource(R.string.recent_searches_clear), onClear)
    }
}

/** One recent search as a neutral pill (focus ring, press flash). */
@Composable
private fun RecentPill(label: String, onClick: () -> Unit) {
    val source = rememberFocusSource()
    val (bg, fg) = pillColors(PillTone.Neutral)
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 200.dp)
            .background(bg, shape)
            .border(1.dp, LocalDogmatixTokens.current.hairline, shape)
            .focusRing(source, 14.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

/** An accent text link with an optional icon: "Save these filters", "Download all", "Surprise me" (portrait). */
@Composable
private fun BulkLink(label: String, icon: Int? = null, onClick: () -> Unit) {
    val source = rememberFocusSource()
    val color = accentInk()
    Row(
        modifier = Modifier
            .focusRing(source, 8.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        icon?.let { Icon(painterResource(it), contentDescription = null, tint = color, modifier = Modifier.size(15.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

/** A small icon button (same tint and size as the filter dropdown arrows): fold / unfold, save view, bulk, surprise. */
@Composable
private fun PanelArrow(icon: Int, description: String, onClick: () -> Unit) {
    val source = rememberFocusSource()
    Icon(
        painterResource(icon),
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .focusRing(source, 8.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(7.dp)
            .size(17.dp)
    )
}

/** Collapsed filter panel: a thin rail with the active-filter count and, at the bottom, the button that brings it back. */
@Composable
private fun FilterRail(count: Int, onExpand: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .requiredWidth(48.dp)
            .padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painterResource(R.drawable.ic_filter),
            contentDescription = null,
            tint = if (count > 0) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        if (count > 0) {
            Spacer(Modifier.height(8.dp))
            Pill(count.toString(), tone = PillTone.Strong)
        }
        Spacer(Modifier.weight(1f))
        PanelArrow(R.drawable.ic_arrow_right, stringResource(R.string.expand_filters), onExpand)
    }
}

@Composable
private fun FilterButton(count: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val active = count > 0
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .height(44.dp)
            .background(if (active) scheme.primary else scheme.surfaceContainer, shape)
            .border(1.dp, if (active) Color.Transparent else LocalDogmatixTokens.current.hairline, shape)
            .focusRing(source, 12.dp, onAccent = active)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val fg = if (active) scheme.onPrimary else scheme.onSurface
        Icon(painterResource(R.drawable.ic_filter), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.filters), style = MaterialTheme.typography.labelLarge, color = fg)
        if (active) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = accentInk(),
                modifier = Modifier
                    .background(scheme.onPrimary, RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun ConsoleChips(
    options: List<FilterOption>,
    selected: Set<String>,
    onSelect: (Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val total = remember(options) { options.sumOf { it.count ?: 0 } }
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item { ConsoleChip(stringResource(R.string.filter_all), selected.isEmpty(), color = null, count = total.takeIf { it > 0 }) { onSelect(emptySet()) } }
        items(options, key = { it.id }) { option ->
            val isOnly = selected.size == 1 && option.id in selected
            ConsoleChip(option.shortLabel, isOnly, color = option.color, count = option.count) { onSelect(if (isOnly) emptySet() else setOf(option.id)) }
        }
    }
}

/** A console chip (portrait): its colour as a dot, the short name and how many games match. */
@Composable
private fun ConsoleChip(label: String, selected: Boolean, color: Color?, count: Int?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .height(40.dp)
            .background(if (selected) scheme.primary else scheme.surfaceContainer, shape)
            .border(1.dp, if (selected) Color.Transparent else LocalDogmatixTokens.current.hairline, shape)
            .focusRing(source, 20.dp, onAccent = selected)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (color != null) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (selected) scheme.onPrimary else color, CircleShape)
            )
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) scheme.onPrimary else scheme.onSurface, maxLines = 1)
        if (count != null) {
            val shown = remember(count) { NumberFormat.getIntegerInstance().format(count) }
            Text(
                shown,
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = if (selected) scheme.onPrimary.copy(alpha = 0.75f) else scheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
