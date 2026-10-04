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
import com.cortinadev.dogmatix.util.EmulatorSaveFolder
import com.cortinadev.dogmatix.util.EmulatorSaveFolders
import com.cortinadev.dogmatix.util.LocalSaveFile
import com.cortinadev.dogmatix.util.RemoteSaveFile
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val appSettings: AppSettings
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
                    _state.update { it.copy(last = result, conflicts = conflicts) }
                    result
                }.onFailure { e ->
                    Log.w(TAG, "Save sync failed", e)
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
    private inner class SafSaveStore : SaveStore {
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
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Could not read ${file.name}")
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
            runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                val target = File(backupDir, "$stamp/${file.kind.apiPath}/${file.path}")
                target.parentFile?.mkdirs()
                target.writeBytes(read(file))
            }.onFailure { Log.w(TAG, "Could not back up ${file.path}", it) }
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
        tmp.writeText(JsonObject().apply { addProperty("version", 1); add("records", array) }.toString())
        if (!tmp.renameTo(storeFile)) { storeFile.delete(); tmp.renameTo(storeFile) }
    }
}
