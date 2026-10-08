package com.cortinadev.dogmatix.data.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cortinadev.dogmatix.DogmatixApplication
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.OfflineCollectionsSettings
import com.cortinadev.dogmatix.data.local.dao.CollectionDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.util.ConditionKind
import com.cortinadev.dogmatix.util.DownloadCondition
import com.cortinadev.dogmatix.util.DuplicateFinder
import com.cortinadev.dogmatix.util.GameEntry
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.OfflineCollections
import com.cortinadev.dogmatix.util.OfflineCollections.Game
import com.cortinadev.dogmatix.util.StorageInsights
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OfflineCollections"
private const val NOTIFICATION_ID = 4231
private const val GB = 1024L * 1024 * 1024

/** A fetched game that left its collection and is still on the device; [collectionNames] are the collections it came for. */
data class StaleGame(val game: Game, val collectionNames: List<String>)

/** What the screen shows: whether a run is going, what each switched-on collection stands at, and the games to review. */
data class OfflineState(
    val planned: List<OfflineCollections.Pick> = emptyList(),
    val running: Boolean = false,
    /** A plan has been worked out since the app started (before that the lines say nothing). */
    val checked: Boolean = false,
    val tallies: Map<Long, OfflineCollections.Tally> = emptyMap(),
    val review: List<StaleGame> = emptyList(),
    /** No download folder is set, so nothing can be fetched. */
    val noFolder: Boolean = false
)

/** The files a removal would delete, per game; shown in the confirmation before anything goes. */
data class RemovalPlan(val items: List<Item>) {
    data class Item(val game: StaleGame, val entries: List<GameEntry>)

    val files: List<String> get() = items.flatMap { i -> i.entries.flatMap { e -> e.files.map { it.name } } }
    val bytes: Long get() = items.sumOf { i -> i.entries.sumOf { it.size } }
}

/**
 * 7.0 "Keep a collection on this device". For every own collection switched on (RomM collections
 * come in through *From RomM*, which makes them own collections too) it queues the games that the
 * library lists but the device does not have, through [DownloadService] (so the download rules and
 * "download when" conditions apply), at most the per-run cap and only as far as the free space
 * reaches. It runs after every source scan (also the background one), after a RomM refresh and on
 * "Fetch now". It never deletes anything: games it fetched that left their collection are only
 * listed ([OfflineState.review]) and go after [remove], which the screen asks to confirm.
 */
@OptIn(FlowPreview::class)
@Singleton
class OfflineCollectionsService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val collectionDao: CollectionDao,
    private val fileDao: DownloadableFileDao,
    private val settings: OfflineCollectionsSettings,
    private val downloadService: DownloadService,
    private val libraryIndex: LibraryIndexService,
    private val scanService: LibraryScanService,
    private val settingsRepository: SettingsRepository,
    private val appSettings: AppSettings,
    rescan: RescanStateHolder,
    rommLibrary: RommLibraryService,
    private val smartCollections: SmartCollectionsService,
    private val profiles: ProfileService
) {
    enum class Trigger { MANUAL, AUTO }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _state = MutableStateFlow(OfflineState())
    val state: StateFlow<OfflineState> = _state.asStateFlow()

    val keptIds: Flow<Set<Long>> = settings.collectionIds
    val cap: Flow<Int> = settings.cap
    val wifiOnly: Flow<Boolean> = settings.wifiOnly
    val lastRun: Flow<OfflineCollections.RunInfo?> = settings.lastRun
    val quotas = settings.quotas
    val reserveGb = settings.reserveGb
    fun setQuota(id: Long, gb: Int) { scope.launch { settings.setQuota(id, gb); preview() } }
    fun setReserveGb(gb: Int) { scope.launch { settings.setReserveGb(gb); preview() } }

    /**
     * Moves when what [preview] is made of moves. The screen collects it (debounced) while it is
     * open, so nothing is worked out while nobody looks.
     */
    val changes: Flow<Unit> = merge(
        settings.collectionIds.map { }, settings.cap.map { }, settings.fetched.map { }, settings.quotas.map { }, settings.reserveGb.map { },
        collectionDao.observeAll().map { }, libraryIndex.ownedKeys.map { }, profiles.activeId.map { }
    )

    init {
        // A finished source scan (also the background one) or RomM refresh may have brought games in.
        scope.launch {
            merge(
                rescan.lastRescanTime.drop(1).map { },
                rommLibrary.state.map { it.updatedAt }.distinctUntilChanged().drop(1).map { }
            ).debounce(3_000).collect {
                try { run(Trigger.AUTO) } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "Run failed: ${e.javaClass.simpleName}") }
            }
        }
    }

    /** Switches [id] on or off. On: the first run starts at once. Off: the games stay and are forgotten, never offered for removal. */
    fun setKept(id: Long, on: Boolean) {
        scope.launch {
            settings.setKept(id, on)
            if (!on) settings.forgetCollection(id)
            preview()
        }
    }

    /** "Fetch now": a run on the service's own scope, so it finishes when the screen is left. */
    fun fetchNow(approved: Set<Game>? = null) {
        scope.launch {
            try { run(Trigger.MANUAL, approved) } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "Run failed: ${e.javaClass.simpleName}") }
        }
    }

    fun setCap(cap: Int) { scope.launch { settings.setCap(cap) } }
    fun setWifiOnly(on: Boolean) { scope.launch { settings.setWifiOnly(on) } }

    /** Looks at every collection that is on without queueing anything; fills [state]. Skipped while a run is going. */
    suspend fun preview() = withContext(Dispatchers.IO) {
        if (lock.isLocked) return@withContext
        val snap = try {
            compute(refreshIndex = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (snap == null || lock.isLocked) return@withContext
        _state.update { it.copy(checked = true, tallies = snap.tallies, review = snap.review, noFolder = snap.noFolder, planned = snap.plan.picks) }
    }

    /**
     * Queues what is missing for every collection that is on, and returns how it went (null when
     * nothing is on, no download folder is set, or the collections could not be read). One run at a time.
     */
    suspend fun run(trigger: Trigger = Trigger.AUTO, approved: Set<Game>? = null): OfflineCollections.RunInfo? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (settings.keptNow().isEmpty()) {
                // Nothing on (maybe just switched off): the lines and the review still need to be current.
                compute(refreshIndex = false)?.let { snap -> _state.update { it.copy(checked = true, tallies = snap.tallies, review = snap.review) } }
                return@withLock null
            }
            _state.update { it.copy(running = true) }
            try {
                val snap = compute(refreshIndex = true) ?: return@withLock null
                if (snap.noFolder) {
                    _state.update { it.copy(checked = true, noFolder = true, tallies = snap.tallies, review = snap.review) }
                    return@withLock null
                }
                val picks = snap.plan.picks.filter { approved == null || it.game in approved }
                val files = picks.mapNotNull { snap.entities[it.game] }
                // Recorded before the downloads start: a game the record misses is never offered for removal.
                settings.addFetched(picks.flatMap { p -> p.collectionIds.map { OfflineCollections.Fetched(it, p.game) } })
                settings.removeFetched(snap.forget)
                if (files.isNotEmpty()) {
                    val condition = if (settings.wifiOnly.first()) DownloadCondition(ConditionKind.WIFI) else null
                    downloadService.startDownloads(files, condition)
                }
                val info = OfflineCollections.RunInfo(System.currentTimeMillis(), files.size, snap.plan.noSpace, snap.plan.overCap, snap.plan.missingBytes)
                val previous = settings.lastRun.first()
                settings.setLastRun(info)
                _state.update { it.copy(checked = true, noFolder = false, tallies = snap.tallies, review = snap.review, planned = emptyList()) }
                if (files.isNotEmpty() || (trigger == Trigger.AUTO && info.noSpace > 0 && !sameSpaceProblem(previous, info))) notify(info)
                info
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    private fun sameSpaceProblem(previous: OfflineCollections.RunInfo?, now: OfflineCollections.RunInfo) =
        previous != null && previous.noSpace == now.noSpace && previous.missingBytes == now.missingBytes

    /** Everything a run or a preview works from. */
    private class Snapshot(
        val plan: OfflineCollections.Plan,
        val entities: Map<Game, DownloadableFileEntity>,
        val tallies: Map<Long, OfflineCollections.Tally>,
        val review: List<StaleGame>,
        val forget: List<OfflineCollections.Fetched>,
        val noFolder: Boolean
    )

    private suspend fun compute(refreshIndex: Boolean): Snapshot? {
        smartCollections.refresh()
        val all = collectionDao.getAll()
        val existing = all.map { it.id }.toSet()
        settings.retainKept(existing)
        val keptIds = settings.keptNow().intersect(existing)
        val items = collectionDao.getAllItems().groupBy { it.collectionId }
        val contents = items.mapValues { (_, rows) -> rows.map { Game(it.consoleId, it.fileName) }.toSet() }

        val noFolder = settingsRepository.downloadDirectory.first().isBlank() && settingsRepository.consoleDownloadDirectories.first().values.all { it.isBlank() }
        // A fresh look at the disk before queueing, so a game that is already there is never fetched twice.
        if (refreshIndex && !noFolder) libraryIndex.refresh()
        val owned = libraryIndex.ownedKeys.value
        val onDevice = { g: Game -> LibraryKeys.isOwned(g.consoleId, g.fileName, owned) }
        val inQueue = downloadService.getDownloads().filter { it.status != DownloadStatus.COMPLETED }.mapTo(HashSet()) { it.fileName }
        val queued = { g: Game -> g.fileName in inQueue }

        val kept = all.filter { it.id in keptIds }.map { c ->
            OfflineCollections.Kept(c.id, c.name, collectionDao.itemsOf(c.id).map { Game(it.consoleId, it.fileName) }.distinct())
        }
        val restrictions = profiles.current()
        val entities = HashMap<Game, DownloadableFileEntity>()
        kept.flatMap { k -> k.games.map { it.consoleId } }.toSet().forEach { console ->
            fileDao.filesOf(console).forEach { if (restrictions.allows(console, fileDao.tagsOf(it.id))) entities.putIfAbsent(Game(console, it.fileName), it) }
        }
        val root = settingsRepository.downloadDirectory.first()
        val folders = settingsRepository.consoleDownloadDirectories.first()
        fun folder(console: String) = folders[console]?.takeIf { it.isNotBlank() } ?: root
        fun volume(uri: String): String = runCatching {
            val parsed = android.net.Uri.parse(uri)
            parsed.authority + ":" + android.provider.DocumentsContract.getTreeDocumentId(parsed).substringBefore(':')
        }.getOrDefault(uri)
        val offers = entities.mapValues { (_, e) ->
            OfflineCollections.Offer(e.fileSize, StorageInsights.isExtractable(e.fileExtension.ifEmpty { e.fileName.substringAfterLast('.', "") }), volume(folder(e.consoleId)))
        }
        val freeByVolume = entities.values.groupBy { volume(folder(it.consoleId)) }.mapValues { (_, group) ->
            com.cortinadev.dogmatix.util.StorageHelper.getFreeBytes(context, folder(group.first().consoleId))
        }

        val activeQueue = downloadService.getDownloads().filter { it.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.COPYING, DownloadStatus.UNZIPPING, DownloadStatus.PAUSED) }
        val queueByVolume = activeQueue.groupBy { volume(folder(downloadService.entityFor(it.fileName)?.consoleId.orEmpty())) }
            .mapValues { (_, rows) -> com.cortinadev.dogmatix.util.SpaceMath.sum(rows.map { item ->
                val remaining = (item.fileSize - item.downloadedBytes).coerceAtLeast(0)
                com.cortinadev.dogmatix.util.SpaceMath.add(remaining, if (StorageInsights.isExtractable(item.fileName.substringAfterLast('.'))) item.fileSize else 0)
            }) }
        val reserve = maxOf(appSettings.minFreeGb.first().toLong(), settings.reserveGb.first().toLong()) * GB
        val reserveByVolume = freeByVolume.keys.associateWith { com.cortinadev.dogmatix.util.SpaceMath.add(reserve, queueByVolume[it] ?: 0) }
        val quotas = settings.quotas.first().mapValues { it.value.toLong() * GB }
        val disk = if (quotas.isEmpty() || noFolder) emptyList() else DuplicateFinder.entries(scanService.scan().files)
        val occupied = kept.associate { c -> c.id to c.games.distinct().sumOf { game ->
            if (onDevice(game)) OfflineCollections.entriesOf(game, disk).sumOf { it.size }
            else if (queued(game)) offers[game]?.let(OfflineCollections::need) ?: quotas[c.id] ?: 0L else 0L
        } }
        val plan = OfflineCollections.plan(kept, offers, onDevice, queued, libraryIndex.freeBytes.value, reserve, settings.cap.first(), quotas, occupied, freeByVolume, reserveByVolume)

        val review = OfflineCollections.review(settings.fetched.first(), contents, keptIds, onDevice, queued)
        val names = all.associate { it.id to it.name }
        val stale = review.stale.map { s -> StaleGame(s.game, s.collectionIds.mapNotNull { names[it] }.sorted()) }
        return Snapshot(plan, entities, plan.tallies.associateBy { it.collectionId }, stale, review.forget, noFolder || kept.any { c -> c.games.any { folder(it.consoleId).isBlank() } })
    }

    // ---- removing what left a collection (only ever after the user confirmed the listed files) ----

    /** The files on disk behind each game of the review, for the confirmation. Reads the download folders. */
    suspend fun prepareRemoval(games: List<StaleGame> = _state.value.review): RemovalPlan = withContext(Dispatchers.IO) {
        val entries = DuplicateFinder.entries(scanService.scan().files)
        RemovalPlan(games.map { RemovalPlan.Item(it, OfflineCollections.entriesOf(it.game, entries)) })
    }

    /**
     * Deletes exactly the files of [plan] (the ones the user was shown), then forgets those games.
     * Returns how many games lost files. A game whose files are not on disk any more is only forgotten.
     */
    suspend fun remove(plan: RemovalPlan): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            // The review is worked out again right before deleting: only games still in it (still out of
            // every collection, not back in a queue) are touched. When it cannot be read, nothing is.
            val stillStale = try {
                compute(refreshIndex = false)?.review?.mapTo(HashSet()) { it.game }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@withLock 0
            var removed = 0
            val done = ArrayList<Game>()
            for (item in plan.items) {
                if (item.game.game !in stillStale) continue
                if (item.entries.isEmpty()) { done += item.game.game; continue }
                var deleted = 0
                for (entry in item.entries) {
                    deleted += try { scanService.delete(entry, com.cortinadev.dogmatix.util.ActionReason.OFFLINE_COLLECTION) } catch (e: CancellationException) { throw e } catch (e: Exception) { 0 }
                }
                if (deleted > 0) { removed++; done += item.game.game }
            }
            if (done.isNotEmpty()) {
                val all = settings.fetched.first().filter { it.game in done.toSet() }
                settings.removeFetched(all)
            }
            removed
        }.also { preview() }
    }

    /** Keeps the games of [games] on the device and stops listing them (the record of what was fetched is dropped). */
    suspend fun keep(games: List<StaleGame>) = withContext(Dispatchers.IO) {
        val set = games.map { it.game }.toSet()
        settings.removeFetched(settings.fetched.first().filter { it.game in set })
        preview()
    }

    // ---- notification ----

    private fun notify(info: OfflineCollections.RunInfo) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val r = context.resources
        val title = if (info.queued > 0) r.getQuantityString(R.plurals.offline7_notify_queued, info.queued, info.queued)
            else context.getString(R.string.offline7_notify_space_title)
        val text = buildList {
            if (info.noSpace > 0) add(r.getQuantityString(R.plurals.offline7_notify_space, info.noSpace, info.noSpace, com.cortinadev.dogmatix.ui.components.formatBytes(info.missingBytes)))
            if (info.overCap > 0) add(r.getQuantityString(R.plurals.offline7_notify_more, info.overCap, info.overCap))
            if (isEmpty()) add(context.getString(R.string.offline7_notify_text))
        }.joinToString(" · ")
        val open = PendingIntent.getActivity(
            context, 6,
            Intent(context, MainActivity::class.java)
                .putExtra(PendingLibraryFilters.EXTRA_OPEN_ROUTE, NavRoutes.Downloads.route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, DogmatixApplication.SCAN_CHANNEL_ID)
            .setSmallIcon(if (info.queued > 0) R.drawable.ic_arrow_down else R.drawable.ic_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
