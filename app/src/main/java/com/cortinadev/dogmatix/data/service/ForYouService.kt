package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.local.VersionPreferenceSettings
import com.cortinadev.dogmatix.data.local.dao.CollectionDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dao.FavouriteDao
import com.cortinadev.dogmatix.data.local.dao.GameMetadataDao
import com.cortinadev.dogmatix.util.BestGames
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.ForYou
import com.cortinadev.dogmatix.util.HistoryKind
import com.cortinadev.dogmatix.util.LibraryDiscovery
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.LibraryRestrictions
import com.cortinadev.dogmatix.util.RankBasis
import com.cortinadev.dogmatix.util.RaConsoleMap
import com.cortinadev.dogmatix.util.UpNext
import com.cortinadev.dogmatix.util.VersionPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ForYou"

/** What the "For you" section shows, and when it was worked out. */
data class ForYouResult(val suggestions: List<ForYou.Suggestion>, val at: Long, val seeds: Int)

/**
 * Builds "Suggested for you" (3.0.0) from what the app already has, without any network: the
 * active profile's favourites and Up next list, collections, ES-DE plays and finished downloads
 * ([PlayHistoryService]), the cached details of `game_metadata`, the RetroAchievements lists Best
 * games keeps on disk, and what is on the device ([LibraryIndexService]). The library is read in
 * light pages, at most [MAX_ROWS] rows, and scored off the main thread; the result is kept per
 * profile for [VALID_MS] (the Refresh button works it out again).
 */
@Singleton
class ForYouService @Inject constructor(
    private val fileDao: DownloadableFileDao,
    private val favourites: FavouriteDao,
    private val collections: CollectionDao,
    private val metadata: GameMetadataDao,
    private val playHistory: PlayHistoryService,
    private val bestGames: BestGamesService,
    private val libraryIndex: LibraryIndexService,
    private val profiles: ProfileService,
    private val upNext: UpNextService,
    private val versionSettings: VersionPreferenceSettings
) {
    private val lock = Mutex()
    private val cache = HashMap<String, ForYouResult>()

    /** The kept result of the active profile while it is fresh, without working anything out. */
    fun cached(now: Long = System.currentTimeMillis()): ForYouResult? =
        synchronized(cache) { cache[profiles.currentIdNow()] }?.takeIf { now - it.at in 0 until VALID_MS }

    /** The suggestions of the active profile: the kept ones while fresh (unless [force]), else worked out now. */
    suspend fun suggestions(force: Boolean = false): ForYouResult = lock.withLock {
        val profileId = profiles.currentIdNow()
        if (!force) cached()?.let { return@withLock it }
        val result = withContext(Dispatchers.Default) { compute() }
        // A profile switch while this ran: the result belongs to the profile it started with.
        synchronized(cache) { cache[profileId] = result }
        result
    }

    /** Drops the kept results (Up next changed, or a game was downloaded). */
    fun invalidate() = synchronized(cache) { cache.clear() }

    private suspend fun compute(): ForYouResult {
        val restrictions = profiles.current()
        val seeds = seeds(restrictions)
        val ownedKeys = libraryIndex.ownedKeys.value
        val rows = ArrayList<ForYou.Row>()
        var after = 0L
        while (rows.size < MAX_ROWS) {
            currentCoroutineContext().ensureActive()
            val page = withContext(Dispatchers.IO) { fileDao.lightPage(after, PAGE) }
            if (page.isEmpty()) break
            for (r in page) rows += ForYou.Row(r.id, r.consoleId, r.fileName, r.name, LibraryKeys.isOwned(r.consoleId, r.fileName, ownedKeys))
            after = page.last().id
            if (page.size < PAGE) break
        }
        val index = runCatching { LibraryDiscovery.buildIndex(metadata.observeKnown().first().map { LibraryDiscovery.RawRow(it.lookupKey, it.genres, it.released, it.developer) }) }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Details not read: ${it.javaClass.simpleName}") }
            .getOrDefault(LibraryDiscovery.Index.EMPTY)
        val popular = popular(rows.mapTo(HashSet()) { it.consoleId })
        val context = currentCoroutineContext()
        val ranked = ForYou.suggest(
            seeds, rows, { _, key -> index.byKey[key] }, popular,
            excluded = upNext.keys.value, hiddenConsoles = restrictions.hiddenConsoles,
            limit = LIMIT * 2, checkActive = { context.ensureActive() }
        )
        return ForYouResult(pickVersions(ranked, restrictions).take(LIMIT), System.currentTimeMillis(), seeds.size)
    }

    /** What the player cares about, newest first per kind, each kind bounded. */
    private suspend fun seeds(restrictions: LibraryRestrictions): List<ForYou.Seed> = withContext(Dispatchers.IO) {
        val out = ArrayList<ForYou.Seed>()
        // One unreadable source (ES-DE folder gone, say) leaves the others.
        suspend fun safe(block: suspend () -> Unit) {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "Seed source skipped: ${e.javaClass.simpleName}") }
        }
        val named = ArrayList<Triple<String, String, ForYou.SeedKind>>()
        safe { favourites.getAll().sortedByDescending { it.addedAt }.take(MAX_SEEDS).forEach { named += Triple(it.consoleId, it.fileName, ForYou.SeedKind.FAVOURITE) } }
        safe { collections.getAllItems().sortedByDescending { it.addedAt }.take(MAX_SEEDS).forEach { named += Triple(it.consoleId, it.fileName, ForYou.SeedKind.COLLECTION) } }
        // The row names make readable titles; a game no source lists any more keeps a cleaned file name.
        val names = HashMap<String, String>()
        named.map { it.second }.distinct().chunked(400).forEach { chunk ->
            safe { fileDao.filesByFileNames(chunk).forEach { names[UpNext.key(it.consoleId, it.fileName)] = it.name } }
        }
        named.forEach { (console, file, kind) -> out += ForYou.Seed(console, file, names[UpNext.key(console, file)] ?: file, kind) }
        upNext.entries.value.orEmpty().forEach { out += ForYou.Seed(it.consoleId, it.fileName, it.title, ForYou.SeedKind.UP_NEXT) }
        safe {
            playHistory.plays()?.filter { it.openable && it.kind == HistoryKind.PLAYED }?.take(MAX_SEEDS)
                ?.forEach { out += ForYou.Seed(it.consoleId, it.fileName, it.title, ForYou.SeedKind.PLAYED) }
        }
        safe { playHistory.downloads().take(MAX_SEEDS).forEach { out += ForYou.Seed(it.consoleId, it.fileName, it.title, ForYou.SeedKind.DOWNLOADED) } }
        // A game on a console the profile hides never explains a suggestion.
        out.filter { it.consoleId !in restrictions.hiddenConsoles }
    }

    /** Per library console, the titles of its cached RetroAchievements list ([BestGames.titleKey]) with their place. */
    private suspend fun popular(consoles: Set<String>): Map<String, Map<String, ForYou.Popular>> {
        val out = HashMap<String, Map<String, ForYou.Popular>>()
        for (consoleId in consoles) {
            val ra = RaConsoleMap.forConsole(consoleId, ConsoleFormatter.getConsoleDisplayName(consoleId)) ?: continue
            try {
                val list = bestGames.cachedGames(ra.id) ?: continue
                val ranking = BestGames.rank(list.games, bestGames.cachedPlayers(ra.id))
                val byPlayers = ranking.basis == RankBasis.PLAYERS
                val titles = HashMap<String, ForYou.Popular>()
                for (ranked in ranking.games) {
                    BestGames.alternatives(ranked.game.title).map { BestGames.titleKey(it) }.filter { it.isNotEmpty() }
                        .forEach { titles.putIfAbsent(it, ForYou.Popular(ranked.rank, byPlayers)) }
                }
                if (titles.isNotEmpty()) out[consoleId] = titles
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w(TAG, "Best games cache skipped: ${e.javaClass.simpleName}") }
        }
        return out
    }

    /**
     * Leaves out the versions the profile hides by tag, and shows the best of the rest by the
     * player's version preference (the same pick as the game page); a title with nothing left goes.
     */
    private suspend fun pickVersions(ranked: List<ForYou.Suggestion>, restrictions: LibraryRestrictions): List<ForYou.Suggestion> {
        val ids = ranked.flatMap { s -> s.versions.map { it.id } }
        val tags = withContext(Dispatchers.IO) {
            runCatching { ids.chunked(400).flatMap { fileDao.tagsOfFiles(it) }.groupBy({ it.fileId }, { it.tag }) }
                .onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyMap())
        }
        val preference = runCatching { versionSettings.global.first() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        return ranked.mapNotNull { s ->
            val allowed = s.versions.filter { restrictions.allows(it.consoleId, tags[it.id].orEmpty()) }
            if (allowed.isEmpty()) return@mapNotNull null
            val best = if (preference == null || allowed.size == 1) allowed.first() else {
                val candidates = allowed.map { VersionPicker.Candidate(it.id.toString(), it.name, tags[it.id].orEmpty()) }
                val pick = runCatching { VersionPicker.best(candidates, preference) }.getOrNull()
                allowed.firstOrNull { it.id.toString() == pick?.id } ?: allowed.first()
            }
            s.copy(row = best, versions = allowed)
        }
    }

    companion object {
        /** Library rows read at most (a library can hold 50,000). */
        const val MAX_ROWS = 60_000
        private const val PAGE = 2_000
        const val LIMIT = 30
        private const val MAX_SEEDS = 300
        const val VALID_MS = 15 * 60 * 1000L
    }
}
