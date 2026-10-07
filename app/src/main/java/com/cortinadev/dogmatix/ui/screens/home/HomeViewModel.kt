package com.cortinadev.dogmatix.ui.screens.home

import android.content.Context
import com.cortinadev.dogmatix.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.data.local.LookSettings
import com.cortinadev.dogmatix.data.local.dao.GameMetadataDao
import com.cortinadev.dogmatix.util.GameTitleCleaner
import com.cortinadev.dogmatix.util.LibraryDiscovery
import kotlinx.coroutines.delay
import com.cortinadev.dogmatix.data.local.dao.CollectionWithCount
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.dao.ConsoleWithFileCount
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.CategorizedTags
import com.cortinadev.dogmatix.data.model.SortOption
import com.cortinadev.dogmatix.data.model.SourceFilter
import com.cortinadev.dogmatix.data.repository.CollectionsRepository
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.repository.FavouritesRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.data.service.LibraryToolsService
import com.cortinadev.dogmatix.data.service.ProfileService
import com.cortinadev.dogmatix.data.service.RaMarks
import com.cortinadev.dogmatix.data.service.RetroAchievementsService
import com.cortinadev.dogmatix.data.service.RommLibraryService
import com.cortinadev.dogmatix.data.repository.WishlistRepository
import com.cortinadev.dogmatix.util.BulkCandidate
import com.cortinadev.dogmatix.util.BulkPlan
import com.cortinadev.dogmatix.util.BulkPlanner
import com.cortinadev.dogmatix.util.SourceRanking
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.LibraryView
import com.cortinadev.dogmatix.util.LibraryViews
import com.cortinadev.dogmatix.util.NewGames
import com.cortinadev.dogmatix.util.RaGame
import com.cortinadev.dogmatix.util.RommMarks
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.SwitchTitles
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.GameMetadataService
import com.cortinadev.dogmatix.data.model.GameDetails
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import com.cortinadev.dogmatix.data.state.LibraryFilterRequest
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.util.QuickAction
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.DeepLinkResolver
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** How long a deep link waits for consoles / tag catalogue before applying what it has. */
private const val RESOLVE_TIMEOUT_MS = 5_000L

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: DownloadableFileRepository,
    private val consoleRepository: ConsoleRepository,
    private val downloadService: DownloadService,
    private val settingsRepository: SettingsRepository,
    private val rescanStateHolder: RescanStateHolder,
    private val libraryIndex: LibraryIndexService,
    private val metadataService: GameMetadataService,
    private val favourites: FavouritesRepository,
    private val pendingFilters: PendingLibraryFilters,
    private val rommLibrary: RommLibraryService,
    private val wishlist: WishlistRepository,
    private val collectionsRepository: CollectionsRepository,
    private val appSettings: AppSettings,
    private val profiles: ProfileService,
    private val versionPreference: com.cortinadev.dogmatix.data.service.VersionPreferenceService,
    private val versionSettings: com.cortinadev.dogmatix.data.local.VersionPreferenceSettings,
    private val retroAchievements: RetroAchievementsService,
    private val libraryTools: LibraryToolsService,
    private val metadataDao: GameMetadataDao,
    lookSettings: LookSettings,
    private val sourceTrack: com.cortinadev.dogmatix.data.service.SourceTrackService,
    private val detailsLoader: GameDetailsLoader
) : ViewModel() {

    /** 5.0: small covers in front of the games in the list (Settings → Look). */
    val listCovers: StateFlow<Boolean> = lookSettings.listCovers
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 6.0: tighter rows (Settings → Look → compact lists). */
    val compactLists: StateFlow<Boolean> = lookSettings.compactLists
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- 6.0: search by feel (genre / decade from the cached game details) ----------------------

    private val _genres = MutableStateFlow<Set<String>>(emptySet())
    val genres: StateFlow<Set<String>> = _genres.asStateFlow()
    private val _decades = MutableStateFlow<Set<Int>>(emptySet())
    val decades: StateFlow<Set<Int>> = _decades.asStateFlow()

    fun setGenres(selection: Set<String>) { _genres.value = selection }
    fun setDecades(selection: Set<Int>) { _decades.value = selection }

    /**
     * What the cached details know (genre / year per title), built off the main thread and rebuilt
     * when the cache grows. Each build is a new object, so a change always re-runs an active filter.
     */
    val discoverIndex: StateFlow<LibraryDiscovery.Index> = metadataDao.observeKnown()
        .map { rows -> LibraryDiscovery.buildIndex(rows.map { LibraryDiscovery.RawRow(it.lookupKey, it.genres, it.released, it.developer) }) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryDiscovery.Index.EMPTY)

    /** State of "Fetch details for the shown games". */
    sealed interface FetchState {
        object Idle : FetchState
        object NoKeys : FetchState
        data class Running(val done: Int, val total: Int) : FetchState
        data class Done(val found: Int, val total: Int) : FetchState
    }

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState.asStateFlow()
    private var fetchJob: Job? = null
    private val triedKeys = HashSet<String>()

    /** Whether any metadata database is configured in this build (RAWG / TheGamesDB keys). */
    private val hasMetadataKeys: Boolean get() = BuildConfig.RAWG_API_KEY.isNotEmpty() || BuildConfig.THEGAMESDB_API_KEY.isNotEmpty()

    /**
     * Opt-in: looks up the details of the games now in the list that are not cached yet, at most
     * [FETCH_CAP] titles, one every [FETCH_GAP_MS] ms (RAWG / TheGamesDB have quotas). Pressing it
     * again while it runs stops it. Nothing runs in the background by itself.
     */
    fun fetchDetailsForShown() {
        if (fetchJob?.isActive == true) { fetchJob?.cancel(); _fetchState.value = FetchState.Idle; return }
        if (!hasMetadataKeys) { _fetchState.value = FetchState.NoKeys; return }
        val shown = _results.value
        fetchJob = viewModelScope.launch {
            val targets = withContext(Dispatchers.Default) {
                LibraryDiscovery.fetchTargets(
                    shown.map { LibraryDiscovery.Target(LibraryDiscovery.lookupKey(it.file.consoleId, it.file.name), it.file.name, it.file.consoleId) },
                    discoverIndex.value.byKey.keys, triedKeys, FETCH_CAP
                )
            }
            if (targets.isEmpty()) { _fetchState.value = FetchState.Done(0, 0); return@launch }
            var found = 0
            targets.forEachIndexed { i, t ->
                _fetchState.value = FetchState.Running(i, targets.size)
                triedKeys.add(t.key)
                val details = runCatching { metadataService.lookup(t.name, t.consoleId) }.getOrNull()
                if (details != null && (details.genres.isNotEmpty() || details.released.isNotBlank())) found++
                if (i < targets.lastIndex) delay(FETCH_GAP_MS)
            }
            _fetchState.value = FetchState.Done(found, targets.size)
        }
    }

    /** Bytes that removing duplicate games (keeping the biggest copy of each) would free; null on failure. */
    suspend fun reclaimableBytes(): Long? = runCatching {
        withContext(Dispatchers.IO) { libraryTools.duplicateGroups().sumOf { it.reclaimable } }
    }.getOrNull()

    /** RetroAchievements marks for the "RA" badge. */
    val raMarks: StateFlow<RaMarks> = retroAchievements.marks

    /** RA game of a row for the details card: (game, true) by hash, (game, false) by title only. */
    suspend fun achievementsFor(file: DownloadableFileEntity) = detailsLoader.achievementsFor(file)

    // ---- 2.5: saved views ("smart collections") ------------------------------------------------

    val views: StateFlow<List<LibraryView>> = appSettings.libraryViews
        .map { LibraryViews.fromJson(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The current filters as a view (no id or name yet). */
    private fun currentView(id: String = "", name: String = "") = LibraryView(
        id = id, name = name, query = _searchQuery.value, consoles = _selectedConsoles.value, tags = _activeTags.value,
        favouritesOnly = _favouritesOnly.value, newOnly = _newOnly.value, collectionId = _collectionId.value,
        source = _source.value.name, sort = _sort.value.name, genres = _genres.value, decades = _decades.value
    )

    /** The saved view whose filters are exactly the current ones, if any. */
    fun matchingViewId(): String? = views.value.firstOrNull { it.copy(id = "", name = "") == currentView() }?.id

    suspend fun saveView(name: String): Boolean {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) return false
        val others = views.value.filterNot { it.name.equals(clean, ignoreCase = true) }
        val view = currentView(UUID.randomUUID().toString(), clean)
        appSettings.setLibraryViews(LibraryViews.toJson(others + view))
        return true
    }

    fun applyView(view: LibraryView) {
        _searchQuery.value = view.query
        _selectedConsoles.value = view.consoles
        _activeTags.value = view.tags
        _favouritesOnly.value = view.favouritesOnly
        _newOnly.value = view.newOnly
        _collectionId.value = view.collectionId
        _source.value = runCatching { SourceFilter.valueOf(view.source) }.getOrDefault(SourceFilter.ALL)
        _sort.value = runCatching { SortOption.valueOf(view.sort) }.getOrDefault(SortOption.NAME_ASC)
        _genres.value = view.genres
        _decades.value = view.decades
    }

    // ---- 2.0: new games, collections, bulk download, Switch updates / DLC ----------------------

    private val _newOnly = MutableStateFlow(false)
    /** Only what rescans found in the last [com.cortinadev.dogmatix.util.NewGames.DAYS] days. */
    val newOnly: StateFlow<Boolean> = _newOnly.asStateFlow()
    fun setNewOnly(on: Boolean) { _newOnly.value = on }

    private val _collectionId = MutableStateFlow(0L)
    /** The collection shown; 0 = all games. */
    val collectionId: StateFlow<Long> = _collectionId.asStateFlow()
    fun setCollection(id: Long) { _collectionId.value = id }

    val collections: StateFlow<List<CollectionWithCount>> = collectionsRepository.collections
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Puts the game of the open details card in or out of collection [id]; returns whether it is in it now. */
    suspend fun toggleCollection(item: DownloadableFileWithTags, id: Long): Boolean {
        val inIt = collectionsRepository.toggle(id, item.file)
        _details.value?.takeIf { it.item == item }?.let { _details.value = it.copy(collectionIds = collectionsRepository.collectionsOf(item.file)) }
        return inIt
    }

    /** Creates a collection and puts [item] in it. */
    suspend fun createCollectionWith(item: DownloadableFileWithTags, name: String): Boolean {
        val id = collectionsRepository.create(name) ?: return false
        if (id !in collectionsRepository.collectionsOf(item.file)) toggleCollection(item, id)
        return true
    }

    fun isNew(file: DownloadableFileEntity): Boolean = NewGames.isNew(file.firstSeenAt, System.currentTimeMillis())

    /** What "Download all" would queue for the current filters ([bestOnly]: one version per game). */
    suspend fun planBulk(bestOnly: Boolean): BulkPlan {
        val params = currentParams()
        // Query-result mapping, preference keys and source ranking all scale with the whole console.
        val (plan, byId) = withContext(Dispatchers.Default) {
            val rows = fetchFiltered(params, 0, BulkPlanner.MAX_FILES * 4).rows
            val owned = ownedKeys.value
            val active = activeDownloads.value
            val whitespace = Regex("\\s+")
            // 7.5: the same file from several sources is queued from the best one.
            val pickBest = sourceTrack.enabled.first()
            val records = sourceTrack.records.value
            val orders = if (pickBest) rows.map { it.file.consoleId }.distinct().associateWith { sourceTrack.sourceOrder(it) } else emptyMap()
            val now = System.currentTimeMillis()
            val preferences = versionPreference.snapshot()
            val versionPrefs = versionSettings.snapshot()
            val pinned = rows.associate { it.file.id to preferences.preferred(it.file.consoleId, it.file.fileName) }
            BulkPlanner.plan(
                rows.map {
                    BulkCandidate(
                        // The cleaned title itself (tags are already stripped from it): the search key folds
                        // repeated characters, so "Game 001" and "Game 011" would count as one game.
                        it.file.id, it.file.consoleId, it.file.name.lowercase().replace(whitespace, " ").trim(),
                        it.file.fileName, it.file.fileSize, it.tags, isOwned(it.file, owned), isDownloading(it.file, active),
                        it.file.sourceUrl
                    )
                },
                bestOnly, { versionPrefs.of(it) }, libraryIndex.freeBytes.value,
                pickSource = { group ->
                    if (!pickBest) group.first()
                    else SourceRanking.best(group, { it.sourceUrl }, records, orders[group.first().consoleId].orEmpty(), now) ?: group.first()
                },
                preferredVersion = { pinned[it.id] }
            ) to rows.associateBy { it.file.id }
        }
        // A cancelled older plan must not replace the rows belonging to the visible dialog.
        lastBulkRows = byId
        return plan
    }

    private var lastBulkRows: Map<Long, DownloadableFileWithTags> = emptyMap()

    /** Queues every game of [plan]; returns how many. */
    suspend fun startBulk(plan: BulkPlan, context: Context): Int {
        val rows = plan.chosen.mapNotNull { lastBulkRows[it.id] }
        if (rows.isEmpty()) return 0
        val downloadDirectory = settingsRepository.downloadDirectory.first()
        val valid = withContext(Dispatchers.IO) { StorageHelper.isValidUri(context, downloadDirectory) }
        if (!valid) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_missing))
            return 0
        }
        withContext(Dispatchers.Default) { downloadService.startDownloads(rows.map { it.file }) }
        return rows.size
    }

    /** Starts the download of a library row found by the Switch section (an update or a DLC). */
    /** 6.0: queues [file] with a condition (Wi-Fi, charging, tonight, at a time); null = right away. */
    fun downloadWhen(file: DownloadableFileEntity, condition: com.cortinadev.dogmatix.util.DownloadCondition?) {
        viewModelScope.launch(Dispatchers.Default) { downloadService.startDownload(sourceTrack.pickBest(file, sameNameOnly = true), condition) }
    }

    fun downloadRow(file: DownloadableFileEntity) = downloadRows(listOf(file))

    /** Switch updates and DLC from the details sheet are enqueued as one batch too. */
    fun downloadRows(files: List<DownloadableFileEntity>) {
        viewModelScope.launch(Dispatchers.Default) {
            val chosen = files.map { sourceTrack.pickBest(it, sameNameOnly = true) }
            downloadService.startDownloads(chosen)
        }
    }

    private fun newSince(): Long = if (_newOnly.value) NewGames.since(System.currentTimeMillis()) else 0L

    /** `consoleId|name` keys of the games the RomM server has (empty when marking is off). */
    val rommKeys: StateFlow<Set<String>> = rommLibrary.keys
    /** The RomM server address, so rows that come from it count as "on RomM" too. */
    val rommBase: StateFlow<String> = settingsRepository.rommUrl.map { it.trim().trimEnd('/') }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun isOnRomm(file: DownloadableFileEntity, keys: Set<String>, base: String): Boolean =
        (keys.isNotEmpty() || base.isNotEmpty()) && RommMarks.isOnServer(keys, file.consoleId, file.fileName, file.downloadUrl, base)

    /** Puts [title] on the wishlist; false for a duplicate or a too short title. */
    suspend fun addToWishlist(title: String): Boolean =
        wishlist.add(title, _selectedConsoles.value.singleOrNull())

    /** `consoleId|fileName` keys of starred games; see [isFavourite]. */
    val favouriteKeys: StateFlow<Set<String>> = favourites.keys

    fun isFavourite(file: DownloadableFileEntity, keys: Set<String>): Boolean = favourites.isFavourite(file, keys)

    /** Star / un-star; returns the new state. */
    suspend fun toggleFavourite(item: DownloadableFileWithTags): Boolean = favourites.toggle(item.file)

    private val _favouritesOnly = MutableStateFlow(false)
    val favouritesOnly: StateFlow<Boolean> = _favouritesOnly.asStateFlow()

    fun setFavouritesOnly(only: Boolean) {
        _favouritesOnly.value = only
    }

    private val _source = MutableStateFlow(SourceFilter.ALL)
    val source: StateFlow<SourceFilter> = _source.asStateFlow()

    fun setSource(source: SourceFilter) {
        _source.value = source
    }

    /** 7.0: asks the screen to put the cursor in the search box (the Search shortcut); it keeps the ask until the screen collects it. */
    private val _searchRequests = Channel<Unit>(Channel.CONFLATED)
    val searchRequests: Flow<Unit> = _searchRequests.receiveAsFlow()

    private fun onQuickAction(action: QuickAction) {
        when (action) {
            QuickAction.SEARCH -> _searchRequests.trySend(Unit)
            QuickAction.SURPRISE -> viewModelScope.launch {
                // A cold start: wait for the first list (the shortcut may be what launched the app).
                withTimeoutOrNull(15_000) { _isSearching.first { !it } }
                _results.value.randomOrNull()?.let(::openDetails)
            }
            QuickAction.DOWNLOADS -> Unit   // a section: PendingLibraryFilters hands it to the shell
        }
    }

    /** The game whose details card is open, if any, and what we know about it so far. */
    private val _details = MutableStateFlow<DetailsState?>(null)
    val details: StateFlow<DetailsState?> = _details.asStateFlow()
    private var detailsJob: Job? = null

    fun openDetails(item: DownloadableFileWithTags) {
        detailsJob?.cancel()
        _details.value = DetailsState(item, loading = true)
        detailsJob = viewModelScope.launch {
            // 8.0: the same steps as the game page (GameDetailsLoader); each one lands only while this game is still open.
            detailsLoader.load(item, discoverIndex.value) { change ->
                _details.value?.takeIf { it.item == item }?.let { _details.value = change(it) }
            }
        }
    }

    fun closeDetails() {
        detailsJob?.cancel()
        _details.value = null
    }

    /** Lower-cased names of files already on disk; see [isOwned]. */
    val ownedKeys: StateFlow<Set<String>> = libraryIndex.ownedKeys

    fun isOwned(file: DownloadableFileEntity, keys: Set<String>): Boolean = libraryIndex.isOwned(file, keys)

    /** File names with a download in flight (queued, downloading, copying or extracting). */
    val activeDownloads: StateFlow<Set<String>> = downloadService.downloads
        .map { list -> list.filter { !it.isFinished }.map { it.fileName }.toSet() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * 5.0: progress (0..1) of every download in flight, by file name, sampled twice a second for
     * the ring on the row badge. The screen reads it only while drawing that ring, so a progress
     * tick never recomposes the list; [activeDownloads] (which changes rarely) decides which rows
     * show the badge at all.
     */
    @OptIn(FlowPreview::class)
    val downloadProgress: StateFlow<Map<String, Float>> = downloadService.downloads
        .sample(500L)
        .map { list -> list.filter { !it.isFinished }.associate { it.fileName to it.progress.coerceIn(0f, 1f) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun isDownloading(file: DownloadableFileEntity, active: Set<String>): Boolean = file.fileName in active

    /** Remove an owned game from the download folder. Returns true if something was deleted. */
    suspend fun removalPlan(fileWithTags: DownloadableFileWithTags) = libraryIndex.removalPlan(fileWithTags.file)

    suspend fun removePlan(plan: List<com.cortinadev.dogmatix.data.service.RemovalFile>, title: String) = libraryIndex.deletePlan(plan, title)

    suspend fun deleteOwned(fileWithTags: DownloadableFileWithTags): Boolean =
        libraryIndex.deleteOwned(fileWithTags.file)

    private val _selectedConsoles = MutableStateFlow<Set<String>>(emptySet())
    val selectedConsoles: StateFlow<Set<String>> = _selectedConsoles

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** The last searches, newest first (see [RecentSearches]). */
    val recentSearches: StateFlow<List<String>> = appSettings.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearRecentSearches() {
        viewModelScope.launch { appSettings.clearRecentSearches() }
    }

    /** A search is remembered once the typing has stopped for a moment. */
    @OptIn(FlowPreview::class)
    private fun rememberSearches() {
        viewModelScope.launch {
            _searchQuery.debounce(1500L).collect { if (it.isNotBlank()) appSettings.addRecentSearch(it) }
        }
    }

    private val _activeTags = MutableStateFlow<Set<String>>(emptySet())
    val activeTags: StateFlow<Set<String>> = _activeTags.asStateFlow()

    private val _sort = MutableStateFlow(SortOption.NAME_ASC)
    val sort: StateFlow<SortOption> = _sort.asStateFlow()

    private val _results = MutableStateFlow<List<DownloadableFileWithTags>>(emptyList())
    val results: StateFlow<List<DownloadableFileWithTags>> = _results

    private val _consoles = MutableStateFlow<List<ConsoleEntity>>(emptyList())
    val consoles: StateFlow<List<ConsoleEntity>> = _consoles

    private val _consolesWithFiles = MutableStateFlow<List<ConsoleWithFileCount>>(emptyList())
    val consolesWithFiles: StateFlow<List<ConsoleWithFileCount>> = _consolesWithFiles

    private val _categorizedTags = MutableStateFlow<CategorizedTags?>(null)
    val categorizedTags: StateFlow<CategorizedTags?> = _categorizedTags

    /** Language tags listed first in the filter; editable in Settings. */
    val favoriteLanguages: StateFlow<Set<String>> = settingsRepository.favoriteLanguages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _hasMoreResults = MutableStateFlow(true)
    val hasMoreResults: StateFlow<Boolean> = _hasMoreResults

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    /** 5.0: a new search (filters / query changed) is running; the list shows placeholder rows meanwhile. */
    private val _isSearching = MutableStateFlow(true)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    /**
     * 5.0: nothing is indexed at all (no sources yet, or none scanned): the empty list then points
     * to Sources instead of saying that nothing matches the filters.
     */
    private val _libraryEmpty = MutableStateFlow(false)
    val libraryEmpty: StateFlow<Boolean> = _libraryEmpty.asStateFlow()

    private var currentOffset = 0
    /** Rows per search ("Maximum search results" in Settings); [Int.MAX_VALUE] when unlimited. */
    private val pageSize: StateFlow<Int> = settingsRepository.maxSearchResults
        .map { if (it <= 0) Int.MAX_VALUE else it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Constants.DEFAULT_MAX_SEARCH_RESULTS)

    /** How many rows "Load more" adds (the label used to say 100 whatever the setting). */
    val loadMoreSize: StateFlow<Int> = pageSize

    init {
        rememberSearches()
        // Deep links (dogmatix://library?…): apply whatever is waiting, now and on every new link.
        viewModelScope.launch {
            pendingFilters.request.collect { if (it != null) pendingFilters.consume()?.let { request -> applyRequest(request) } }
        }
        // 7.0 quick access (launcher shortcuts): Surprise me and Search.
        viewModelScope.launch {
            pendingFilters.quick.collect { if (it != null) pendingFilters.consumeQuick()?.let(::onQuickAction) }
        }
        viewModelScope.launch {
            combine(
                combine(_searchQuery, _selectedConsoles, _activeTags) { q, c, t -> Triple(q, c, t) },
                combine(_sort, _favouritesOnly, _source, rescanStateHolder.lastRescanTime, combine(_newOnly, _collectionId) { n, c -> n to c }) { s, f, src, _, nc -> FilterExtra(s, f, src, nc.first, nc.second) },
                // Re-query when a star changes while "Favourites only" is on, else the row would linger.
                // …and when the active profile changes what is hidden.
                combine(_favouritesOnly, favourites.keys, profiles.restrictions) { only, keys, r -> (if (only) keys else emptySet()) to r }.distinctUntilChanged(),
                pageSize,
                // 6.0: genre / decade; the index only matters (and re-runs the search) while one is active.
                combine(_genres, _decades, discoverIndex) { g, d, idx -> Triple(g, d, if (g.isNotEmpty() || d.isNotEmpty()) idx else null) }
            ) { (query, consoles, tags), extra, _, limit, (genres, decades, _) ->
                FilterParams(
                    query = query, consoles = consoles, tags = tags, sort = extra.sort, favouritesOnly = extra.favouritesOnly, source = extra.source, limit = limit,
                    newSince = if (extra.newOnly) NewGames.since(System.currentTimeMillis()) else 0L, collectionId = extra.collectionId,
                    genres = genres, decades = decades
                )
            }.collect { params ->
                currentOffset = 0
                _isSearching.value = true
                try {
                    val page = fetchFiltered(params, 0, params.limit)
                    currentOffset = page.nextOffset
                    _results.value = page.rows
                    _hasMoreResults.value = !page.exhausted
                    // Before the flag drops: an empty list must already know whether the library is empty.
                    loadConsoles()
                } finally {
                    _isSearching.value = false
                }
                loadAvailableTags(params.query, params.consoles)
            }
        }
    }

    /**
     * Deep-link values are loose (`console=snes`, `region=japan`): resolve them against the
     * console list and the tag catalogue, waiting briefly for both if the link arrived during a
     * cold start. Unknown consoles are dropped; unknown tags are applied verbatim.
     */
    private suspend fun applyRequest(request: LibraryFilterRequest) {
        val consoleIds = if (request.consoles.isEmpty()) emptySet() else {
            val consoles = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { _consoles.first { it.isNotEmpty() } } ?: _consoles.value
            DeepLinkResolver.resolveConsoles(request.consoles, consoles.map { it.id })
        }
        val tags = if (request.tags.isEmpty()) emptySet() else {
            val catalogue = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { _categorizedTags.first { it != null } } ?: _categorizedTags.value
            val known = catalogue?.let { it.regions.tags + it.languages.tags + it.videoStandards.tags + it.contentTypes.tags + it.fileTypes.tags }.orEmpty()
            DeepLinkResolver.resolveTags(request.tags, known)
        }
        _selectedConsoles.value = consoleIds
        _activeTags.value = tags
        _searchQuery.value = request.query.orEmpty()
        request.favouritesOnly?.let { _favouritesOnly.value = it }
        request.newOnly?.let {
            _newOnly.value = it
            if (it) _sort.value = SortOption.NEWEST
        }
        request.collectionId?.let { _collectionId.value = it }
    }

    fun toggleConsoleFilter(consoleId: String) {
        val currentConsoles = _selectedConsoles.value.toMutableSet()
        if (currentConsoles.contains(consoleId)) {
            currentConsoles.remove(consoleId)
        } else {
            currentConsoles.add(consoleId)
        }
        _selectedConsoles.value = currentConsoles
    }

    fun clearConsoleFilters() {
        _selectedConsoles.value = emptySet()
    }

    fun setSearch(query: String) {
        _searchQuery.value = query
    }

    fun toggleTag(tag: String) {
        val currentTags = _activeTags.value.toMutableSet()
        if (currentTags.contains(tag)) {
            currentTags.remove(tag)
        } else {
            currentTags.add(tag)
        }
        _activeTags.value = currentTags
    }

    fun removeTag(tag: String) {
        _activeTags.value = _activeTags.value - tag
    }

    fun removeConsole(consoleId: String) {
        _selectedConsoles.value = _selectedConsoles.value - consoleId
    }

    fun setSort(option: SortOption) {
        _sort.value = option
    }

    /** Replace whichever of [categoryTags] are active with [selection] (a subset of them). */
    fun setTagsInCategory(categoryTags: Collection<String>, selection: Set<String>) {
        _activeTags.value = (_activeTags.value - categoryTags.toSet()) + selection
    }

    fun setConsoleSelection(consoleIds: Set<String>) {
        _selectedConsoles.value = consoleIds
    }

    fun clearAllFilters() {
        _searchQuery.value = ""
        _activeTags.value = emptySet()
        _selectedConsoles.value = emptySet()
        _sort.value = SortOption.NAME_ASC
        _favouritesOnly.value = false
        _source.value = SourceFilter.ALL
        _newOnly.value = false
        _collectionId.value = 0L
        _genres.value = emptySet()
        _decades.value = emptySet()
    }

    private fun currentParams() = FilterParams(
        query = _searchQuery.value, consoles = _selectedConsoles.value, tags = _activeTags.value, sort = _sort.value,
        favouritesOnly = _favouritesOnly.value, source = _source.value, limit = pageSize.value, newSince = newSince(),
        collectionId = _collectionId.value, genres = _genres.value, decades = _decades.value
    )

    /** A page of results: where the next one starts (in database rows) and whether nothing is left. */
    private class Page(val rows: List<DownloadableFileWithTags>, val nextOffset: Int, val exhausted: Boolean)

    /**
     * [want] rows of the filtered list from database row [offset] on. Without a genre / decade
     * filter that is one query. With one, the database filters cannot know the cached details, so
     * rows are read in batches of [SCAN_BATCH] and matched against [discoverIndex] on a background
     * thread until [want] are found, the list ends, or [SCAN_CAP] rows were looked at (the next
     * page then simply continues from there).
     */
    private suspend fun fetchFiltered(params: FilterParams, offset: Int, want: Int): Page {
        val filter = LibraryDiscovery.Filter(params.genres, params.decades)
        suspend fun query(limit: Int, from: Int) = repository.searchFilesWithTags(
            query = params.query, consoleIds = params.consoles, tags = params.tags, favouritesOnly = params.favouritesOnly,
            newSince = params.newSince, collectionId = params.collectionId, source = params.source, sort = params.sort,
            limit = limit, offset = from
        )
        if (!filter.isActive) {
            val rows = query(want, offset)
            return Page(rows, offset + rows.size, rows.size < want)
        }
        val index = discoverIndex.value
        val out = ArrayList<DownloadableFileWithTags>()
        var next = offset
        var scanned = 0
        var exhausted = false
        while (out.size < want && scanned < SCAN_CAP) {
            val batch = query(SCAN_BATCH, next)
            if (batch.isEmpty()) { exhausted = true; break }
            val matched = withContext(Dispatchers.Default) {
                LibraryDiscovery.match(batch, index, filter, want - out.size) { LibraryDiscovery.lookupKey(it.file.consoleId, it.file.name) }
            }
            out.addAll(matched.rows)
            next += matched.consumed
            scanned += matched.consumed
            if (matched.consumed == batch.size && batch.size < SCAN_BATCH) { exhausted = true; break }
        }
        return Page(out, next, exhausted)
    }

    private suspend fun loadConsoles() {
        val allConsoles = consoleRepository.getAllConsoles().first()
        _consoles.value = allConsoles.sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) }

        val consolesWithFiles = repository.getConsolesWithFiles(
            query = _searchQuery.value.ifBlank { "*" },
            manufacturer = null
        )
        _consolesWithFiles.value = consolesWithFiles.sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) }
        // The file counts follow the search text, so only an empty search can tell an empty library.
        _libraryEmpty.value = allConsoles.isEmpty() ||
            (consolesWithFiles.isEmpty() && (_searchQuery.value.isBlank() || _libraryEmpty.value))
    }

    private suspend fun loadAvailableTags(query: String, consoleIds: Set<String>) {
        _categorizedTags.value = repository.getCategorizedTags(
            query = query,
            consoleIds = consoleIds
        )
    }

    fun getConsoleName(consoleId: String): String {
        return ConsoleFormatter.formatConsoleField(consoleId)
    }

    suspend fun loadMore() {
        if (_isLoadingMore.value || !_hasMoreResults.value) return

        val limit = pageSize.value
        _isLoadingMore.value = true

        val page = fetchFiltered(currentParams(), currentOffset, limit)
        currentOffset = page.nextOffset
        if (page.rows.isNotEmpty()) _results.value += page.rows
        _hasMoreResults.value = !page.exhausted

        _isLoadingMore.value = false
    }

    suspend fun startDownload(fileWithTags: DownloadableFileWithTags, context: Context) {
        val downloadDirectory = settingsRepository.downloadDirectory.first()
        if (downloadDirectory.isEmpty()) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_missing))
            return
        }

        if (!withContext(Dispatchers.IO) { StorageHelper.isValidUri(context, downloadDirectory) }) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_inaccessible))
            return
        }

        // 7.5: the copy of the game from the source with the best track record (same game, version and region).
        withContext(Dispatchers.Default) {
            downloadService.startDownload(sourceTrack.pickBest(fileWithTags.file, sameNameOnly = true))
        }
    }
}

data class FilterParams(
    val query: String,
    val consoles: Set<String>,
    val tags: Set<String>,
    val sort: SortOption,
    val favouritesOnly: Boolean = false,
    val source: SourceFilter = SourceFilter.ALL,
    val limit: Int = Constants.DEFAULT_MAX_SEARCH_RESULTS,
    val newSince: Long = 0L,
    val collectionId: Long = 0L,
    val genres: Set<String> = emptySet(),
    val decades: Set<Int> = emptySet()
)

private const val SCAN_BATCH = 1500
private const val SCAN_CAP = 60_000
internal const val FETCH_CAP = 40
private const val FETCH_GAP_MS = 800L

private data class FilterExtra(val sort: SortOption, val favouritesOnly: Boolean, val source: SourceFilter, val newOnly: Boolean, val collectionId: Long)

data class DetailsState(
    val item: DownloadableFileWithTags,
    val loading: Boolean,
    val details: GameDetails? = null,
    /** How many versions of this game the library lists (this one included). */
    val versionCount: Int = 1,
    /** The version that suits the user best when it is not simply this one; null otherwise. */
    val best: DownloadableFileWithTags? = null,
    /** Own collections this game is in. */
    val collectionIds: Set<Long> = emptySet(),
    /** For a Switch file with a title ID: what it is, and its game's updates / DLC. */
    val switchTitle: SwitchTitles.Title? = null,
    val switch: SwitchTitles.GameStatus<DownloadableFileEntity>? = null,
    /** RetroAchievements game and whether it was matched by hash (true) or only by title (false). */
    val achievements: Pair<RaGame, Boolean>? = null,
    /** 6.0: "More like this", ranked from local data; empty hides the section. */
    val similar: List<DownloadableFileWithTags> = emptyList(),
    /** 8.0: every version of the game the library lists (this one included), for the game page. */
    val versions: List<DownloadableFileWithTags> = emptyList(),
    /** 8.0: file name of the version that suits the user best (null with one version or no pick). */
    val bestFileName: String? = null
)
