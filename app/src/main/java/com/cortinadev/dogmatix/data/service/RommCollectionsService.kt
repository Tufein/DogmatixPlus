package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.local.RommSettings
import com.cortinadev.dogmatix.data.local.dao.CollectionDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dao.FavouriteDao
import com.cortinadev.dogmatix.data.local.entity.CollectionItemEntity
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.cortinadev.dogmatix.data.repository.CollectionsRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.RommFavouritesSync
import com.cortinadev.dogmatix.util.RommMarks
import com.cortinadev.dogmatix.util.RommSource
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommCollections"
/** Star changes are gathered this long before one write goes to RomM. */
private const val FAVOURITES_DEBOUNCE_MS = 2_500L

/** How the last automatic favourites sync went (Settings → RomM, under the switch). */
data class RommFavouritesState(
    val running: Boolean = false,
    /** When the last sync finished without error; 0 = never. */
    val syncedAt: Long = 0L,
    /** Stars added / removed here and hearts added / removed on RomM by the last sync. */
    val starred: Int = 0,
    val unstarred: Int = 0,
    val heartsAdded: Int = 0,
    val heartsRemoved: Int = 0,
    val errorKind: RommErrorKind? = null,
    val error: String? = null
)

/**
 * Keeps own collections and RomM collections alike. Only games the library lists from a RomM
 * source (`romm://…`) can be matched both ways, because only those carry their RomM id.
 *  - **From RomM**: each RomM collection becomes (or adds to) the collection of the same name here.
 *  - **To RomM**: each collection here gets a RomM collection of the same name, holding the RomM
 *    games of it; games from other sources are skipped and counted.
 *
 * 5.0, "Keep favourites in step with RomM" ([RommSettings.favouritesTwoWay]): stars and RomM's
 * hearts are merged both ways ([RommFavouritesSync]) a moment after a star changes, after each
 * refresh of the server's game list, and with From / To RomM. Games on the server are then also
 * matched through the RomM game list (`RommLibraryService.games`), not only `romm://` rows.
 * With the switch off, nothing changes from 4.x: hearts only come down with From RomM (as
 * stars, never removing one), stars never go up, and To RomM never touches the favourites
 * collection — a collection here called "Favourites" must not overwrite the hearts.
 */
@OptIn(FlowPreview::class)
@Singleton
class RommCollectionsService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val romm: RommClient,
    private val collections: CollectionsRepository,
    private val collectionDao: CollectionDao,
    private val fileDao: DownloadableFileDao,
    private val favouriteDao: FavouriteDao,
    private val rommSettings: RommSettings,
    private val rommLibrary: RommLibraryService,
    private val settingsRepository: SettingsRepository
) {
    data class Result(val collections: Int, val games: Int, val skipped: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val favouritesLock = Mutex()
    private val baseFile = File(context.filesDir, "romm_favourites_base.json")

    private val _favouritesState = MutableStateFlow(RommFavouritesState())
    val favouritesState: StateFlow<RommFavouritesState> = _favouritesState.asStateFlow()

    /** The star keys as the last sync left them: their echo in the database is not a change. */
    @Volatile private var appliedKeys: Set<String>? = null

    init {
        scope.launch {
            rommSettings.favouritesTwoWay.distinctUntilChanged().collectLatest { on ->
                if (!on) {
                    // Turning it on again later starts with a merge that removes nothing.
                    runCatching { baseFile.delete() }
                    appliedKeys = null
                    return@collectLatest
                }
                merge(
                    favouriteDao.observeAll()
                        .map { list -> list.map { it.key }.toSet() }
                        .distinctUntilChanged()
                        .filter { it != appliedKeys }
                        .map { },
                    rommLibrary.state.map { it.updatedAt }.distinctUntilChanged().map { },
                    rommLibrary.games.map { it.size }.distinctUntilChanged().map { }
                )
                    .debounce(FAVOURITES_DEBOUNCE_MS)
                    .collect { runAutomaticSync() }
            }
        }
    }

    /** rom id → (console, file name) of the RomM rows in the library. */
    private suspend fun rommRows(): Map<Int, Pair<String, String>> =
        fileDao.rommRows().mapNotNull { r -> RommSource.romIdOf(r.downloadUrl)?.let { it to (r.consoleId to r.fileName) } }.toMap()

    private var cachedId: Int? = null
    private suspend fun myId(): Int? = cachedId ?: romm.myUserId().also { cachedId = it }

    suspend fun pull(): Result = withContext(Dispatchers.IO) {
        val twoWay = rommSettings.favouritesTwoWay.first()
        val rows = rommRows()
        var games = 0
        var skipped = 0
        val remote = romm.collections()
        remote.forEach { c ->
            if (c.isFavourite) {
                // Two-way: the merge below takes care of the hearts.
                if (twoWay) return@forEach
                // RomM's hearts become stars here, not a collection called "Favourites".
                if (c.userId != null && c.userId != myId()) return@forEach
                val have = favouriteDao.getAll().map { it.consoleId to it.fileName }.toSet()
                val items = c.romIds.mapNotNull { rows[it] }
                skipped += c.romIds.size - items.size
                val fresh = items.filter { it !in have }
                favouriteDao.upsertAll(fresh.map { (console, file) -> FavouriteEntity(console, file) })
                games += fresh.size
                return@forEach
            }
            val id = collections.create(c.name) ?: return@forEach
            val have = collectionDao.itemsOf(id).map { it.consoleId to it.fileName }.toSet()
            val items = c.romIds.mapNotNull { rows[it] }
            skipped += c.romIds.size - items.size
            val fresh = items.filter { it !in have }
            collectionDao.addItems(fresh.map { (console, file) -> CollectionItemEntity(id, console, file) })
            games += fresh.size
        }
        if (twoWay) favouritesQuietly()?.let { games += it.starred }
        Result(remote.size, games, skipped)
    }

    suspend fun push(): Result = withContext(Dispatchers.IO) {
        val byKey = rommRows().entries.associate { (romId, key) -> key to romId }
        // Only the account's own collections can be changed; another user's public one of the
        // same name gets a twin of ours instead of a permission error.
        val me = myId()
        val remote = romm.collections()
            .filter { !it.isFavourite && (me == null || it.userId == null || it.userId == me) }
            .associateBy { it.name.lowercase() }
        var games = 0
        var skipped = 0
        val local = collectionDao.getAll()
        local.forEach { c ->
            val items = collectionDao.itemsOf(c.id)
            val romIds = items.mapNotNull { byKey[it.consoleId to it.fileName] }
            skipped += items.size - romIds.size
            val existing = remote[c.name.lowercase()]
            val target = existing?.id ?: romm.createCollection(c.name)
            romm.setCollectionRoms(target, c.name, ((existing?.romIds ?: emptyList()) + romIds).distinct())
            games += romIds.size
        }
        if (rommSettings.favouritesTwoWay.first()) favouritesQuietly()?.let { games += it.heartsAdded }
        Result(local.size, games, skipped)
    }

    /** The favourites merge as part of From / To RomM: its failure is shown under the switch, not as theirs. */
    private suspend fun favouritesQuietly(): RommFavouritesState? = try {
        syncFavourites()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Favourites sync failed: ${e.javaClass.simpleName}")
        null
    }

    // ---- 5.0: favourites in step with RomM ------------------------------------------------------

    /** Runs the favourites sync now (Settings → RomM, "Sync now"); does nothing while the switch is off. */
    fun syncFavouritesNow() {
        scope.launch { runAutomaticSync() }
    }

    private suspend fun runAutomaticSync() {
        if (!rommSettings.favouritesTwoWay.first()) return
        try {
            syncFavourites()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Already recorded in favouritesState; the next change or refresh tries again.
            Log.w(TAG, "Favourites sync failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * One two-way merge of the stars here with RomM's favourites collection. Returns what it
     * changed, or null when RomM is not set up. Throws when the server cannot be read or written
     * (nothing is changed here then).
     */
    suspend fun syncFavourites(): RommFavouritesState? = withContext(Dispatchers.IO) {
        favouritesLock.withLock {
            if (romm.configuredBaseUrl().isEmpty() || settingsRepository.rommToken.first().isBlank()) return@withLock null
            _favouritesState.update { it.copy(running = true) }
            try {
                val outcome = mergeFavourites()
                _favouritesState.value = outcome
                outcome
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _favouritesState.update { it.copy(running = false, errorKind = rommErrorKind(e), error = (e.message ?: e.javaClass.simpleName).take(160)) }
                } else _favouritesState.update { it.copy(running = false) }
                throw e
            }
        }
    }

    private suspend fun mergeFavourites(): RommFavouritesState {
        val me = myId()
        val all = romm.collections()
        val picked = RommFavouritesSync.pickCollection(
            all.map { RommFavouritesSync.CollectionRef(it.id, it.name, it.userId, it.isFavourite) }, me
        )
        val favourites = picked?.let { p -> all.first { it.id == p.id } }
        if (favourites != null && !RommFavouritesSync.isReadable(favourites.romIds, favourites.romCount)) {
            throw RommException("Could not read the games of RomM's favourites collection")
        }

        val resolver = Resolver(rommRows(), rommLibrary.games.value.mapValues { it.value.romId })
        val stars = favouriteDao.getAll()
        val starIds = HashMap<Int, MutableList<FavouriteEntity>>()
        stars.forEach { s -> resolver.idOf(s.consoleId, s.fileName)?.let { starIds.getOrPut(it) { ArrayList() } += s } }
        val local = starIds.keys.toSet()
        val remote = favourites?.romIds?.toSet().orEmpty()

        val stored = readBase()
        val base = stored?.takeIf { favourites != null && it.first == favourites.id }?.second
        val known = HashSet(local)
        (remote + base.orEmpty()).forEach { id -> if (id !in known && resolver.rowOf(id) != null) known += id }

        val plan = RommFavouritesSync.plan(local, remote, base, known)

        var collectionId = favourites?.id
        if (plan.pushNeeded) {
            val name = favourites?.name ?: RommFavouritesSync.COLLECTION_NAME
            val target = collectionId ?: romm.createCollection(RommFavouritesSync.COLLECTION_NAME, favourite = true)
            romm.setCollectionRoms(target, name, plan.remote.toList())
            collectionId = target
        }

        val toStar = plan.addLocal.mapNotNull { id -> resolver.rowOf(id) }.distinct()
            .map { (console, file) -> FavouriteEntity(console, file) }
        val toUnstar = plan.removeLocal.flatMap { id -> starIds[id].orEmpty() }
        if (toStar.isNotEmpty()) favouriteDao.upsertAll(toStar)
        toUnstar.forEach { favouriteDao.delete(it.consoleId, it.fileName) }
        appliedKeys = favouriteDao.getAll().mapTo(HashSet()) { it.key }

        collectionId?.let { writeBase(it, plan.base) }
        return RommFavouritesState(
            running = false,
            syncedAt = System.currentTimeMillis(),
            starred = toStar.size,
            unstarred = toUnstar.size,
            heartsAdded = if (plan.pushNeeded) (plan.remote - remote).size else 0,
            heartsRemoved = if (plan.pushNeeded) (remote - plan.remote).size else 0
        )
    }

    /**
     * Finds the RomM id of a library game and the library game of a RomM id: `romm://` rows by
     * their download URL, other rows (and stars) through the server's game list, by console and
     * file name without extension ([RommMarks.key]).
     */
    private inner class Resolver(
        private val rows: Map<Int, Pair<String, String>>,
        private val idByKey: Map<String, Int>
    ) {
        private val rowIdByPair = rows.entries.associate { (id, pair) -> pair to id }
        private val keysById: Map<Int, List<String>> = idByKey.entries.groupBy({ it.value }, { it.key })
        private val namesByConsole = HashMap<String, List<String>>()

        fun idOf(consoleId: String, fileName: String): Int? =
            rowIdByPair[consoleId to fileName] ?: idByKey[RommMarks.key(consoleId, fileName)]

        /** A library row (console, file name) of RomM game [id]; null when the library has none. */
        suspend fun rowOf(id: Int): Pair<String, String>? {
            rows[id]?.let { return it }
            for (key in keysById[id].orEmpty()) {
                val console = key.substringBefore('|')
                val names = namesByConsole.getOrPut(console) { runCatching { fileDao.fileNamesFor(console) }.getOrDefault(emptyList()) }
                names.firstOrNull { RommMarks.key(console, it) == key }?.let { return console to it }
            }
            return null
        }
    }

    /** The agreement of the last sync: (collection id, rom ids); null when there is none. */
    private fun readBase(): Pair<Int, Set<Int>>? {
        if (!baseFile.exists()) return null
        return runCatching {
            val o = JsonParser.parseString(baseFile.readText()).asJsonObject
            val id = o.get("collection").asInt
            id to o.getAsJsonArray("ids").mapNotNullTo(HashSet<Int>()) { runCatching { it.asInt }.getOrNull() }
        }.onFailure { Log.w(TAG, "Unreadable favourites base; merging without removals") }.getOrNull()
    }

    private fun writeBase(collectionId: Int, ids: Set<Int>) {
        runCatching {
            val json = JsonObject().apply {
                addProperty("collection", collectionId)
                addProperty("at", System.currentTimeMillis())
                add("ids", JsonArray().also { a -> ids.sorted().forEach { a.add(it) } })
            }
            val tmp = File(baseFile.parentFile, baseFile.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(baseFile)) { baseFile.delete(); tmp.renameTo(baseFile) }
        }.onFailure { Log.w(TAG, "Could not save the favourites base") }
    }
}
