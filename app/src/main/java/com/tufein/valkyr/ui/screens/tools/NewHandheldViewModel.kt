package com.tufein.valkyr.ui.screens.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.FrontendCoversSettings
import com.tufein.valkyr.data.local.NewHandheldSettings
import com.tufein.valkyr.data.local.dao.CollectionDao
import com.tufein.valkyr.data.local.dao.CollectionWithCount
import com.tufein.valkyr.data.local.dao.ConsoleDao
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.dao.WishlistDao
import com.tufein.valkyr.data.local.entity.ConsoleEntity
import com.tufein.valkyr.data.local.entity.WishlistEntity
import com.tufein.valkyr.data.repository.FavouritesRepository
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.DavStatusService
import com.tufein.valkyr.data.service.GameLaunchService
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.data.service.RommLibraryService
import com.tufein.valkyr.data.service.SaveSyncService
import com.tufein.valkyr.data.state.LibraryFilterRequest
import com.tufein.valkyr.data.state.PendingLibraryFilters
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.EmulatorCatalog
import com.tufein.valkyr.util.FrontendCheck
import com.tufein.valkyr.util.FrontendCovers
import com.tufein.valkyr.util.GameLaunchKeys
import com.tufein.valkyr.util.LibraryKeys
import com.tufein.valkyr.util.NewHandheldPlan
import com.tufein.valkyr.util.SourcesJson
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.WeeklyDigest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the checklist shows: the steps, how far it is, and the one collection that misses games here. */
data class NewHandheldUi(
    val steps: List<NewHandheldPlan.StepResult>,
    val summary: NewHandheldPlan.Summary,
    /** (id, name) of the only collection with games missing here, for its own "Download all". */
    val collection: Pair<Long, String>? = null
)

/** The library side of the snapshot, worked out off the main thread whenever its inputs change. */
private data class LibraryFacts(
    val sources: Int,
    val folderSet: Boolean,
    val filesOnDisk: Boolean,
    val withGames: Set<String>,
    /** Consoles of the favourites and the wishlist: their emulators matter too. */
    val wanted: Set<String>,
    val favourites: Int,
    val favouritesMissing: Int,
    val collectionGames: Int,
    val collectionGamesMissing: Int,
    val collectionsMissing: List<Pair<Long, String>>,
    val wishes: Int,
    val wishesMissing: Int,
    val rommGames: Int,
    val rommMissing: Int
)

private data class LibraryInputs(
    val consoles: List<ConsoleEntity>,
    val folderSet: Boolean,
    val owned: Set<String>,
    val favourites: Set<String>,
    val collections: List<CollectionWithCount>,
    val wishes: List<WishlistEntity>,
    val romm: Set<String>
)

private data class SaveFacts(
    val rommConfigured: Boolean,
    val foldersSet: Boolean,
    val auto: Boolean,
    val running: Boolean,
    val done: Boolean,
    val conflicts: Int,
    val error: Boolean
)

private data class LauncherFacts(val ready: Int, val coversSet: Boolean)

/**
 * "Set up a new handheld": gathers what the app already knows into a [NewHandheldPlan.Snapshot]
 * and hands the steps to the screen. Everything comes from existing flows and database queries
 * (no folder is walked here); the counting runs off the main thread and only when its inputs change.
 * The actions only open existing flows: the library's "Download everything shown" preview, a
 * rescan of the on-disk index, the save sync.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class NewHandheldViewModel @Inject constructor(
    consoleDao: ConsoleDao,
    private val settings: SettingsRepository,
    private val appSettings: AppSettings,
    dav: DavStatusService,
    private val library: LibraryIndexService,
    favourites: FavouritesRepository,
    private val collectionDao: CollectionDao,
    wishlistDao: WishlistDao,
    romm: RommLibraryService,
    private val saveSync: SaveSyncService,
    private val launcher: GameLaunchService,
    private val files: DownloadableFileDao,
    covers: FrontendCoversSettings,
    private val store: NewHandheldSettings,
    private val pendingFilters: PendingLibraryFilters
) : ViewModel() {

    /** Bumped when the screen comes back to the front: an emulator may have been installed meanwhile. */
    private val resumes = MutableStateFlow(0)

    private val libraryInputs: Flow<LibraryInputs> = combine(
        combine(consoleDao.getAllConsoles(), settings.downloadDirectory, settings.consoleDownloadDirectories) { consoles, root, perConsole ->
            consoles to (root.isNotBlank() || perConsole.values.any { it.isNotBlank() })
        },
        library.ownedKeys,
        favourites.keys,
        combine(collectionDao.observeAll(), wishlistDao.observeAll(), romm.keys) { c, w, r -> Triple(c, w, r) }
    ) { (consoles, folderSet), owned, favs, (collections, wishes, rommKeys) ->
        LibraryInputs(consoles, folderSet, owned, favs, collections, wishes, rommKeys)
    }

    private val libraryFacts: Flow<LibraryFacts> = libraryInputs
        .debounce(200)
        .mapLatest { gatherLibrary(it) }
        .flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    private val emulatorFacts: Flow<List<NewHandheldPlan.ConsoleEmulators>> = combine(
        libraryFacts.map { it.withGames to it.wanted }.distinctUntilChanged(),
        launcher.preferenceRevision,
        resumes
    ) { consoles, _, _ -> consoles }
        .mapLatest { (withGames, wanted) -> gatherEmulators(withGames, wanted) }
        .flowOn(Dispatchers.Default)

    private val saveFacts: Flow<SaveFacts> = combine(
        combine(settings.rommUrl, settings.rommToken) { url, token -> url.isNotBlank() && token.isNotBlank() },
        combine(settings.saveSyncSavesDir, settings.saveSyncStatesDir, appSettings.saveSyncEmulatorFolders) { saves, states, emulators ->
            saves.isNotBlank() || states.isNotBlank() || emulators.isNotEmpty()
        },
        settings.saveSyncAuto,
        saveSync.state,
        store.savesSyncedAt
    ) { rommConfigured, foldersSet, auto, state, syncedAt ->
        SaveFacts(
            rommConfigured = rommConfigured,
            foldersSet = foldersSet,
            auto = auto,
            running = state.running,
            done = syncedAt > 0L || (state.last != null && state.last.failed == 0 && state.error.isNullOrBlank()),
            conflicts = state.conflicts.size,
            error = !state.running && !state.error.isNullOrBlank()
        )
    }.distinctUntilChanged()   // the sync's progress text ticks; the facts rarely change

    private val launcherFacts: Flow<LauncherFacts> = combine(
        combine(settings.downloadDirectory, settings.esdeDirectory, settings.iisuDirectory) { d, e, i -> Triple(d, e, i) },
        combine(appSettings.esdeArtwork, appSettings.pegasusArtwork, appSettings.retroArchThumbnailsDir) { a, p, r -> Triple(a, p, r) },
        combine(FrontendCovers.Target.entries.map { covers.of(it) }) { targets -> targets.any { it.folder.isNotBlank() } }
    ) { (download, esde, iisu), (esdeCovers, pegasus, retroArch), coversSet ->
        val findings = FrontendCheck.evaluate(
            FrontendCheck.State(
                downloadFolderSet = download.isNotBlank(), esdeFolderSet = esde.isNotBlank(), esdeCovers = esdeCovers,
                iisuFolderSet = iisu.isNotBlank(), pegasusCovers = pegasus, retroArchThumbnailsSet = retroArch.isNotBlank()
            )
        )
        LauncherFacts(findings.count { it.status == FrontendCheck.Status.READY }, coversSet)
    }.distinctUntilChanged()

    /** Null until the first snapshot is worked out. */
    val ui: StateFlow<NewHandheldUi?> = combine(
        libraryFacts,
        emulatorFacts,
        saveFacts,
        launcherFacts,
        combine(dav.status.map { it.configured }.distinctUntilChanged(), store.marks) { cloud, marks -> cloud to marks }
    ) { lib, emulators, saves, launch, (cloud, marks) ->
        val snapshot = NewHandheldPlan.Snapshot(
            sources = lib.sources,
            cloudConfigured = cloud,
            libraryFolderSet = lib.folderSet,
            filesOnDisk = lib.filesOnDisk,
            consolesWithGames = lib.withGames.size,
            emulators = emulators,
            favourites = lib.favourites,
            favouritesMissing = lib.favouritesMissing,
            collectionGames = lib.collectionGames,
            collectionGamesMissing = lib.collectionGamesMissing,
            collectionsMissing = lib.collectionsMissing.size,
            wishes = lib.wishes,
            wishesMissing = lib.wishesMissing,
            rommConfigured = saves.rommConfigured,
            rommGames = lib.rommGames,
            rommMissing = lib.rommMissing,
            saveFoldersSet = saves.foldersSet,
            saveSyncAuto = saves.auto,
            saveSyncRunning = saves.running,
            saveSyncDone = saves.done,
            saveConflicts = saves.conflicts,
            saveError = saves.error,
            frontendsReady = launch.ready,
            coversFolderSet = launch.coversSet
        )
        val steps = NewHandheldPlan.plan(snapshot, marks)
        NewHandheldUi(steps, NewHandheldPlan.summary(steps), lib.collectionsMissing.singleOrNull())
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // A finished save sync is remembered here, so the step stays done after the app restarts
        // (the sync service only keeps its last result while the app runs).
        viewModelScope.launch {
            saveSync.state
                .map { if (it.last != null && it.last.failed == 0 && it.error.isNullOrBlank()) it.last.finishedAt else 0L }
                .distinctUntilChanged()
                .filter { it > 0L }
                .collect { store.recordSavesSynced(it) }
        }
    }

    private suspend fun gatherLibrary(i: LibraryInputs): LibraryFacts {
        // Consoles that were downloaded to but are not in the catalogue (removed, or from a backup) count as well.
        val downloadedTo = i.owned.mapNotNullTo(HashSet()) { key ->
            key.substringBefore('|').takeIf { it.startsWith("console:") }?.removePrefix("console:")?.takeIf(String::isNotEmpty)
        }
        val ids = (i.consoles.map { it.id } + downloadedTo).distinct()
        val sources = i.consoles.sumOf { SourcesJson.parseUrlEntries(it.urls).size }
        val withGames = WeeklyDigest.consolesWithGames(ids, i.owned)
        val favourites = i.favourites.mapNotNull { key ->
            val bar = key.indexOf('|')
            if (bar <= 0) null else key.substring(0, bar) to key.substring(bar + 1)
        }
        val items = if (i.collections.none { it.count > 0 }) emptyList() else collectionDao.getAllItems()
        val itemGames = items.map { it.consoleId to it.fileName }.distinct()
        val missingItems = NewHandheldPlan.missingGames(itemGames, i.owned).toHashSet()
        val names = i.collections.associate { it.id to it.name }
        val collectionsMissing = items.filter { (it.consoleId to it.fileName) in missingItems }
            .map { it.collectionId }.distinct()
            .mapNotNull { id -> names[id]?.let { id to it } }
        val wishes = i.wishes.map { NewHandheldPlan.Wish(it.title, it.consoleId?.let(LibraryKeys::scopesFor)) }
        val rommGames = i.romm.mapNotNull { key ->
            val bar = key.indexOf('|')
            if (bar <= 0) null else key.substring(0, bar) to key.substring(bar + 1)
        }
        return LibraryFacts(
            sources = sources,
            folderSet = i.folderSet,
            filesOnDisk = i.owned.isNotEmpty(),
            withGames = withGames,
            wanted = (favourites.map { it.first } + i.wishes.mapNotNull { it.consoleId?.takeIf(String::isNotBlank) }).toSet(),
            favourites = favourites.size,
            favouritesMissing = NewHandheldPlan.missingCount(favourites, i.owned),
            collectionGames = itemGames.size,
            collectionGamesMissing = missingItems.size,
            collectionsMissing = collectionsMissing,
            wishes = wishes.size,
            wishesMissing = wishes.size - NewHandheldPlan.wishesOnDevice(wishes, i.owned),
            rommGames = rommGames.size,
            rommMissing = NewHandheldPlan.missingCount(rommGames, i.owned)
        )
    }

    /** Which consoles have an emulator to start their games: the catalogue's installed apps, or an app picked on a game page. */
    private suspend fun gatherEmulators(withGames: Set<String>, wanted: Set<String>): List<NewHandheldPlan.ConsoleEmulators> {
        val ids = (withGames + wanted).toList()
        if (ids.isEmpty()) return emptyList()
        val targets = launcher.catalogueTargets(ids)
        return withContext(Dispatchers.IO) {
            ids.map { id ->
                val system = EmulatorCatalog.systemOf(id)
                val stored = launcher.preferred(id)
                // An app chosen on a game page that the catalogue does not know, still installed.
                val ownApp = stored != null && stored != GameLaunchKeys.AUTOMATIC &&
                    GameLaunchKeys.parseCatalogue(stored) == null && launcher.labelOf(stored) != null
                NewHandheldPlan.ConsoleEmulators(
                    consoleId = id,
                    name = ConsoleFormatter.getConsoleDisplayName(id).replace('\n', ' '),
                    hasGames = id in withGames,
                    known = system != null,
                    launchable = targets[id].orEmpty().isNotEmpty() || ownApp,
                    suggestion = system?.let { EmulatorCatalog.emulatorsFor(it).firstOrNull()?.label }
                )
            }
        }
    }

    fun onResume() = resumes.update { it + 1 }

    fun setMark(step: NewHandheldPlan.Step, mark: NewHandheldPlan.Mark?) {
        viewModelScope.launch { store.setMark(step, mark) }
    }

    fun startOver() {
        viewModelScope.launch { store.clearMarks() }
    }

    /** Reads the games folders again (the same rescan a finished download starts). */
    fun scanLibrary(context: Context) {
        library.requestRefresh()
        ToastUtil.showInfo(context.applicationContext, context.getString(R.string.wizard35_scan_started))
    }

    /** The save sync the Save sync screen's "Sync now" runs. */
    fun syncSaves(context: Context) {
        saveSync.syncNow()
        ToastUtil.showInfo(context.applicationContext, context.getString(R.string.wizard35_sync_started))
    }

    /**
     * The library with only the favourites, and its "Download everything shown" preview open:
     * it skips what is here or downloading and checks the free space before anything starts.
     */
    fun downloadFavourites() {
        pendingFilters.submit(LibraryFilterRequest(favouritesOnly = true, newOnly = false, collectionId = 0L, openBulk = true))
    }

    /** The same for one collection. */
    fun downloadCollection(id: Long) {
        pendingFilters.submit(LibraryFilterRequest(favouritesOnly = false, newOnly = false, collectionId = id, openBulk = true))
    }

    /**
     * The readiness check ("Ready to play?") for a game of [consoleId], one on this handheld when
     * there is one, as Settings' emulator choice does; null when the library has no game of it.
     */
    fun emulatorCheck(consoleId: String, onRoute: (String?) -> Unit) {
        viewModelScope.launch {
            val owned = library.ownedKeys.value
            val file = withContext(Dispatchers.IO) {
                val rows = files.filesOf(consoleId)
                rows.firstOrNull { LibraryKeys.isOwned(it.consoleId, it.fileName, owned) } ?: rows.firstOrNull()
            }
            onRoute(file?.let { ReadinessRoute.of(consoleId, it.fileName) })
        }
    }
}
