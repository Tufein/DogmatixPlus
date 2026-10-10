package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.CloudSaveEntry
import com.cortinadev.dogmatix.util.CloudSaveResult
import com.cortinadev.dogmatix.util.CloudSaves
import com.cortinadev.dogmatix.util.EmulatorSaveFolder
import com.cortinadev.dogmatix.util.EmulatorSaveFolders
import com.cortinadev.dogmatix.util.LocalSaveFile
import com.cortinadev.dogmatix.util.RemoteSaveFile
import com.cortinadev.dogmatix.util.RestoreTarget
import com.cortinadev.dogmatix.util.SafetyCopy
import com.cortinadev.dogmatix.util.SaveConflict
import com.cortinadev.dogmatix.util.SaveKind
import com.cortinadev.dogmatix.util.SaveServer
import com.cortinadev.dogmatix.util.SaveStore
import com.cortinadev.dogmatix.util.SaveSyncEngine
import com.cortinadev.dogmatix.util.SaveSyncPlanner
import com.cortinadev.dogmatix.util.SaveSyncRecord
import com.cortinadev.dogmatix.util.SaveSyncResult
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.ToastUtil
import com.cortinadev.dogmatix.util.VerifiedSafetyCopies
import com.cortinadev.dogmatix.util.JournalKey
import com.cortinadev.dogmatix.util.SaveHandoff
import com.cortinadev.dogmatix.util.SaveHandoffPreview
import com.cortinadev.dogmatix.util.SaveHandoffDirection
import com.cortinadev.dogmatix.util.Profiles
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SaveSyncService"

/** Bigger files are not saves (or not ones worth moving over the network on every sync). */
private const val MAX_FILE_BYTES = 64L * 1024 * 1024

/** Automatic syncs (app opened / back in front) run at most this often. */
private const val AUTO_INTERVAL_MS = 2 * 60 * 1000L

/** Device copies replaced by a download are kept this long in the app's private storage. */
private const val BACKUP_KEEP_MS = 30L * 24 * 60 * 60 * 1000

data class SaveSyncState(
    val running: Boolean = false,
    /** "3 / 12" while files are being transferred. */
    val progress: String? = null,
    val last: SaveSyncResult? = null,
    /** Error that stopped the whole sync (server unreachable, folder gone) or a failed choice. */
    val error: String? = null,
    val conflicts: List<SaveConflict> = emptyList()
)

data class LocalSafetyRestorePreview(
    val copy: SafetyCopy,
    val profileId: String,
    val backupSha256: String,
    val currentSha256: String?,
    val currentBytes: Long?,
    val configuration: String
)

/**
 * Keeps the emulator saves and save states on this device and on the RomM server the same,
 * in both directions: RomM becomes the place games save to and load from, so a game can be
 * continued on another device (or in RomM's web player).
 *
 * [SaveSyncEngine] does the work; this class gives it the picked folders (Storage Access
 * Framework) and the server ([RommClient]), runs it one sync at a time and keeps its records
 * in the app's private storage. A device file overwritten by a download is first copied to
 * `files/save-backups/` (kept 30 days).
 */
@Singleton
class SaveSyncService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val rommClient: RommClient,
    private val appSettings: AppSettings,
    private val history: OperationHistoryService,
    private val actionLog: ActionLogService,
    private val gameAccess: JournalGameAccess
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val storeFile = File(context.filesDir, "save_sync.json")
    private val backupDir = File(context.filesDir, "save-backups")
    @Volatile private var lastAutoSync = 0L

    private val _state = MutableStateFlow(SaveSyncState())
    val state: StateFlow<SaveSyncState> = _state.asStateFlow()

    private val engine = SaveSyncEngine(
        server = RommSaveServer(rommClient),
        store = SafSaveStore(),
        onProgress = { done, total -> _state.update { it.copy(progress = "$done / $total") } },
        syncDeletions = { settingsRepository.saveSyncDeletions.first() }
    )

    suspend fun isConfigured(): Boolean =
        rommClient.configuredBaseUrl().isNotEmpty() && settingsRepository.rommToken.first().isNotBlank() &&
            (settingsRepository.saveSyncSavesDir.first().isNotBlank() || settingsRepository.saveSyncStatesDir.first().isNotBlank() ||
                appSettings.saveSyncEmulatorFolders.first().isNotEmpty())

    /** Sync now (Save sync screen); ignored while one runs. [confirmDeletions] lets held-back deletions through. */
    fun syncNow(confirmDeletions: Boolean = false) {
        scope.launch { sync(confirmDeletions) }
    }

    /** App opened or back in front: sync when switched on, set up and not done a moment ago. */
    fun autoSync() {
        val now = System.currentTimeMillis()
        if (now - lastAutoSync < AUTO_INTERVAL_MS) return
        scope.launch {
            if (!settingsRepository.saveSyncAuto.first() || !isConfigured()) return@launch
            lastAutoSync = now
            val result = sync() ?: return@launch
            // Only worth a word when something moved or needs the user.
            val message = when {
                result.conflicts > 0 -> context.getString(R.string.save_sync_toast_conflicts, result.conflicts)
                result.uploaded + result.downloaded > 0 -> context.getString(R.string.save_sync_toast, result.uploaded, result.downloaded)
                else -> null
            }
            message?.let { withContext(Dispatchers.Main) { ToastUtil.showInfo(context, it) } }
        }
    }

    /** Keeps [keepDevice] ? the device copy (uploads it) : the server copy (downloads it). */
    fun resolve(conflict: SaveConflict, keepDevice: Boolean) {
        scope.launch {
            lock.withLock {
                val records = loadRecords().toMutableMap()
                runCatching { engine.resolve(conflict, keepDevice, records) }
                    .onSuccess {
                        saveRecords(records)
                        _state.update { s -> s.copy(error = null, conflicts = s.conflicts.filterNot { it.local.kind == conflict.local.kind && it.local.path == conflict.local.path }) }
                    }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Resolving ${conflict.local.path} failed", e)
                        _state.update { it.copy(error = "${conflict.local.name}: ${e.message}") }
                    }
            }
        }
    }

    /** One full sync; null when one was already running, nothing is set up or it failed as a whole. */
    suspend fun sync(confirmDeletions: Boolean = false): SaveSyncResult? {
        if (!lock.tryLock()) return null
        try {
            if (!isConfigured()) return null
            _state.update { it.copy(running = true, progress = null, error = null) }
            return withContext(Dispatchers.IO) {
                runCatching {
                    pruneBackups()
                    val records = loadRecords().toMutableMap()
                    val (result, conflicts) = engine.sync(records, confirmDeletions)
                    saveRecords(records)
                    history.event("save_sync", "${result.uploaded} / ${result.downloaded} / ${result.conflicts}", if (result.conflicts > 0) "conflict" else if (result.failed > 0) "failed" else "done")
                    actionLog.saveSync(result.uploaded, result.downloaded, result.conflicts, result.failed, result.deletedOnDevice, result.deletedOnServer)
                    _state.update { it.copy(last = result, conflicts = conflicts) }
                    result
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Save sync failed", e)
                    actionLog.saveSyncFailed()
                    _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                }.getOrNull()
            }
        } finally {
            _state.update { it.copy(running = false, progress = null) }
            lock.unlock()
        }
    }

    private fun pruneBackups() {
        val cutoff = System.currentTimeMillis() - BACKUP_KEEP_MS
        backupDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    // ---- Server -------------------------------------------------------------------------------

    private class RommSaveServer(private val client: RommClient) : SaveServer {
        override suspend fun list(kind: SaveKind) = client.saves(kind)
        override suspend fun download(save: RemoteSaveFile) = client.downloadSave(save, MAX_FILE_BYTES)
        override suspend fun upload(kind: SaveKind, romId: Int, fileName: String, emulator: String?, bytes: ByteArray) =
            client.uploadSave(kind, romId, fileName, emulator, bytes)
        override suspend fun searchRoms(term: String) = client.searchRoms(term)
        override suspend fun delete(save: RemoteSaveFile) = client.deleteSave(save)
    }

    // ---- Device folders -----------------------------------------------------------------------

    /**
     * The picked folders through the Storage Access Framework: every save / state three folder
     * levels deep (RetroArch sorts by core and by content folder). One folder picked for both
     * holds both kinds; the name tells which one a file is.
     */
    private inner class SafSaveStore(private val readLimit: Long = MAX_FILE_BYTES) : SaveStore {
        /** Document of every file of the last listing (and of later writes), by [SaveSyncEngine.key]. */
        private var documents: Map<String, Uri> = emptyMap()

        override suspend fun list(): SaveStore.Listing = withContext(Dispatchers.IO) {
            val savesUri = settingsRepository.saveSyncSavesDir.first()
            val statesUri = settingsRepository.saveSyncStatesDir.first()
            val files = mutableListOf<Pair<LocalSaveFile, Uri>>()
            val topFolders = mutableMapOf<SaveKind, Set<String>>()
            var tooLarge = 0
            val emulatorFolders = appSettings.saveSyncEmulatorFolders.first()
            fun scan(treeUri: String, kindOf: (String) -> SaveKind, kinds: List<SaveKind>, under: EmulatorSaveFolder? = null) {
                val tree = treeUri.toUri()
                val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                    ?: throw IOException("Pick the folder again (no access)")
                val children = query(tree, rootId) ?: throw IOException("${folderName(tree)} cannot be read; pick it again")
                // An emulator's own folder shows up as one folder named after the emulator.
                if (under == null) kinds.forEach { topFolders[it] = topFolders[it].orEmpty() + children.filter { c -> c.isDir }.map { c -> c.name } }
                else kinds.forEach { topFolders[it] = topFolders[it].orEmpty() + under.label }
                fun walk(prefix: String, depth: Int, list: List<Child>) {
                    list.forEach { child ->
                        val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                        when {
                            child.isDir -> if (depth < 3 && !child.name.startsWith(".")) {
                                query(tree, child.documentId)?.let { walk(path, depth + 1, it) }
                            }
                            !SaveSyncPlanner.isSyncable(child.name) -> Unit
                            child.size > MAX_FILE_BYTES -> tooLarge++
                            else -> files += LocalSaveFile(kindOf(child.name), path, child.size, child.modified, under?.platforms.orEmpty()) to
                                DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId)
                        }
                    }
                }
                walk(under?.label.orEmpty(), if (under == null) 1 else 2, children)
            }
            if (savesUri.isNotBlank() && savesUri == statesUri) {
                scan(savesUri, SaveSyncPlanner::kindOf, SaveKind.entries)
            } else {
                if (savesUri.isNotBlank()) scan(savesUri, { SaveKind.SAVE }, listOf(SaveKind.SAVE))
                if (statesUri.isNotBlank()) scan(statesUri, { SaveKind.STATE }, listOf(SaveKind.STATE))
            }
            // A folder of the same name in the saves folder would mix with it; stop rather than guess
            // (skipping it would make its files look deleted).
            emulatorFolders.forEach { folder ->
                if (topFolders[SaveKind.SAVE].orEmpty().any { it.equals(folder.label, ignoreCase = true) })
                    throw IOException(context.getString(R.string.save_sync_emulator_clash, folder.label))
                scan(folder.uri, { SaveKind.SAVE }, listOf(SaveKind.SAVE), folder)
            }
            documents = files.associate { (file, uri) -> SaveSyncEngine.key(file) to uri }
            val rooted = buildSet {
                if (savesUri.isNotBlank()) add(if (savesUri == statesUri) SaveKind.entries else listOf(SaveKind.SAVE))
                if (statesUri.isNotBlank()) add(listOf(SaveKind.STATE))
            }.flatten().toSet()
            SaveStore.Listing(files.map { it.first }, topFolders, tooLarge, topFolders.keys - rooted)
        }

        override suspend fun read(file: LocalSaveFile): ByteArray = withContext(Dispatchers.IO) {
            val uri = documents[SaveSyncEngine.key(file)] ?: throw IOException("${file.name} is no longer on the device")
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size().toLong() + count > readLimit) {
                        if (readLimit == SaveHandoff.MAX_SAVE_BYTES) throw SaveHandoff.SaveTooLargeException()
                        throw IOException("Save file is too large")
                    }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: throw IOException("Could not read ${file.name}")
            // Some document providers report 0 when the size is unknown.
            if (file.size > 0L && bytes.size.toLong() != file.size) throw IOException("${file.name} changed while it was being read; try again")
            bytes
        }

        override suspend fun write(kind: SaveKind, path: String, bytes: ByteArray): LocalSaveFile = withContext(Dispatchers.IO) {
            // "DraStic (standalone)/Game.dsv" goes into DraStic's own folder when one is picked for it.
            val emulator = if (kind == SaveKind.SAVE) EmulatorSaveFolders.locate(path, appSettings.saveSyncEmulatorFolders.first()) else null
            val root = (if (emulator != null) StorageHelper.getDocumentFile(context, emulator.first.uri) else rootFor(kind))
                ?: throw IOException("No folder picked for ${kind.apiPath}")
            val inside = emulator?.second ?: path
            val written = StorageHelper.writeBytesSafely(context, root, inside.substringBeforeLast('/', ""), inside.substringAfterLast('/'), bytes)
            val file = LocalSaveFile(kind, path, written.length(), written.lastModified(), emulator?.first?.platforms.orEmpty())
            documents = documents + (SaveSyncEngine.key(file) to written.uri)
            file
        }

        override suspend fun delete(file: LocalSaveFile) = withContext(Dispatchers.IO) {
            val uri = documents[SaveSyncEngine.key(file)] ?: throw IOException("${file.name} is no longer on the device")
            if (!DocumentsContract.deleteDocument(context.contentResolver, uri)) throw IOException("Could not delete ${file.name}")
            documents = documents - SaveSyncEngine.key(file)
        }

        override suspend fun backup(file: LocalSaveFile) {
            keepSafetyCopy(file.kind, file.path, read(file))
        }

        private suspend fun rootFor(kind: SaveKind): DocumentFile? {
            val uri = when (kind) {
                SaveKind.SAVE -> settingsRepository.saveSyncSavesDir.first()
                SaveKind.STATE -> settingsRepository.saveSyncStatesDir.first()
            }
            return uri.takeIf { it.isNotBlank() }?.let { StorageHelper.getDocumentFile(context, it) }
        }

        private fun folderName(tree: Uri): String =
            runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':') }.getOrDefault(tree.toString())
    }

    private data class Child(val documentId: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    /** One provider query per folder; null when it cannot be read. */
    private fun query(tree: Uri, documentId: String): List<Child>? = runCatching {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    add(Child(
                        documentId = id,
                        name = name,
                        isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = if (c.isNull(3)) 0L else c.getLong(3),
                        modified = if (c.isNull(4)) 0L else c.getLong(4)
                    ))
                }
            }
        }
    }.getOrNull()

    // ---- Records ------------------------------------------------------------------------------

    /** Fixed field names (not Gson reflection), so minified builds read what debug builds wrote. */
    private fun loadRecords(): Map<String, SaveSyncRecord> = runCatching {
        if (!storeFile.exists()) return emptyMap()
        val root = JsonParser.parseString(storeFile.readText()).asJsonObject
        root.getAsJsonArray("records").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            runCatching {
                SaveSyncRecord(
                    kind = SaveKind.valueOf(o.get("kind").asString),
                    path = o.get("path").asString,
                    romId = o.get("romId").asInt,
                    remoteId = o.get("remoteId").asInt,
                    remoteUpdatedAt = o.get("remoteUpdatedAt").asString,
                    localSize = o.get("localSize").asLong,
                    localModified = o.get("localModified").asLong,
                    remoteSize = o.get("remoteSize")?.takeUnless { it.isJsonNull }?.asLong ?: -1,
                    remoteHash = o.get("remoteHash")?.takeUnless { it.isJsonNull }?.asString
                )
            }.getOrNull()
        }.associateBy { SaveSyncPlanner.key(it.kind, it.path) }
    }.getOrElse { e -> Log.w(TAG, "Unreadable sync records; starting over", e); emptyMap() }

    private fun saveRecords(records: Map<String, SaveSyncRecord>) {
        val array = JsonArray()
        records.values.forEach { r ->
            array.add(JsonObject().apply {
                addProperty("kind", r.kind.name)
                addProperty("path", r.path)
                addProperty("romId", r.romId)
                addProperty("remoteId", r.remoteId)
                addProperty("remoteUpdatedAt", r.remoteUpdatedAt)
                addProperty("localSize", r.localSize)
                addProperty("localModified", r.localModified)
                addProperty("remoteSize", r.remoteSize)
                r.remoteHash?.let { addProperty("remoteHash", it) }
            })
        }
        val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
        val bytes = JsonObject().apply { addProperty("version", 1); add("records", array) }.toString().toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(tmp).use { output -> output.write(bytes); output.flush(); output.fd.sync() }
            if (!tmp.readBytes().contentEquals(bytes) || !tmp.renameTo(storeFile)) throw IOException("Could not keep save sync records")
        } finally { tmp.delete() }
    }

    // ---- 5.0: cloud saves per game (used by CloudSavesService; additions only) ----------------

    /** Where the safety copies live: `save-backups/<yyyyMMdd-HHmmss>/<saves|states>/<path>` (kept 30 days). */
    val safetyCopiesDir: File get() = backupDir

    /** Whether a device folder is picked for [kind] (for saves, an emulator's own folder counts too). */
    suspend fun hasDeviceFolder(kind: SaveKind): Boolean = when (kind) {
        SaveKind.SAVE -> settingsRepository.saveSyncSavesDir.first().isNotBlank() || appSettings.saveSyncEmulatorFolders.first().isNotEmpty()
        SaveKind.STATE -> settingsRepository.saveSyncStatesDir.first().isNotBlank()
    }

    /** The device's saves and states as a sync lists them (nothing is transferred); null when no folder is picked. */
    suspend fun deviceListing(): SaveStore.Listing? = withContext(Dispatchers.IO) {
        if (!hasDeviceFolder(SaveKind.SAVE) && !hasDeviceFolder(SaveKind.STATE)) null else SafSaveStore().list()
    }

    /** What the last sync recorded per device file, by [SaveSyncPlanner.key] (a copy). */
    suspend fun syncRecords(): Map<String, SaveSyncRecord> = withContext(Dispatchers.IO) { loadRecords() }

    // ---- Local recovery / guided device handoff -----------------------------------------------

    private suspend fun configurationSignature(): String = SaveHandoff.sha256(listOf(
        rommClient.configuredBaseUrl(), settingsRepository.rommToken.first(),
        settingsRepository.saveSyncSavesDir.first(), settingsRepository.saveSyncStatesDir.first(),
        settingsRepository.rommPlatformMap.first().entries.sortedBy { it.key }.joinToString("") { "${it.key.length}:${it.key}:${it.value}" },
        com.cortinadev.dogmatix.util.EmulatorSaveFolders.toJson(appSettings.saveSyncEmulatorFolders.first())
    ).joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))

    private suspend fun guardGame(key: JournalKey, configuration: String? = null) = appSettings.withActiveProfile(key.profileId) {
        gameAccess.check(key)
        if (configuration != null) check(configurationSignature() == configuration) { "Save folders or server changed; check them again" }
    }

    /** Owner metadata is optional only for legacy/global copies; malformed metadata fails closed. */
    private fun copyOwner(copy: SafetyCopy): JournalKey? {
        val file = File(backupDir, copy.relative)
        require(file.canonicalPath.startsWith(backupDir.canonicalPath + File.separator))
        val parsed = CloudSaves.safetyCopy(copy.relative, file.length(), file.lastModified(), ZoneId.systemDefault())
        require(parsed != null && parsed.relative == copy.relative && parsed.kind == copy.kind && parsed.path == copy.path) { "Unexpected safety copy path" }
        val batch = copy.relative.substringBefore('/')
        val owner = File(File(backupDir, batch), "owner.json")
        if (!owner.exists()) return null
        require(owner.canonicalPath.startsWith(backupDir.canonicalPath + File.separator) && owner.isFile && owner.length() in 1..8192)
        val json = JsonParser.parseString(owner.readText(Charsets.UTF_8)).asJsonObject
        fun string(name: String) = json.get(name).let { require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
        return JournalKey(string("profile"), string("console"), string("file")).also {
            require(it.profileId.length <= 128 && it.consoleId.length in 1..128 && it.fileName.length in 1..1024)
        }
    }

    private suspend fun checkCopyAccess(profileId: String, copy: SafetyCopy) = appSettings.withActiveProfile(profileId) {
        val owner = copyOwner(copy)
        if (owner == null) check(profileId.isEmpty()) { "This older copy belongs to the unrestricted profile" }
        else { check(owner.profileId == profileId) { "This copy belongs to another profile" }; gameAccess.check(owner) }
    }

    suspend fun localSafetyCopies(profileId: String): List<SafetyCopy> = withContext(Dispatchers.IO) {
        appSettings.withActiveProfile(profileId) { Unit }
        if (!backupDir.isDirectory) return@withContext emptyList()
        val copies = backupDir.walkTopDown().maxDepth(8).filter { it.isFile }.take(5001).toList()
        require(copies.size <= 5000) { "Too many safety copies; review them before restoring" }
        copies.mapNotNull { file ->
            val relative = file.relativeToOrNull(backupDir)?.invariantSeparatorsPath ?: return@mapNotNull null
            CloudSaves.safetyCopy(relative, file.length(), file.lastModified(), ZoneId.systemDefault())
        }.filter { copy ->
            try { checkCopyAccess(profileId, copy); true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        }.sortedByDescending { it.takenAt }
    }

    suspend fun previewLocalSafetyRestore(profileId: String, copy: SafetyCopy): LocalSafetyRestorePreview = withContext(Dispatchers.IO) {
        checkCopyAccess(profileId, copy)
        check(hasDeviceFolder(copy.kind)) { "Choose the save folder again before restoring" }
        val bytes = VerifiedSafetyCopies.read(backupDir, copy.relative, MAX_FILE_BYTES)
        val store = SafSaveStore()
        val listing = store.list()
        require(listing.tooLarge == 0) { "A save is too large to verify safely" }
        val matches = listing.files.filter { it.kind == copy.kind && it.path.equals(copy.path, ignoreCase = true) }
        require(matches.size <= 1) { "Several save files match this path" }
        val current = matches.singleOrNull()?.let { store.read(it) }
        checkCopyAccess(profileId, copy)
        LocalSafetyRestorePreview(copy, profileId, SaveHandoff.sha256(bytes), current?.let(SaveHandoff::sha256), current?.size?.toLong(), configurationSignature())
    }

    suspend fun restoreLocalSafetyCopy(preview: LocalSafetyRestorePreview): CloudSaveResult {
        if (!lock.tryLock()) return CloudSaveResult.Busy
        try { return withContext(Dispatchers.IO) {
            try {
                val fresh = previewLocalSafetyRestore(preview.profileId, preview.copy)
                check(fresh == preview) { "The save or folder changed; check the restore again" }
                val bytes = VerifiedSafetyCopies.read(backupDir, preview.copy.relative, MAX_FILE_BYTES)
                appSettings.withActiveProfile(preview.profileId) {
                    checkCopyAccessUnlocked(preview.profileId, preview.copy)
                    check(configurationSignature() == preview.configuration) { "Save folder changed; check the restore again" }
                    val store = SafSaveStore()
                    val current = store.list().files.filter { it.kind == preview.copy.kind && it.path.equals(preview.copy.path, ignoreCase = true) }
                    require(current.size <= 1)
                    val old = current.singleOrNull()?.let { store.read(it) }
                    check(old?.let(SaveHandoff::sha256) == preview.currentSha256) { "Save changed; check the restore again" }
                    check(SaveHandoff.sha256(bytes) == preview.backupSha256)
                    currentCoroutineContext().ensureActive()
                    if (old != null) {
                        val owner = copyOwner(preview.copy)
                        if (owner == null) keepSafetyCopy(preview.copy.kind, current.single().path, old)
                        else keepGameSafetyCopy(owner, preview.copy.kind, current.single().path, old)
                    }
                    val written = store.write(preview.copy.kind, current.singleOrNull()?.path ?: preview.copy.path, bytes)
                    check(store.read(written).contentEquals(bytes)) { "Restored save could not be verified" }
                    // Reset the baseline; the next sync compares instead of trusting stale timestamps.
                    saveRecords(loadRecords().filterKeys { it != SaveSyncEngine.key(written) })
                    CloudSaveResult.Done(written.path)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { CloudSaveResult.Failed(if (e is VerifiedSafetyCopies.IntegrityException) context.getString(R.string.csave_error_integrity) else e.message ?: "Restore failed") }
        } } finally { lock.unlock() }
    }

    private suspend fun checkCopyAccessUnlocked(profileId: String, copy: SafetyCopy) {
        val owner = copyOwner(copy)
        if (owner == null) check(profileId.isEmpty()) else { check(owner.profileId == profileId); gameAccess.check(owner) }
    }

    private fun keepGameSafetyCopy(key: JournalKey, kind: SaveKind, path: String, bytes: ByteArray) {
        val target = VerifiedSafetyCopies.keep(backupDir, kind, path, bytes)
        val batch = File(backupDir, target.relativeTo(backupDir).invariantSeparatorsPath.substringBefore('/'))
        val owner = File(batch, "owner.json")
        val metadata = JsonObject().apply { addProperty("profile", key.profileId); addProperty("console", key.consoleId); addProperty("file", key.fileName) }.toString().toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(owner).use { it.write(metadata); it.flush(); it.fd.sync() }
            check(owner.readBytes().contentEquals(metadata))
            check(VerifiedSafetyCopies.read(backupDir, target.relativeTo(backupDir).invariantSeparatorsPath, MAX_FILE_BYTES).contentEquals(bytes))
        } catch (e: Exception) { batch.deleteRecursively(); throw e }
    }

    /** Fresh ordinary saves only. No emulator state is offered as portable between devices. */
    suspend fun previewHandoff(key: JournalKey, romId: Int): SaveHandoffPreview = withContext(Dispatchers.IO) {
        val configuration = configurationSignature()
        val endpoint = rommClient.saveEndpoint()
        guardGame(key, configuration)
        previewHandoffAt(key, romId, endpoint, configuration)
    }

    private suspend fun previewHandoffAt(key: JournalKey, romId: Int, endpoint: RommSaveEndpoint, configuration: String): SaveHandoffPreview {
        guardGame(key)
        check(isConfigured() && hasDeviceFolder(SaveKind.SAVE)) { "Set up RomM and an in-game save folder first" }
        val platform = settingsRepository.rommPlatformMap.first()[key.consoleId]
            ?: error("Map this console to RomM before transferring saves")
        val detail = JsonHttp.requireOk(JsonHttp.request("GET", "${endpoint.base}/api/roms/$romId", endpoint.auth, readTimeoutMs = 60_000))
        val identity = CloudSaves.romFileAndPlatform(detail.json) ?: error("Server game identity could not be verified")
        check(identity.second == platform && com.cortinadev.dogmatix.util.RommMarks.key(key.consoleId, identity.first) == com.cortinadev.dogmatix.util.RommMarks.key(key.consoleId, key.fileName)) {
            "Server game changed; refresh the RomM library before transferring"
        }
        val store = SafSaveStore(SaveHandoff.MAX_SAVE_BYTES)
        val listing = store.list()
        require(listing.tooLarge == 0) { "A save is too large to verify safely" }
        val records = loadRecords()
        val stem = CloudSaves.gameStem(key.fileName)
        val locals = listing.files.filter { local ->
            val record = records[SaveSyncEngine.key(local)]
            if (record != null) record.romId == romId
            else CloudSaves.belongsToGame(local.name, stem) && (local.platformHints.isEmpty() || ConsoleFolderAliases.normalize(key.consoleId) in local.platformHints)
        }
        val ordinary = locals.filter { it.kind == SaveKind.SAVE && SaveHandoff.isInGameSave(it.name) }
        ordinary.forEach { SaveHandoff.requireBoundedSave(it.size) }
        require(ordinary.map { it.path.lowercase() }.distinct().size == ordinary.size) { "Several save folders have the same path" }
        // A familiar stem alone cannot distinguish (for example) NES Tetris from Game Boy Tetris.
        // Require an established record or a unique platform-aware server match before an upload.
        ordinary.filter { records[SaveSyncEngine.key(it)] == null }.forEach { local ->
            val match = SaveSyncPlanner.matchRom(local, rommClient.searchRoms(SaveSyncPlanner.searchTerm(local.name), endpoint = endpoint))
            check(match is SaveSyncPlanner.RomMatch.Found && match.romId == romId) { "Save cannot be tied to this console safely; configure its emulator folder or synchronize it first" }
        }
        val remotes = SaveSyncPlanner.latestPerName(rommClient.saves(SaveKind.SAVE, endpoint).filter { it.romId == romId && SaveHandoff.isInGameSave(it.fileName) })
        remotes.forEach { SaveHandoff.requireBoundedSave(it.size) }
        require(ordinary.size + remotes.size <= SaveHandoff.MAX_FILES * 2) { "Too many saves for one game" }
        val localHashes = ordinary.associate { it.path to SaveHandoff.sha256(store.read(it)) }
        val remoteHashes = remotes.associate { it.id to SaveHandoff.sha256(handoffServerBytes(it, endpoint)) }
        guardGame(key, configuration)
        return SaveHandoffPreview(key, romId, configuration, System.currentTimeMillis(), SaveHandoff.plan(
            listing.copy(files = ordinary), remotes, records.values.filter { it.romId == romId }, localHashes, remoteHashes
        ), omittedStates = locals.size - ordinary.size)
    }

    /** Re-read both sides before approval is used, back up bytes, transfer and verify both sides again. */
    suspend fun transferHandoff(preview: SaveHandoffPreview): SaveHandoffPreview {
        check(lock.tryLock()) { "A save synchronization is already running" }
        try { return withContext(Dispatchers.IO) {
            guardGame(preview.key, preview.configuration)
            val endpoint = rommClient.saveEndpoint()
            guardGame(preview.key, preview.configuration)
            val fresh = previewHandoffAt(preview.key, preview.romId, endpoint, preview.configuration)
            SaveHandoff.requireFresh(preview, fresh, System.currentTimeMillis())
            val records = loadRecords().toMutableMap()
            for (row in fresh.files) {
                currentCoroutineContext().ensureActive()
                guardGame(preview.key, preview.configuration)
                val store = SafSaveStore(SaveHandoff.MAX_SAVE_BYTES)
                val local = store.list().files.singleOrNull { it.kind == SaveKind.SAVE && it.path == row.path }
                val bytes = local?.let { store.read(it) }
                check(bytes?.let(SaveHandoff::sha256) == row.localSha256) { "Device save changed during transfer; check it again" }
                val actualRemote = SaveSyncPlanner.latestPerName(rommClient.saves(SaveKind.SAVE, endpoint).filter { it.romId == preview.romId })
                    .singleOrNull { it.fileName.equals(row.remote?.fileName ?: local?.name ?: row.path.substringAfterLast('/'), ignoreCase = true) }
                check(actualRemote?.id == row.remote?.id) { "Server save changed during transfer; check it again" }
                val remote = actualRemote
                val serverBytes = remote?.let { handoffServerBytes(it, endpoint) }
                check(serverBytes?.let(SaveHandoff::sha256) == row.remoteSha256) { "Server save changed during transfer; check it again" }
                if (bytes != null) appSettings.withActiveProfile(preview.key.profileId) {
                    gameAccess.check(preview.key)
                    keepGameSafetyCopy(preview.key, SaveKind.SAVE, row.path, bytes)
                }
                if (serverBytes != null && row.direction == SaveHandoffDirection.UPLOAD) appSettings.withActiveProfile(preview.key.profileId) {
                    gameAccess.check(preview.key)
                    keepGameSafetyCopy(preview.key, SaveKind.SAVE, row.path, serverBytes)
                }
                when (row.direction) {
                    SaveHandoffDirection.UPLOAD -> appSettings.withActiveProfile(preview.key.profileId) {
                        // Only this final remote mutation holds the profile lock. Preview/downloads
                        // release it, so switching cancels a delayed action before it writes anything.
                        gameAccess.check(preview.key)
                        check(configurationSignature() == preview.configuration) { "Server or folders changed; check saves again" }
                        val stored = rommClient.uploadSave(SaveKind.SAVE, preview.romId, requireNotNull(local).name, SaveSyncPlanner.emulatorFor(local), requireNotNull(bytes), endpoint)
                            ?: throw IOException("Server did not confirm the save upload")
                        check(SaveHandoff.sha256(handoffServerBytes(stored, endpoint)) == row.localSha256) { "Uploaded save could not be verified" }
                        check(configurationSignature() == preview.configuration) { "Server settings changed during transfer; check it again" }
                        records[SaveSyncEngine.key(local)] = SaveSyncEngine.record(local, stored)
                    }
                    SaveHandoffDirection.DOWNLOAD -> appSettings.withActiveProfile(preview.key.profileId) {
                        gameAccess.check(preview.key)
                        check(configurationSignature() == preview.configuration)
                        val latestLocal = store.list().files.singleOrNull { it.kind == SaveKind.SAVE && it.path == row.path }
                        check(latestLocal?.let { SaveHandoff.sha256(store.read(it)) } == row.localSha256) { "Save changed during transfer" }
                        val written = store.write(SaveKind.SAVE, row.path, requireNotNull(serverBytes))
                        check(store.read(written).contentEquals(serverBytes))
                        records[SaveSyncEngine.key(written)] = SaveSyncEngine.record(written, requireNotNull(remote))
                    }
                    SaveHandoffDirection.IDENTICAL -> records[SaveSyncEngine.key(requireNotNull(local))] = SaveSyncEngine.record(local, requireNotNull(remote))
                    else -> error("Resolve save conflicts first")
                }
                appSettings.withActiveProfile(preview.key.profileId) { gameAccess.check(preview.key); saveRecords(records) }
            }
            val checked = previewHandoffAt(preview.key, preview.romId, endpoint, preview.configuration)
            check(checked.files.isNotEmpty() && checked.files.all { it.direction == SaveHandoffDirection.IDENTICAL }) { "Some saves still differ; check them again" }
            // Read back the backup for every current save, including files that were just downloaded.
            val store = SafSaveStore(SaveHandoff.MAX_SAVE_BYTES)
            val current = store.list().files
            checked.files.forEach { row ->
                val local = current.single { it.kind == SaveKind.SAVE && it.path == row.path }
                val bytes = store.read(local)
                check(SaveHandoff.sha256(bytes) == row.localSha256)
                appSettings.withActiveProfile(preview.key.profileId) { gameAccess.check(preview.key); keepGameSafetyCopy(preview.key, SaveKind.SAVE, row.path, bytes) }
            }
            guardGame(preview.key, preview.configuration)
            checked.copy(backupVerified = true)
        } } finally { lock.unlock() }
    }

    private suspend fun handoffServerBytes(remote: RemoteSaveFile, endpoint: RommSaveEndpoint): ByteArray {
        SaveHandoff.requireBoundedSave(remote.size)
        return try {
            rommClient.downloadSave(remote, SaveHandoff.MAX_SAVE_BYTES, endpoint).also { SaveHandoff.requireBoundedSave(it.size.toLong()) }
        } catch (e: IOException) {
            if (e.message?.contains("too large", ignoreCase = true) == true || e.message?.contains("exceed", ignoreCase = true) == true) throw SaveHandoff.SaveTooLargeException()
            throw e
        }
    }

    /**
     * The screenshot RetroArch keeps next to a save state (`Game.state1.png`): its document URI and
     * date; null when there is none or the folder cannot be read.
     */
    suspend fun deviceStateShot(local: LocalSaveFile): Pair<String, Long>? = withContext(Dispatchers.IO) {
        if (local.kind != SaveKind.STATE) return@withContext null
        runCatching {
            val treeUri = settingsRepository.saveSyncStatesDir.first()
            val root = if (treeUri.isBlank()) null else StorageHelper.getDocumentFile(context, treeUri)
            if (root == null) null else listOf("${local.path}.png", "${local.path.substringBeforeLast('.')}.png").distinct()
                .firstNotNullOfOrNull { candidate -> StorageHelper.findFile(root, candidate)?.takeIf { it.isFile } }
                ?.let { it.uri.toString() to it.lastModified() }
        }.getOrNull()
    }

    /**
     * "Restore this version": writes [version] (one of the game's server saves or states) into its
     * device folder. The device file it replaces is kept as a safety copy first, and so is the
     * server's current version when the device does not hold it already: nothing is lost, nothing is
     * deleted. The next sync then sends the restored version up as the current one.
     * [serverFiles] are the game's server entries; [gameStem] is the game's file name without extension.
     */
    suspend fun restoreServerVersion(version: CloudSaveEntry, serverFiles: List<CloudSaveEntry>, gameStem: String, profileKey: JournalKey? = null): CloudSaveResult =
        restoreInto(version.kind, serverFiles, restored = version, profileKey = profileKey, target = { listing, records ->
            CloudSaves.restoreTarget(version, gameStem, listing.files, records.values, listing.topFolders, listing.noRootFolder)
        }) { rommClient.downloadSave(version.toRemote(), MAX_FILE_BYTES) }

    /** Puts a safety copy back in its place; the device file there now becomes a safety copy itself first. */
    suspend fun restoreSafetyCopy(copy: SafetyCopy, serverFiles: List<CloudSaveEntry>): CloudSaveResult =
        restoreInto(copy.kind, serverFiles, restored = null, target = { _, _ -> RestoreTarget.Path(copy.path) }) {
            val file = File(backupDir, copy.relative)
            if (!file.isFile || !file.canonicalPath.startsWith(backupDir.canonicalPath + File.separator)) {
                throw IOException(context.getString(R.string.csave_error_gone, copy.name))
            }
            VerifiedSafetyCopies.read(backupDir, copy.relative, MAX_FILE_BYTES)
        }

    private suspend fun restoreInto(
        kind: SaveKind,
        serverFiles: List<CloudSaveEntry>,
        restored: CloudSaveEntry?,
        profileKey: JournalKey? = null,
        target: (SaveStore.Listing, Map<String, SaveSyncRecord>) -> RestoreTarget,
        bytesOf: suspend () -> ByteArray
    ): CloudSaveResult {
        if (!lock.tryLock()) return CloudSaveResult.Busy
        try {
            return withContext(Dispatchers.IO) {
                runCatching<CloudSaveResult> {
                    profileKey?.let { guardGame(it) }
                    val configuration = configurationSignature()
                    if (!hasDeviceFolder(kind)) return@runCatching CloudSaveResult.Failed(context.getString(R.string.csave_error_no_folder))
                    val store = SafSaveStore()
                    val listing = store.list()
                    val records = loadRecords().toMutableMap()
                    val path = when (val t = target(listing, records)) {
                        is RestoreTarget.Path -> t.path
                        RestoreTarget.Ambiguous -> return@runCatching CloudSaveResult.Failed(context.getString(R.string.csave_error_ambiguous))
                        RestoreTarget.NoFolder -> return@runCatching CloudSaveResult.Failed(context.getString(R.string.csave_error_no_folder))
                    }
                    val bytes = bytesOf()
                    val current = listing.files.firstOrNull { it.kind == kind && it.path.equals(path, ignoreCase = true) }
                    val targetPath = current?.path ?: path
                    val latest = CloudSaves.latest(serverFiles, kind, targetPath.substringAfterLast('/'))
                    val record = current?.let { records[SaveSyncEngine.key(it)] }
                    // 1. What the device has there now becomes a safety copy (if that fails, nothing is touched).
                    val original = current?.let { store.read(it) }
                    if (original != null) {
                        if (profileKey == null) keepSafetyCopy(kind, requireNotNull(current).path, original)
                        else appSettings.withActiveProfile(profileKey.profileId) { gameAccess.check(profileKey); keepGameSafetyCopy(profileKey, kind, requireNotNull(current).path, original) }
                    }
                    // 2. So does the server's current version, unless the device held it or it is what comes back.
                    val restoringLatest = restored != null && latest != null && restored.kind == latest.kind && restored.id == latest.id
                    if (latest != null && !restoringLatest && !CloudSaves.deviceHolds(latest, record, current)) {
                        val remoteBytes = rommClient.downloadSave(latest.toRemote(), MAX_FILE_BYTES)
                        if (profileKey == null) keepSafetyCopy(kind, targetPath, remoteBytes)
                        else appSettings.withActiveProfile(profileKey.profileId) { gameAccess.check(profileKey); keepGameSafetyCopy(profileKey, kind, targetPath, remoteBytes) }
                    }
                    // 3. In place; the record tells the next sync what to do with it.
                    val commit: suspend () -> CloudSaveResult = {
                        check(configurationSignature() == configuration) { "Save folders or server changed; check the restore again" }
                        val actual = store.list().files.filter { it.kind == kind && it.path.equals(targetPath, ignoreCase = true) }
                        require(actual.size <= 1)
                        check(actual.singleOrNull()?.let { SaveHandoff.sha256(store.read(it)) } == original?.let(SaveHandoff::sha256)) { "Save changed; check the restore again" }
                        val written = store.write(kind, targetPath, bytes)
                        val key = SaveSyncEngine.key(written)
                        val next = CloudSaves.recordAfterRestore(written, restored, latest, records[key])
                        if (next != null) records[key] = next else records.remove(key)
                        saveRecords(records)
                        dropConflict(kind, written.path)
                        CloudSaveResult.Done(written.path)
                    }
                    if (profileKey == null) commit() else appSettings.withActiveProfile(profileKey.profileId) { gameAccess.check(profileKey); commit() }
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Restore failed", e)
                    CloudSaveResult.Failed(if (e is VerifiedSafetyCopies.IntegrityException) context.getString(R.string.csave_error_integrity) else e.message ?: e.javaClass.simpleName)
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * "Upload now": sends the device file [path] of [kind] to RomM as a save of ROM [romId], the way a
     * sync would. When the server's copy of that name is not what the last sync saw (another device
     * saved since), it is kept as a safety copy first, so nothing is lost.
     */
    suspend fun uploadDeviceSave(kind: SaveKind, path: String, romId: Int, serverFiles: List<CloudSaveEntry>, profileKey: JournalKey? = null): CloudSaveResult {
        if (!lock.tryLock()) return CloudSaveResult.Busy
        try {
            return withContext(Dispatchers.IO) {
                runCatching<CloudSaveResult> {
                    profileKey?.let { guardGame(it) }
                    val configuration = configurationSignature()
                    val store = SafSaveStore()
                    val local = store.list().files.firstOrNull { it.kind == kind && it.path.equals(path, ignoreCase = true) }
                        ?: return@runCatching CloudSaveResult.Failed(context.getString(R.string.csave_error_gone, path.substringAfterLast('/')))
                    val records = loadRecords().toMutableMap()
                    val record = records[SaveSyncEngine.key(local)]
                    val paired = record?.let { r -> serverFiles.firstOrNull { it.kind == kind && it.id == r.remoteId && it.syncable } }
                    val latest = paired ?: CloudSaves.latest(serverFiles, kind, local.name, romId)
                    if (latest != null && !CloudSaves.serverUnchangedSinceSync(latest, record)) {
                        val remoteBytes = rommClient.downloadSave(latest.toRemote(), MAX_FILE_BYTES)
                        if (profileKey == null) keepSafetyCopy(kind, local.path, remoteBytes)
                        else appSettings.withActiveProfile(profileKey.profileId) { gameAccess.check(profileKey); keepGameSafetyCopy(profileKey, kind, local.path, remoteBytes) }
                    }
                    val bytes = store.read(local)
                    profileKey?.let { guardGame(it, configuration) }
                    val upload: suspend () -> CloudSaveResult = {
                        check(configurationSignature() == configuration) { "Server or save folders changed" }
                        val stored = rommClient.uploadSave(kind, romId, local.name, SaveSyncPlanner.emulatorFor(local), bytes)
                            ?: rommClient.saves(kind).filter { it.romId == romId && it.fileName.equals(local.name, ignoreCase = true) }
                                .maxByOrNull { SaveSyncPlanner.epochMillis(it.updatedAt) ?: Long.MIN_VALUE }
                            ?: throw IOException("Server did not confirm the save upload")
                        records[SaveSyncEngine.key(local)] = SaveSyncEngine.record(local, stored)
                        saveRecords(records)
                        dropConflict(kind, local.path)
                        CloudSaveResult.Done(local.name)
                    }
                    if (profileKey == null) upload() else appSettings.withActiveProfile(profileKey.profileId) { gameAccess.check(profileKey); upload() }
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Upload of $path failed", e)
                    CloudSaveResult.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** A conflict the user settled from the game's view leaves the Save sync screen's list. */
    private fun dropConflict(kind: SaveKind, path: String) {
        _state.update { s -> s.copy(conflicts = s.conflicts.filterNot { it.local.kind == kind && it.local.path.equals(path, ignoreCase = true) }) }
    }

    /**
     * Keeps [bytes] as a safety copy of the device file [path], in the layout the sync's own copies use.
     * Throws when it cannot (the caller then changes nothing).
     */
    private fun keepSafetyCopy(kind: SaveKind, path: String, bytes: ByteArray) {
        VerifiedSafetyCopies.keep(backupDir, kind, path, bytes)
    }
}
