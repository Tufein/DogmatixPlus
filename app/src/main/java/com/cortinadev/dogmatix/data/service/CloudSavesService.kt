package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.CloudSaveEntry
import com.cortinadev.dogmatix.util.CloudSaveResult
import com.cortinadev.dogmatix.util.CloudSaveVersion
import com.cortinadev.dogmatix.util.CloudSaves
import com.cortinadev.dogmatix.util.DeviceSave
import com.cortinadev.dogmatix.util.RestoreTarget
import com.cortinadev.dogmatix.util.SafetyCopy
import com.cortinadev.dogmatix.util.SaveConflict
import com.cortinadev.dogmatix.util.SaveKind
import com.cortinadev.dogmatix.util.SaveStore
import com.cortinadev.dogmatix.util.SaveSyncPlanner
import com.cortinadev.dogmatix.util.SaveSyncRecord
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CloudSavesService"

/** A game's server listing is read again after this long (and after every sync, restore or upload). */
private const val SERVER_TTL_MS = 2 * 60 * 1000L

/** The device folders are scanned again after this long (a scan walks every save folder). */
private const val DEVICE_TTL_MS = 60 * 1000L

/** Everything the "Cloud saves" section of a game shows. */
data class GameCloudSaves(
    val consoleId: String,
    val fileName: String,
    /** The game on the RomM server; null when the server does not have it (or it is not known yet). */
    val romId: Int? = null,
    val loadingServer: Boolean = false,
    val loadingDevice: Boolean = false,
    /** The server's saves and states of the game, newest first. */
    val server: List<CloudSaveVersion> = emptyList(),
    val serverError: String? = null,
    /** The device's saves of the game and how each stands against the server. */
    val device: List<DeviceSave> = emptyList(),
    val deviceError: String? = null,
    /** Copies kept in the app before a sync or a restore replaced a file. */
    val safetyCopies: List<SafetyCopy> = emptyList(),
    /** A device folder is picked for saves / states (restoring needs one). */
    val canRestoreSaves: Boolean = false,
    val canRestoreStates: Boolean = false
) {
    /** The section shows when the game is on the server or has safety copies. */
    val visible: Boolean get() = romId != null || safetyCopies.isNotEmpty()

    fun canRestore(kind: SaveKind): Boolean = if (kind == SaveKind.SAVE) canRestoreSaves else canRestoreStates
}

/** Screenshots of the two sides of a save-state conflict. */
data class StateShots(
    /** `content://` URI of the PNG next to the device's state. */
    val deviceUri: String? = null,
    val deviceModified: Long? = null,
    /** URL of the screenshot RomM keeps with the server's state. */
    val serverUrl: String? = null,
    val serverModified: Long? = null
) {
    val any: Boolean get() = deviceUri != null || serverUrl != null
}

/**
 * Cloud saves you can see (5.0): for one library game, the RomM server's saves and states (with
 * state screenshots when RomM has them), the device's own files and the safety copies the sync kept,
 * plus "Restore this version" and "Upload now" (both through [SaveSyncService], which keeps a copy of
 * whatever they replace). Everything runs on Dispatchers.IO; network and parse failures become an
 * error line, never a crash.
 */
@Singleton
class CloudSavesService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val rommClient: RommClient,
    private val rommLibrary: RommLibraryService,
    private val saveSync: SaveSyncService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Timed<T>(val at: Long, val value: T)

    private val serverCache = ConcurrentHashMap<Int, Timed<List<CloudSaveEntry>>>()
    @Volatile private var deviceCache: Timed<SaveStore.Listing?>? = null

    init {
        // A finished sync changes both sides: read them again next time.
        scope.launch {
            saveSync.state.map { it.last?.finishedAt }.distinctUntilChanged().collect { invalidate() }
        }
    }

    /** Forget what was read (after a sync, restore or upload). */
    fun invalidate() {
        serverCache.clear()
        deviceCache = null
    }

    /** RomM is set up (server and token). */
    suspend fun rommReady(): Boolean =
        rommClient.configuredBaseUrl().isNotEmpty() && settingsRepository.rommToken.first().isNotBlank()

    /**
     * The game's RomM id: from the library refresh, else from a save of the game an earlier sync
     * tied to a ROM. Null when neither knows it.
     */
    suspend fun romIdFor(consoleId: String, fileName: String, records: Map<String, SaveSyncRecord>? = null): Int? {
        if (!rommReady()) return null
        rommLibrary.gameFor(consoleId, fileName)?.romId?.let { return it }
        // The server's game list knows which games it has: a save of the same name on another console
        // (a Tetris.srm of the Game Boy for the NES Tetris) must not be taken for this game's.
        if (rommLibrary.games.value.isNotEmpty()) return null
        val stem = CloudSaves.gameStem(fileName)
        val known = records ?: runCatching { saveSync.syncRecords() }.getOrDefault(emptyMap())
        return known.values.filter { CloudSaves.belongsToGame(it.path.substringAfterLast('/'), stem) }
            .groupingBy { it.romId }.eachCount().maxByOrNull { it.value }?.key
    }

    /** First, quick part: RomM id and safety copies (no network, no folder scan). */
    suspend fun loadLocal(consoleId: String, fileName: String): GameCloudSaves = withContext(Dispatchers.IO) {
        val records = runCatching { saveSync.syncRecords() }.getOrDefault(emptyMap())
        val romId = romIdFor(consoleId, fileName, records)
        val stem = CloudSaves.gameStem(fileName)
        GameCloudSaves(
            consoleId = consoleId,
            fileName = fileName,
            romId = romId,
            safetyCopies = CloudSaves.safetyCopiesFor(safetyCopies(), stem, romId, records),
            canRestoreSaves = saveSync.hasDeviceFolder(SaveKind.SAVE),
            canRestoreStates = saveSync.hasDeviceFolder(SaveKind.STATE)
        )
    }

    /** Everything: [loadLocal] plus the server listing and the device folders, read side by side. */
    suspend fun load(consoleId: String, fileName: String, force: Boolean = false): GameCloudSaves = withContext(Dispatchers.IO) {
        if (force) invalidate()
        val base = loadLocal(consoleId, fileName)
        val romId = base.romId
        val stem = CloudSaves.gameStem(fileName)
        coroutineScope {
            val serverJob = async { if (romId == null || !rommReady()) Result.success(emptyList<CloudSaveEntry>()) else runCatching { serverEntries(romId) } }
            val deviceJob = async { runCatching { deviceListing() } }
            val records = runCatching { saveSync.syncRecords() }.getOrDefault(emptyMap())
            val serverResult = serverJob.await()
            val deviceResult = deviceJob.await()
            val entries = serverResult.getOrDefault(emptyList())
            val locals = deviceResult.getOrNull()?.files.orEmpty()
            val url = rommClient.configuredBaseUrl()
            base.copy(
                server = CloudSaves.versions(entries, records.values, locals, url),
                serverError = serverResult.exceptionOrNull()?.let { messageOf(it) },
                device = CloudSaves.deviceSaves(locals, stem, romId, records, entries),
                deviceError = deviceResult.exceptionOrNull()?.let { messageOf(it) }
            )
        }
    }

    /**
     * The saves and states RomM keeps for ROM [romId]: `GET /api/{saves|states}?rom_id=` (filtered
     * again here, as older servers ignore the parameter), else the ROM's own `user_saves` /
     * `user_states`. Cached for a short while.
     */
    suspend fun serverEntries(romId: Int, force: Boolean = false): List<CloudSaveEntry> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force) serverCache[romId]?.takeIf { now - it.at < SERVER_TTL_MS }?.let { return@withContext it.value }
        val base = rommClient.configuredBaseUrl().ifEmpty { throw RommException("RomM server URL not set") }
        val headers = rommClient.downloadHeaders()
        var detail: JsonHttp.Response? = null
        val entries = SaveKind.entries.flatMap { kind ->
            val listed = JsonHttp.request("GET", "$base/api/${kind.apiPath}?rom_id=$romId", headers, readTimeoutMs = 60_000)
            if (listed.code == 401 || listed.code == 403) JsonHttp.requireOk(listed)
            val fromListing = if (listed.ok && listed.json != null && (listed.json.isJsonArray || listed.json.isJsonObject)) {
                CloudSaves.parse(kind, listed.json)
            } else null
            fromListing ?: run {
                // Older servers: the ROM's own details carry the user's saves and states.
                val d = detail ?: JsonHttp.requireOk(JsonHttp.request("GET", "$base/api/roms/$romId", headers, readTimeoutMs = 60_000)).also { detail = it }
                CloudSaves.parse(kind, d.json?.takeIf { it.isJsonObject }?.asJsonObject?.get("user_${kind.apiPath}"))
            }
        }.filter { it.romId == romId }
        serverCache[romId] = Timed(now, entries)
        entries
    }

    /** The device listing, scanned at most once a minute; null when no folder is picked. */
    private suspend fun deviceListing(): SaveStore.Listing? {
        val now = System.currentTimeMillis()
        deviceCache?.takeIf { now - it.at < DEVICE_TTL_MS }?.let { return it.value }
        return saveSync.deviceListing().also { deviceCache = Timed(now, it) }
    }

    /** Every safety copy in `files/save-backups`. */
    private fun safetyCopies(): List<SafetyCopy> {
        val root = saveSync.safetyCopiesDir
        if (!root.isDirectory) return emptyList()
        val zone = ZoneId.systemDefault()
        return root.walkTopDown().maxDepth(8).filter { it.isFile }.mapNotNull { f ->
            val relative = f.relativeToOrNull(root)?.invariantSeparatorsPath ?: return@mapNotNull null
            CloudSaves.safetyCopy(relative, f.length(), f.lastModified(), zone)
        }.toList()
    }

    // ---- Actions ------------------------------------------------------------------------------

    /** "Restore this version" of a server save / state. */
    suspend fun restore(state: GameCloudSaves, version: CloudSaveVersion): CloudSaveResult {
        val entries = state.romId?.let { runCatching { serverEntries(it, force = true) }.getOrNull() } ?: state.server.map { it.entry }
        val result = saveSync.restoreServerVersion(version.entry, entries, CloudSaves.gameStem(state.fileName))
        invalidate()
        return result
    }

    /**
     * The device path "Restore this version" would write [version] to (so the confirm dialog can
     * name the folder); null when it cannot be told or no single file fits.
     */
    suspend fun restoreTargetPath(state: GameCloudSaves, version: CloudSaveVersion): String? = withContext(Dispatchers.IO) {
        val listing = runCatching { deviceListing() }.getOrNull() ?: return@withContext null
        val records = runCatching { saveSync.syncRecords() }.getOrDefault(emptyMap())
        val target = CloudSaves.restoreTarget(
            version.entry, CloudSaves.gameStem(state.fileName), listing.files, records.values, listing.topFolders, listing.noRootFolder
        )
        (target as? RestoreTarget.Path)?.path
    }

    /** Puts a safety copy back in place. */
    suspend fun restore(state: GameCloudSaves, copy: SafetyCopy): CloudSaveResult {
        val entries = state.romId?.let { id -> if (rommReady()) runCatching { serverEntries(id, force = true) }.getOrNull() else null }
            ?: state.server.map { it.entry }
        val result = saveSync.restoreSafetyCopy(copy, entries)
        invalidate()
        return result
    }

    /** "Upload now" of a device save. */
    suspend fun upload(state: GameCloudSaves, save: DeviceSave): CloudSaveResult {
        val romId = state.romId ?: return CloudSaveResult.Failed(context.getString(R.string.csave_error_not_on_server))
        val entries = runCatching { serverEntries(romId, force = true) }.getOrElse { e -> return CloudSaveResult.Failed(messageOf(e)) }
        val result = saveSync.uploadDeviceSave(save.local.kind, save.local.path, romId, entries)
        invalidate()
        return result
    }

    // ---- Conflict screenshots -----------------------------------------------------------------

    /** The screenshots of both sides of a save-state conflict (either may be missing). */
    suspend fun stateShots(conflict: SaveConflict): StateShots = withContext(Dispatchers.IO) {
        if (conflict.local.kind != SaveKind.STATE) return@withContext StateShots()
        val device = runCatching { saveSync.deviceStateShot(conflict.local) }.getOrNull()
        val server = if (!rommReady()) null else runCatching {
            serverEntries(conflict.remote.romId).firstOrNull { it.kind == SaveKind.STATE && it.id == conflict.remote.id }
        }.onFailure { Log.w(TAG, "No server screenshot for ${conflict.remote.fileName}: ${it.message}") }.getOrNull()
        StateShots(
            deviceUri = device?.first,
            deviceModified = device?.second?.takeIf { it > 0 },
            serverUrl = server?.let { CloudSaves.screenshotUrl(rommClient.configuredBaseUrl(), it) },
            serverModified = server?.updatedMillis ?: SaveSyncPlanner.epochMillis(conflict.remote.updatedAt)
        )
    }

    private fun messageOf(e: Throwable): String = when (e) {
        is JsonHttp.HttpException -> "HTTP ${e.code}"
        else -> e.message?.take(160) ?: e.javaClass.simpleName
    }
}

/** For composables outside a ViewModel (the top-bar icon, conflict rows). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CloudSavesEntryPoint {
    fun cloudSaves(): CloudSavesService
    fun cloudStatus(): CloudStatusService
}
