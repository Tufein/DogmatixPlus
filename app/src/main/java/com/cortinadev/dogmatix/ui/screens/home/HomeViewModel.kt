package com.cortinadev.dogmatix.ui.screens.home

import android.content.Context
import com.cortinadev.dogmatix.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.dao.ConsoleWithFileCount
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.CategorizedTags
import com.cortinadev.dogmatix.data.model.SortOption
import com.cortinadev.dogmatix.data.model.SourceFilter
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.repository.FavouritesRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.data.service.RommLibraryService
import com.cortinadev.dogmatix.data.repository.WishlistRepository
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.RommMarks
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.VersionPicker
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.GameMetadataService
import com.cortinadev.dogmatix.data.model.GameDetails
import kotlinx.coroutines.Job
import com.cortinadev.dogmatix.data.state.LibraryFilterRequest
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private val collectionsRepository: com.cortinadev.dogmatix.data.repository.CollectionsRepository
) : ViewModel() {

    // ---- 2.0: new games, collections, bulk download, Switch updates / DLC ----------------------

    private val _newOnly = MutableStateFlow(false)
    /** Only what rescans found in the last [com.cortinadev.dogmatix.util.NewGames.DAYS] days. */
    val newOnly: StateFlow<Boolean> = _newOnly.asStateFlow()
    fun setNewOnly(on: Boolean) { _newOnly.value = on }

    private val _collectionId = MutableStateFlow(0L)
    /** The collection shown; 0 = all games. */
    val collectionId: StateFlow<Long> = _collectionId.asStateFlow()
    fun setCollection(id: Long) { _collectionId.value = id }

    val collections: StateFlow<List<com.cortinadev.dogmatix.data.local.dao.CollectionWithCount>> = collectionsRepository.collections
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

    fun isNew(file: DownloadableFileEntity): Boolean = com.cortinadev.dogmatix.util.NewGames.isNew(file.firstSeenAt, System.currentTimeMillis())

    /** File names on disk for [consoleId] (from the library index keys of its folders). */
    private fun ownedNamesFor(consoleId: String): List<String> {
        val scopes = com.cortinadev.dogmatix.util.LibraryKeys.scopesFor(consoleId)
        return libraryIndex.ownedKeys.value.mapNotNull { key ->
            val bar = key.indexOf('|')
            if (bar < 0 || key.substring(0, bar) !in scopes) null else key.substring(bar + 1)
        }
    }

    /** What "Download all" would queue for the current filters ([bestOnly]: one version per game). */
    suspend fun planBulk(bestOnly: Boolean): com.cortinadev.dogmatix.util.BulkPlan {
        val rows = repository.searchFilesWithTags(
            query = _searchQuery.value, consoleIds = _selectedConsoles.value, tags = _activeTags.value,
            favouritesOnly = _favouritesOnly.value, newSince = newSince(), collectionId = _collectionId.value,
            source = _source.value, sort = _sort.value, limit = com.cortinadev.dogmatix.util.BulkPlanner.MAX_FILES * 4, offset = 0
        )
        val owned = ownedKeys.value
        val active = activeDownloads.value
        val languages = settingsRepository.favoriteLanguages.first()
        return com.cortinadev.dogmatix.util.BulkPlanner.plan(
            rows.map {
                com.cortinadev.dogmatix.util.BulkCandidate(
                    it.file.id, it.file.consoleId, it.file.searchKey.ifEmpty { com.cortinadev.dogmatix.util.SearchNormalizer.key(it.file.name) },
                    it.file.fileName, it.file.fileSize, it.tags, isOwned(it.file, owned), isDownloading(it.file, active)
                )
            },
            bestOnly, VersionPicker.regionPreference(languages), languages, libraryIndex.freeBytes.value
        ).also { lastBulkRows = rows.associateBy { it.file.id } }
    }

    private var lastBulkRows: Map<Long, DownloadableFileWithTags> = emptyMap()

    /** Queues every game of [plan]; returns how many. */
    suspend fun startBulk(plan: com.cortinadev.dogmatix.util.BulkPlan, context: Context): Int {
        val rows = plan.chosen.mapNotNull { lastBulkRows[it.id] }
        if (rows.isEmpty()) return 0
        val downloadDirectory = settingsRepository.downloadDirectory.first()
        if (downloadDirectory.isEmpty() || !StorageHelper.isValidUri(context, downloadDirectory)) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_missing))
            return 0
        }
        rows.forEach { downloadService.startDownload(it.file) }
        return rows.size
    }

    /** Starts the download of a library row found by the Switch section (an update or a DLC). */
    fun downloadRow(file: DownloadableFileEntity) {
        viewModelScope.launch { downloadService.startDownload(file) }
    }

    private fun newSince(): Long = if (_newOnly.value) com.cortinadev.dogmatix.util.NewGames.since(System.currentTimeMillis()) else 0L

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

    /** The game whose details card is open, if any, and what we know about it so far. */
    private val _details = MutableStateFlow<DetailsState?>(null)
    val details: StateFlow<DetailsState?> = _details.asStateFlow()
    private var detailsJob: Job? = null

    fun openDetails(item: DownloadableFileWithTags) {
        detailsJob?.cancel()
        _details.value = DetailsState(item, loading = true)
        detailsJob = viewModelScope.launch {
            // Which version of this game suits the user best (region, language, no demos).
            val versions = runCatching { repository.versionsOf(item.file) }.getOrDefault(emptyList())
            val languages = settingsRepository.favoriteLanguages.first()
            val bestId = if (versions.size > 1) VersionPicker.best(
                versions.map { VersionPicker.Candidate(it.file.fileName, FileParsingUtils.decodeUrlEncodedFileName(it.file.fileName), it.tags, it.file.fileSize) },
                VersionPicker.regionPreference(languages), languages
            )?.id else null
            val best = versions.firstOrNull { it.file.fileName == bestId }
            if (_details.value?.item == item) _details.value = _details.value!!.copy(versionCount = versions.size, best = best)
            if (_details.value?.item == item) _details.value = _details.value!!.copy(collectionIds = collectionsRepository.collectionsOf(item.file))
            // A Switch game: its updates and DLC in the library, against what is on disk.
            com.cortinadev.dogmatix.util.SwitchTitles.parse(item.file.fileName)?.let { title ->
                val rows = runCatching { repository.filesOf(item.file.consoleId) }.getOrDefault(emptyList())
                val status = com.cortinadev.dogmatix.util.SwitchTitles.analyse(rows, { it.fileName }, ownedNamesFor(item.file.consoleId), onlyOwned = false)
                    .firstOrNull { it.baseId == title.baseId }
                if (_details.value?.item == item) _details.value = _details.value!!.copy(switchTitle = title, switch = status)
            }
            val found = metadataService.lookup(item.file.name, item.file.consoleId)
            if (_details.value?.item == item) _details.value = _details.value!!.copy(loading = false, details = found)
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun isDownloading(file: DownloadableFileEntity, active: Set<String>): Boolean = file.fileName in active

    /** Remove an owned game from the download folder. Returns true if something was deleted. */
    suspend fun deleteOwned(fileWithTags: DownloadableFileWithTags): Boolean =
        libraryIndex.deleteOwned(fileWithTags.file)

    private val _selectedConsoles = MutableStateFlow<Set<String>>(emptySet())
    val selectedConsoles: StateFlow<Set<String>> = _selectedConsoles

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

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

    private var currentOffset = 0
    /** Rows per search ("Maximum search results" in Settings); [Int.MAX_VALUE] when unlimited. */
    private val pageSize: StateFlow<Int> = settingsRepository.maxSearchResults
        .map { if (it <= 0) Int.MAX_VALUE else it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Constants.DEFAULT_MAX_SEARCH_RESULTS)

    init {
        // Deep links (dogmatix://library?…): apply whatever is waiting, now and on every new link.
        viewModelScope.launch {
            pendingFilters.request.collect { if (it != null) pendingFilters.consume()?.let { request -> applyRequest(request) } }
        }
        viewModelScope.launch {
            combine(
                combine(_searchQuery, _selectedConsoles, _activeTags) { q, c, t -> Triple(q, c, t) },
                combine(_sort, _favouritesOnly, _source, rescanStateHolder.lastRescanTime, combine(_newOnly, _collectionId) { n, c -> n to c }) { s, f, src, _, nc -> FilterExtra(s, f, src, nc.first, nc.second) },
                // Re-query when a star changes while "Favourites only" is on, else the row would linger.
                combine(_favouritesOnly, favourites.keys) { only, keys -> if (only) keys else emptySet() }.distinctUntilChanged(),
                pageSize
            ) { (query, consoles, tags), extra, _, limit ->
                FilterParams(
                    query = query, consoles = consoles, tags = tags, sort = extra.sort, favouritesOnly = extra.favouritesOnly, source = extra.source, limit = limit,
                    newSince = if (extra.newOnly) com.cortinadev.dogmatix.util.NewGames.since(System.currentTimeMillis()) else 0L, collectionId = extra.collectionId
                )
            }.collect { params ->
                currentOffset = 0
                val initialResults = performSearch(params)
                _results.value = initialResults
                _hasMoreResults.value = initialResults.size >= params.limit
                loadConsoles()
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
    }

    private suspend fun performSearch(params: FilterParams): List<DownloadableFileWithTags> {
        currentOffset = 0
        return repository.searchFilesWithTags(
            query = params.query,
            consoleIds = params.consoles,
            tags = params.tags,
            favouritesOnly = params.favouritesOnly,
            newSince = params.newSince,
            collectionId = params.collectionId,
            source = params.source,
            sort = params.sort,
            limit = params.limit,
            offset = 0
        )
    }

    private suspend fun loadConsoles() {
        val allConsoles = consoleRepository.getAllConsoles().first()
        _consoles.value = allConsoles.sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) }

        val consolesWithFiles = repository.getConsolesWithFiles(
            query = _searchQuery.value.ifBlank { "*" },
            manufacturer = null
        )
        _consolesWithFiles.value = consolesWithFiles.sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) }
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
        currentOffset += limit

        val newResults = repository.searchFilesWithTags(
            query = _searchQuery.value,
            consoleIds = _selectedConsoles.value,
            tags = _activeTags.value,
            favouritesOnly = _favouritesOnly.value,
            newSince = newSince(),
            collectionId = _collectionId.value,
            source = _source.value,
            sort = _sort.value,
            limit = limit,
            offset = currentOffset
        )

        if (newResults.isEmpty()) {
            _hasMoreResults.value = false
        } else {
            _results.value += newResults
            if (newResults.size < limit) _hasMoreResults.value = false
        }

        _isLoadingMore.value = false
    }

    suspend fun startDownload(fileWithTags: DownloadableFileWithTags, context: Context) {
        val downloadDirectory = settingsRepository.downloadDirectory.first()
        if (downloadDirectory.isEmpty()) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_missing))
            return
        }

        if (!StorageHelper.isValidUri(context, downloadDirectory)) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_inaccessible))
            return
        }

        downloadService.startDownload(fileWithTags.file)
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
    val collectionId: Long = 0L
)

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
    val switchTitle: com.cortinadev.dogmatix.util.SwitchTitles.Title? = null,
    val switch: com.cortinadev.dogmatix.util.SwitchTitles.GameStatus<DownloadableFileEntity>? = null
)
