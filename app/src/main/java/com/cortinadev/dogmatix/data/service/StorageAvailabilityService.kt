package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.provider.DocumentsContract
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class StorageLocation(val uri: String, val key: String, val label: String,
    val downloadRoot: Boolean = false, val consoleIds: Set<String> = emptySet(), val sharedRoot: Boolean = false)
data class StorageLocationState(val location: StorageLocation, val status: StorageAccessStatus)

/** Reads exact selected folders. Reconnecting never starts transfers or restores files by itself. */
@Singleton
class StorageAvailabilityService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val appSettings: AppSettings
) {
    private val lock = Mutex()
    private val preferences = context.getSharedPreferences("storage_availability", Context.MODE_PRIVATE)
    private val _states = MutableStateFlow<List<StorageLocationState>>(emptyList())
    val states = _states.asStateFlow()

    suspend fun configuredLocations(): List<StorageLocation> {
        val restrictions = Profiles.restrictionsOf(Profiles.fromJson(appSettings.profiles.first()), appSettings.activeProfile.first())
        val locations = ArrayList<StorageLocation>()
        fun add(uri: String, label: String, download: Boolean = false, consoles: Set<String> = emptySet(), shared: Boolean = false) {
            if (uri.isBlank()) return
            locations += StorageLocation(uri, key(uri), label, download, consoles, shared)
        }
        add(settings.downloadDirectory.first(), context.getString(R.string.files_root_downloads), download = true, shared = true)
        settings.consoleDownloadDirectories.first().filterKeys { it !in restrictions.hiddenConsoles }.forEach { (console, uri) ->
            add(uri, ConsoleFormatter.getConsoleDisplayName(console), download = true, consoles = setOf(console))
        }
        add(settings.saveSyncSavesDir.first(), context.getString(R.string.save_sync_saves_folder), shared = true)
        add(settings.saveSyncStatesDir.first(), context.getString(R.string.save_sync_states_folder), shared = true)
        appSettings.saveSyncEmulatorFolders.first().forEach { add(it.uri, it.label, shared = true) }
        add(settings.esdeDirectory.first(), "ES-DE", shared = true)
        add(settings.iisuDirectory.first(), "iiSU", shared = true)
        return locations.groupBy { it.key }.values.map { group -> group.first().copy(
            label = group.map { it.label }.distinct().joinToString(" · "), downloadRoot = group.any { it.downloadRoot },
            consoleIds = group.flatMap { it.consoleIds }.toSet(), sharedRoot = group.any { it.sharedRoot }) }
    }

    suspend fun recheck(): List<StorageLocationState> = lock.withLock { withContext(Dispatchers.IO) {
        val checked = configuredLocations().map { location ->
            currentCoroutineContext().ensureActive()
            val old = _states.value.firstOrNull { it.location.key == location.key }?.status ?: stored(location.key)
            StorageLocationState(location, StorageAvailability.transition(old, probe(context, location.uri)))
        }
        checked.forEach { preferences.edit().putString(receiptKey(it.location.key), it.status.name).apply() }
        _states.value = checked
        checked
    } }

    /** Called immediately before an explicit resume: flags alone cannot prove that a provider writes. */
    suspend fun verifyWritable(rootUri: String): Boolean = lock.withLock { withContext(Dispatchers.IO) {
        val location = configuredLocations().firstOrNull { it.key == key(rootUri) } ?: return@withContext false
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        var status = probe(context, location.uri)
        if (StorageAvailability.writable(status)) {
            val folder = StorageHelper.getDocumentFile(context, location.uri)
            var ownProbe: androidx.documentfile.provider.DocumentFile? = null
            try {
                checkNotNull(folder)
                val name = ".dogmatix-probe-${UUID.randomUUID()}.tmp"
                val dir = DiskScanner.rootOf(location.uri) ?: error("Storage unavailable")
                val before = DiskScanner.listOrNull(context, dir, true) ?: error("Storage unavailable")
                check(before.none { it.name == name })
                coroutine.ensureActive()
                val created = folder.createFile("application/octet-stream", name) ?: error("Storage cannot create a file")
                // A broken provider must not redirect the write or cleanup to an existing file.
                check(before.none { it.uri == created.uri }) { "Storage returned an existing file" }
                ownProbe = created
                check(ownProbe.name == name)
                val bytes = UUID.randomUUID().toString().toByteArray(Charsets.US_ASCII)
                context.contentResolver.openOutputStream(ownProbe.uri, "wt")?.use { it.write(bytes) } ?: error("Storage cannot write")
                coroutine.ensureActive()
                val read = context.contentResolver.openInputStream(ownProbe.uri)?.use { BoundedStreams.read(it, bytes.size + 1) }
                    ?: error("Storage cannot read")
                check(read.contentEquals(bytes))
                coroutine.ensureActive()
                check(ownProbe.delete())
                ownProbe = null
                status = StorageAccessStatus.AVAILABLE
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { status = StorageAccessStatus.ACCESS_LOST }
            catch (_: Exception) { status = StorageAccessStatus.UNAVAILABLE }
            finally { ownProbe?.let { runCatching { it.delete() } } }
        }
        val old = _states.value.firstOrNull { it.location.key == location.key }?.status ?: stored(location.key)
        val state = StorageLocationState(location, StorageAvailability.transition(old, status))
        preferences.edit().putString(receiptKey(location.key), state.status.name).apply()
        _states.update { oldStates -> oldStates.filterNot { it.location.key == location.key } + state }
        StorageAvailability.writable(state.status)
    } }

    private fun stored(key: String) = runCatching { StorageAccessStatus.valueOf(preferences.getString(receiptKey(key), "")!!) }.getOrNull()
    private fun receiptKey(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        fun key(uri: String): String = DiskScanner.rootOf(uri)?.let { dir ->
            // Provider document ids and real paths may be case-sensitive. Unlike a display/index
            // key, an access/hold identity must never conflate separately selected folders.
            DiskScanner.canonicalPath(dir.treeUri.authority, dir.documentId) ?: "${dir.treeUri.authority}|${dir.documentId}"
        } ?: uri

        /** Query the selected document first: an absent child must never fall back to its granted parent. */
        fun probe(context: Context, uri: String): StorageAccessStatus {
            val dir = DiskScanner.rootOf(uri) ?: return StorageAccessStatus.UNAVAILABLE
            return try {
                val metadata = context.contentResolver.query(DiskScanner.uriOf(dir), arrayOf(
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
                    ?: return StorageAccessStatus.UNAVAILABLE
                val writable = metadata.use { cursor ->
                    if (cursor.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true) return StorageAccessStatus.UNAVAILABLE
                    if (!cursor.moveToFirst()) return StorageAccessStatus.MISSING
                    if (cursor.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR) return StorageAccessStatus.UNAVAILABLE
                    cursor.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
                }
                // The frequent availability check only opens the exact child cursor and validates
                // its status/first row. It never materializes a large ROM folder on each tick.
                val children = context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(dir.treeUri, dir.documentId),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?: return StorageAccessStatus.UNAVAILABLE
                val listed = children.use { cursor ->
                    cursor.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) != true &&
                        (!cursor.moveToFirst() || (!cursor.isNull(0) && !cursor.isNull(1)))
                }
                if (!listed) StorageAccessStatus.UNAVAILABLE
                else if (!writable) StorageAccessStatus.READ_ONLY else StorageAccessStatus.AVAILABLE
            } catch (_: SecurityException) { StorageAccessStatus.ACCESS_LOST }
            catch (_: Exception) { StorageAccessStatus.UNAVAILABLE }
        }
    }
}
