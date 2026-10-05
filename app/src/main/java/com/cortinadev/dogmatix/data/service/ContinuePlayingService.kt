package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.local.CloudSavesSettings
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.CloudSaveEntry
import com.cortinadev.dogmatix.util.CloudSaves
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.ContinuePlaying
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.RommMarks
import com.cortinadev.dogmatix.util.SaveKind
import com.cortinadev.dogmatix.util.SearchNormalizer
import com.cortinadev.dogmatix.util.ShelfEntry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ContinuePlaying"

/** The shelf is worked out again when older than this (and after every save sync). */
private const val STALE_MS = 10 * 60 * 1000L

/** At most this many `GET /api/roms/{id}` per refresh for games the library refresh does not know. */
private const val MAX_ROM_LOOKUPS = 8

/** After a failed refresh (offline, server down), the next try waits this long. */
private const val RETRY_MS = 60 * 1000L

/** How long a refresh waits for the RomM game list to be read back at app start. */
private const val GAMES_WAIT_MS = 5_000L

/** A card of the "Continue playing" shelf. */
data class ContinueItem(
    /** The library row; opening the game's details takes exactly this. */
    val row: DownloadableFileWithTags,
    /** When it was last saved (RomM) or played (ES-DE), epoch millis. */
    val at: Long,
    /** Device or emulator that saved it ("Thor", "mGBA"); null when unknown. */
    val via: String?
)

/**
 * The "Continue playing" shelf on Home (5.0): the library games most recently saved on ANY device —
 * RomM's saves and states, newest first, mapped back to library rows through the RomM game list —
 * or, without RomM, the games ES-DE played last. Worked out on Dispatchers.IO, kept in memory and in
 * `files/continue_playing.json` (so the shelf is there at once on the next start), refreshed when it
 * is shown and stale, and after every save sync.
 */
@Singleton
class ContinuePlayingService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val rommClient: RommClient,
    private val rommLibrary: RommLibraryService,
    private val esdePlays: EsdePlayService,
    private val dao: DownloadableFileDao,
    private val settings: CloudSavesSettings,
    saveSync: SaveSyncService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val cacheFile = File(context.filesDir, "continue_playing.json")

    private val _items = MutableStateFlow<List<ContinueItem>>(emptyList())
    val items: StateFlow<List<ContinueItem>> = _items.asStateFlow()

    @Volatile private var lastRefresh = 0L
    @Volatile private var cacheLoaded = false
    @Volatile private var shown = false

    /** `consoleId|name` keys of ROMs looked up one by one (when the library refresh does not list them). */
    private val lookedUp = ConcurrentHashMap<Int, List<String>>()

    init {
        // A finished sync may have brought saves from another device.
        scope.launch {
            saveSync.state.map { it.last?.finishedAt }.distinctUntilChanged().drop(1).collect { finished ->
                if (finished != null && shown) refresh(force = true)
            }
        }
    }

    /** The shelf is on screen: show the stored shelf at once, then work it out again when stale. */
    fun onShown() {
        shown = true
        scope.launch {
            if (!cacheLoaded) loadCache()
            refresh(force = false)
        }
    }

    /** Work the shelf out again now (pull to refresh, a changed setting). */
    fun refreshNow() {
        scope.launch { refresh(force = true) }
    }

    private suspend fun refresh(force: Boolean) {
        if (!settings.continuePlaying.first()) return
        if (!force && System.currentTimeMillis() - lastRefresh < STALE_MS) return
        if (!lock.tryLock()) return
        try {
            val resolved = runCatching { compute() }
                .onFailure { Log.w(TAG, "Shelf not refreshed: ${it.message}") }
                .getOrNull()
            if (resolved == null) {
                // Keep what is shown; try again in a minute rather than at every visit to Home.
                lastRefresh = System.currentTimeMillis() - STALE_MS + RETRY_MS
                return
            }
            lastRefresh = System.currentTimeMillis()
            _items.value = withTags(resolved)
            persist(resolved.map { it.first })
        } finally {
            lock.unlock()
        }
    }

    private suspend fun rommReady(): Boolean =
        rommClient.configuredBaseUrl().isNotEmpty() && settingsRepository.rommToken.first().isNotBlank()

    /** The shelf's entries with their rows; null when the server could not be read (the old shelf stays). */
    private suspend fun compute(): List<Pair<ShelfEntry, DownloadableFileEntity>>? {
        val romm = rommReady()
        if (romm) {
            // Saves and states are read apart: a server without one of the two still fills the shelf.
            val listings = SaveKind.entries.map { kind ->
                runCatching { serverListing(kind) }.onFailure { Log.w(TAG, "RomM ${kind.apiPath} not read: ${it.message}") }
            }
            if (listings.all { it.isFailure }) return null
            val entries = listings.flatMap { it.getOrDefault(emptyList()) }
            val fromServer = fromServer(entries)
            if (fromServer.isNotEmpty()) return fromServer
        }
        // Without RomM (or nothing saved there yet): what ES-DE played last.
        return fromEsde()
    }

    /** Every save (or state) of the account: the listing the sync reads too. */
    private suspend fun serverListing(kind: SaveKind) =
        CloudSaves.parse(
            kind,
            JsonHttp.requireOk(
                JsonHttp.request("GET", "${rommClient.configuredBaseUrl()}/api/${kind.apiPath}", rommClient.downloadHeaders(), readTimeoutMs = 60_000)
            ).json
        )

    private suspend fun fromServer(entries: List<CloudSaveEntry>): List<Pair<ShelfEntry, DownloadableFileEntity>> {
        val recent = ContinuePlaying.recentServerSaves(entries, limit = ContinuePlaying.DEFAULT_LIMIT * 2)
        if (recent.isEmpty()) return emptyList()
        val keysByRom = ContinuePlaying.keysByRomId(awaitGames()) { it.romId }
        val namesByConsole = HashMap<String, List<String>>()
        val out = mutableListOf<Pair<ShelfEntry, DownloadableFileEntity>>()
        var lookups = 0
        for (save in recent) {
            val keys = keysByRom[save.romId] ?: lookedUp[save.romId]
                ?: if (lookups < MAX_ROM_LOOKUPS) { lookups++; lookupRom(save.romId) } else null
            val row = keys?.firstNotNullOfOrNull { key -> rowForKey(key, namesByConsole) } ?: continue
            if (out.any { (_, r) -> RommMarks.key(r.consoleId, r.fileName) == RommMarks.key(row.consoleId, row.fileName) }) continue
            out += ShelfEntry(row.consoleId, row.fileName, save.at, save.via) to row
            if (out.size >= ContinuePlaying.DEFAULT_LIMIT) break
        }
        return out
    }

    /** The RomM game list, waiting a moment at app start while it is read back from disk. */
    private suspend fun awaitGames(): Map<String, RommGameRef> {
        val now = rommLibrary.games.value
        if (now.isNotEmpty() || !settingsRepository.rommMarkGames.first()) return now
        return withTimeoutOrNull(GAMES_WAIT_MS) { rommLibrary.games.first { it.isNotEmpty() } } ?: rommLibrary.games.value
    }

    /** `GET /api/roms/{id}` → the library keys of that ROM on every console mapped to its platform. */
    private suspend fun lookupRom(romId: Int): List<String>? = runCatching {
        val json = JsonHttp.requireOk(
            JsonHttp.request("GET", "${rommClient.configuredBaseUrl()}/api/roms/$romId", rommClient.downloadHeaders())
        ).json
        val (fsName, platformId) = CloudSaves.romFileAndPlatform(json) ?: return@runCatching emptyList<String>()
        settingsRepository.rommPlatformMap.first().filterValues { it == platformId }.keys.sorted().map { RommMarks.key(it, fsName) }
    }.onFailure { Log.w(TAG, "ROM $romId not looked up: ${it.message}") }
        .getOrNull()?.also { lookedUp[romId] = it }

    /** The library row of a `consoleId|name` key: a narrow title search first, else the console's file names. */
    private suspend fun rowForKey(key: String, namesByConsole: MutableMap<String, List<String>>): DownloadableFileEntity? {
        val (consoleId, stem) = ContinuePlaying.splitKey(key) ?: return null
        val squashed = SearchNormalizer.key(stem)
        if (squashed.isNotEmpty()) {
            ContinuePlaying.rowForKey(key, dao.filesMatching(squashed, consoleId, 200), { it.consoleId }, { it.fileName })?.let { return it }
        }
        // The row's title differs from its file name (RomM rows): match the file names of that console.
        val names = namesByConsole.getOrPut(consoleId) { dao.fileNamesFor(consoleId) }
        val name = names.firstOrNull { RommMarks.key(consoleId, it) == key } ?: return null
        return dao.filesByFileNames(listOf(name)).firstOrNull { it.consoleId == consoleId }
    }

    private suspend fun fromEsde(): List<Pair<ShelfEntry, DownloadableFileEntity>> {
        val plays = runCatching { esdePlays.plays() }.getOrNull().orEmpty()
        val recent = ContinuePlaying.recentPlays(plays, ContinuePlaying.DEFAULT_LIMIT * 2)
        if (recent.isEmpty()) return emptyList()
        val exact = recent.map { ContinuePlaying.playFileName(it) }.distinct().chunked(400)
            .flatMap { dao.filesByFileNames(it) }
        val out = mutableListOf<Pair<ShelfEntry, DownloadableFileEntity>>()
        for (play in recent) {
            val name = ContinuePlaying.playFileName(play)
            var candidates = exact.filter { it.fileName.equals(name, ignoreCase = true) }
            if (candidates.isEmpty()) {
                val squashed = SearchNormalizer.key(LibraryKeys.baseName(name))
                if (squashed.isNotEmpty()) candidates = dao.filesMatching(squashed, null, 50)
            }
            val row = ContinuePlaying.rowForPlay(play, candidates, { it.consoleId }, { it.fileName }, ConsoleFolderAliases::matches) ?: continue
            if (out.any { (_, r) -> r.consoleId == row.consoleId && r.fileName == row.fileName }) continue
            out += ShelfEntry(row.consoleId, row.fileName, play.lastPlayed ?: 0L, null) to row
            if (out.size >= ContinuePlaying.DEFAULT_LIMIT) break
        }
        return out
    }

    private suspend fun withTags(resolved: List<Pair<ShelfEntry, DownloadableFileEntity>>): List<ContinueItem> {
        val ids = resolved.map { it.second.id }.distinct()
        val tags = ids.chunked(400).flatMap { dao.tagsOfFiles(it) }.groupBy({ it.fileId }, { it.tag })
        return resolved.map { (entry, row) -> ContinueItem(DownloadableFileWithTags(row, tags[row.id].orEmpty()), entry.at, entry.via) }
    }

    // ---- The stored shelf ---------------------------------------------------------------------

    private suspend fun loadCache() {
        cacheLoaded = true
        val entries = runCatching {
            if (!cacheFile.exists()) return
            val root = JsonParser.parseString(cacheFile.readText()).asJsonObject
            root.getAsJsonArray("items")?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                runCatching {
                    ShelfEntry(
                        consoleId = o.get("c").asString,
                        fileName = o.get("f").asString,
                        at = o.get("t")?.asLong ?: 0L,
                        via = o.get("v")?.takeUnless { it.isJsonNull }?.asString
                    )
                }.getOrNull()
            }.orEmpty()
        }.onFailure { Log.w(TAG, "Unreadable stored shelf; starting over", it) }.getOrDefault(emptyList())
        if (entries.isEmpty() || _items.value.isNotEmpty()) return
        val rows = entries.map { it.fileName }.distinct().chunked(400).flatMap { dao.filesByFileNames(it) }
        val resolved = ContinuePlaying.dedupe(entries).mapNotNull { e ->
            rows.firstOrNull { it.consoleId == e.consoleId && it.fileName == e.fileName }?.let { e to it }
        }
        if (_items.value.isEmpty()) _items.value = withTags(resolved)
    }

    private fun persist(entries: List<ShelfEntry>) {
        runCatching {
            val array = JsonArray()
            entries.forEach { e ->
                array.add(JsonObject().apply {
                    addProperty("c", e.consoleId)
                    addProperty("f", e.fileName)
                    addProperty("t", e.at)
                    e.via?.let { addProperty("v", it) }
                })
            }
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(JsonObject().apply { addProperty("version", 1); add("items", array) }.toString())
            if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile) }
        }.onFailure { Log.w(TAG, "Could not store the shelf", it) }
    }
}
