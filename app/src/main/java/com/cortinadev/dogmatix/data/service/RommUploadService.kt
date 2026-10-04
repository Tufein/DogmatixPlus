package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.RommPlatformMapper
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.RommUploadPlan
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.UploadSession
import com.cortinadev.dogmatix.util.UploadSessions
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommUploadService"
private const val CHUNK_SIZE = 8 * 1024 * 1024
private const val ATTEMPTS = 2

enum class UploadStatus { UPLOADING, DONE, FAILED }

data class UploadState(val status: UploadStatus, val progress: Float = 0f, val message: String = "")

/**
 * Pushes finished downloads to the RomM server (Settings → RomM). Observes the downloads list
 * the way [LibraryIndexService] does, so [DownloadService] needs no RomM knowledge; the state
 * lives here and the Downloads screen shows it next to each row.
 */
@Singleton
class RommUploadService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val downloadService: DownloadService,
    private val downloadableFileDao: DownloadableFileDao,
    private val downloadFileManager: DownloadFileManager,
    private val rommClient: RommClient,
    private val rommLibraryService: RommLibraryService,
    private val historyDao: DownloadHistoryDao,
    private val libraryIndexService: LibraryIndexService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()
    private val sessionFile = File(context.filesDir, "romm_upload_sessions.json")
    /** Server-side upload sessions that were cut short; continued instead of started over. Guarded by [uploadLock]. */
    private val sessions = LinkedHashMap<String, UploadSession>()

    private val _uploads = MutableStateFlow<Map<String, UploadState>>(emptyMap())
    /** Upload state per download file name; absent = nothing to show. */
    val uploads: StateFlow<Map<String, UploadState>> = _uploads.asStateFlow()

    init {
        loadSessions()
        // An upload cut short by a dying process is picked up again once the downloads list is back.
        scope.launch {
            downloadService.downloads.first { it.isNotEmpty() || sessions.isEmpty() }
            delay(3_000L)
            if (settingsRepository.rommAutoUpload.first()) {
                val pending = uploadLock.withLock { sessions.values.map { it.downloadFileName }.distinct() }
                pending.forEach { launch { enqueue(it) } }
            }
        }
        // Fires once per download, only when the file is really in place (see DownloadService.finished).
        scope.launch {
            downloadService.finished.collect { fileName ->
                if (settingsRepository.rommAutoUpload.first()) launch { enqueue(fileName) }
            }
        }
    }

    fun hasActive(): Boolean = _uploads.value.values.any { it.status == UploadStatus.UPLOADING }

    fun retry(fileName: String) {
        scope.launch { enqueue(fileName) }
    }

    /**
     * Sends the finished downloads the server does not have yet (see [RommUploadPlan]); this is for
     * games downloaded before auto-upload was on. Returns how many uploads were started, or null when
     * the server's game list is not known (it needs *Mark games already in RomM*).
     */
    suspend fun uploadMissing(): Int? {
        if (!settingsRepository.rommMarkGames.first()) return null
        val serverKeys = rommLibraryService.keys.value
        if (serverKeys.isEmpty()) return null
        val mapped = settingsRepository.rommPlatformMap.first().keys
        val base = rommClient.configuredBaseUrl()
        val owned = libraryIndexService.ownedKeys.value
        val candidates = historyDao.getAll()
            .filter { it.status == DownloadStatus.COMPLETED.name }
            .map { RommUploadPlan.Candidate(it.fileName, it.consoleId, it.downloadUrl) }
        val names = RommUploadPlan.missing(candidates, serverKeys, mapped, base) { LibraryKeys.isOwned(it.consoleId, it.fileName, owned) }
        names.forEach { name -> scope.launch { enqueue(name) } }
        return names.size
    }

    private suspend fun enqueue(fileName: String) {
        val file = downloadService.entityFor(fileName) ?: downloadableFileDao.getFileByFileName(fileName) ?: return
        if (RommSource.isDownloadFrom(rommClient.configuredBaseUrl(), file.downloadUrl)) return   // it came from RomM
        val platformId = settingsRepository.rommPlatformMap.first()[file.consoleId]
        if (platformId == null) {
            Log.i(TAG, "No RomM platform mapped for ${file.consoleId}; skipping ${file.fileName}")
            return
        }
        val names = downloadService.uploadCandidates(fileName)
        _uploads.update { it + (fileName to UploadState(UploadStatus.UPLOADING)) }
        uploadLock.withLock {
            val result = runCatching { uploadAll(file, names, platformId) }
            _uploads.update {
                it + (fileName to result.fold(
                    onSuccess = {
                        names.forEach { rommLibraryService.markUploaded(file.consoleId, it) }
                        UploadState(UploadStatus.DONE, 1f)
                    },
                    onFailure = { e -> Log.w(TAG, "RomM upload failed for $fileName: ${e.message}"); UploadState(UploadStatus.FAILED, message = e.message.orEmpty()) }
                ))
            }
        }
    }

    private suspend fun uploadAll(file: DownloadableFileEntity, names: List<String>, platformId: Int) {
        val dirUri = downloadFileManager.getDownloadDirectoryUri(file)
        if (dirUri == Uri.EMPTY) throw RommException("Download directory not accessible")
        val directory = StorageHelper.createDirectory(context, dirUri.toString(), downloadFileManager.getSubPath(file))
            ?: throw RommException("Could not open the download folder")
        val docs = names.mapNotNull { directory.findFile(it) }.filter { it.isFile }
        if (docs.isEmpty()) throw RommException("File not found on disk: ${names.joinToString()}")

        val totalBytes = docs.sumOf { it.length() }
        var sent = 0L
        docs.forEach { doc ->
            val name = doc.name ?: return@forEach
            var lastError: Throwable? = null
            for (attempt in 0 until ATTEMPTS) {
                try {
                    if (attempt > 0) delay(3_000L)
                    uploadOne(doc.uri, name, doc.length(), platformId, file.fileName) { done ->
                        _uploads.update { it + (file.fileName to UploadState(UploadStatus.UPLOADING, (sent + done).toFloat() / totalBytes.coerceAtLeast(1))) }
                    }
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    // "already exists" means a previous attempt did land: nothing to redo.
                    if (e is JsonHttp.HttpException && e.code == 400 && e.body?.contains("already exists") == true) { lastError = null; break }
                }
            }
            lastError?.let { throw it }
            sent += doc.length()
        }
    }

    /**
     * Sends one file in chunks. A cut-short upload keeps its server session (and is written to
     * disk), so the next try continues at the first chunk the server has not got instead of
     * sending everything again; if the server no longer knows the session it starts over.
     */
    private suspend fun uploadOne(uri: Uri, name: String, size: Long, platformId: Int, downloadFileName: String, onProgress: (Long) -> Unit) {
        val chunks = RommPlatformMapper.chunkCount(size, CHUNK_SIZE)
        // Runs inside the upload lock (see enqueue), so the session list is not touched concurrently.
        var resumed = UploadSessions.resumable(sessions.values, platformId, name, size, chunks, System.currentTimeMillis())
        while (true) {
            val session = resumed ?: UploadSession(
                downloadFileName, platformId, name, size, chunks,
                rommClient.uploadStart(platformId, name, size, chunks), 0, System.currentTimeMillis()
            ).also { remember(it) }
            try {
                sendChunks(uri, name, session, chunks, onProgress)
                rommClient.uploadComplete(session.uploadId)
                forget(session)
                Log.i(TAG, "Uploaded $name to RomM platform $platformId" + if (resumed != null) " (resumed at chunk ${session.nextChunk})" else "")
                return
            } catch (e: Exception) {
                val code = (e as? JsonHttp.HttpException)?.code
                if (resumed != null && !UploadSessions.isTransient(code)) {
                    // The server dropped the half-finished upload: forget it and send the file afresh.
                    Log.i(TAG, "RomM no longer has the upload of $name (HTTP $code); starting over")
                    forget(session)
                    resumed = null
                    continue
                }
                if (UploadSessions.isTransient(code)) {
                    Log.i(TAG, "Upload of $name interrupted; it can continue at the next try")
                } else {
                    forget(session)
                    rommClient.uploadCancel(session.uploadId)
                }
                throw e
            }
        }
    }

    private suspend fun sendChunks(uri: Uri, name: String, session: UploadSession, chunks: Int, onProgress: (Long) -> Unit) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            var skip = UploadSessions.offsetOf(session.nextChunk, CHUNK_SIZE)
            while (skip > 0) {
                val skipped = input.skip(skip)
                if (skipped <= 0) { if (input.read() < 0) break else skip-- } else skip -= skipped
            }
            val buffer = ByteArray(CHUNK_SIZE)
            var index = session.nextChunk
            var done = UploadSessions.offsetOf(session.nextChunk, CHUNK_SIZE)
            while (index < chunks) {
                var filled = 0
                while (filled < buffer.size) {
                    val n = input.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                rommClient.uploadChunk(session.uploadId, index, buffer, filled)
                done += filled
                onProgress(done)
                index++
                remember(session.copy(nextChunk = index.coerceAtMost(chunks - 1), updatedAt = System.currentTimeMillis()).takeIf { index < chunks })
                if (filled < buffer.size) break
            }
        } ?: throw RommException("Could not read $name")
    }

    private fun remember(session: UploadSession?) {
        session ?: return
        sessions[session.key] = session
        saveSessions()
    }

    private fun forget(session: UploadSession) {
        sessions.remove(session.key)
        saveSessions()
    }

    private fun loadSessions() {
        runCatching {
            if (!sessionFile.exists()) return@runCatching
            // A server drops half-finished uploads after a while: older sessions are of no use.
            val now = System.currentTimeMillis()
            UploadSessions.decode(sessionFile.readText()).filter { now - it.updatedAt <= UploadSessions.MAX_AGE_MS }.forEach { sessions[it.key] = it }
        }
    }

    private fun saveSessions() {
        runCatching {
            if (sessions.isEmpty()) { sessionFile.delete(); return }
            val tmp = File(sessionFile.parentFile, sessionFile.name + ".tmp")
            tmp.writeText(UploadSessions.encode(sessions.values))
            if (!tmp.renameTo(sessionFile)) { sessionFile.delete(); tmp.renameTo(sessionFile) }
        }
    }
}
