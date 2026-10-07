package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.DownloadFailure
import com.cortinadev.dogmatix.data.model.DownloadFailureCategory
import com.cortinadev.dogmatix.util.DownloadFailures
import com.cortinadev.dogmatix.util.StorageAccessException
import com.cortinadev.dogmatix.util.TorrentProgress
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.FileParsingUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentFlags
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts a selective torrent file download:
 * 1. Gets the handle from [TorrentHandleRegistry] (instant cache hit after indexing).
 * 2. Selects [DownloadableFileEntity.torrentFileIndex] while keeping selected siblings active.
 * 3. Resumes the torrent and hands off to [TorrentProgressBridge] for updates.
 */
@Singleton
class TorrentDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: TorrentHandleRegistry,
    private val progressBridge: TorrentProgressBridge,
    private val downloadFileManager: DownloadFileManager,
    private val progressTracker: DownloadProgressTracker
) {
    data class TorrentFileInfo(val relativePath: String, val expectedSize: Long)

    // Caches file path/size at download-start time so moveTorrentFile can proceed even if the
    // handle is later invalidated (e.g. session stopped during app shutdown before copy finishes).
    private val fileInfoCache = ConcurrentHashMap<String, TorrentFileInfo>()
    // A queued sibling can start while another finishes. Keep same-torrent priority/tracking/
    // release operations together; copying/extraction runs outside these locks.
    private val downloadMutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(magnet: String): Mutex =
        downloadMutexes.getOrPut(FileParsingUtils.optimizeMagnetUri(magnet)) { Mutex() }

    fun getFileInfo(fileName: String): TorrentFileInfo? = fileInfoCache[fileName]

    suspend fun startDownload(file: DownloadableFileEntity) = withContext(Dispatchers.IO) {
        val magnet = file.torrentMagnet
            ?: throw IllegalArgumentException("No torrentMagnet on ${file.fileName}")
        mutexFor(magnet).withLock { startDownloadLocked(file, magnet) }
    }

    private suspend fun startDownloadLocked(file: DownloadableFileEntity, magnet: String) {
        val fileIndex = file.torrentFileIndex
            ?: throw IllegalArgumentException("No torrentFileIndex on ${file.fileName}")

        val handle = try {
            registry.getOrFetch(magnet)
        } catch (e: TorrentMetadataTimeoutException) {
            Log.e(TAG, "Metadata timeout: ${e.message}")
            progressTracker.updateDownloadStatus(file.fileName, DownloadStatus.FAILED, allowedFrom = TorrentProgress.NETWORK_STATUSES, failure = DownloadFailures.classify(e))
            return
        }

        val torrentInfo = handle.torrentFile() ?: run {
            Log.e(TAG, "No TorrentInfo for ${file.fileName}")
            progressTracker.updateDownloadStatus(file.fileName, DownloadStatus.FAILED, allowedFrom = TorrentProgress.NETWORK_STATUSES, failure = DownloadFailure(DownloadFailureCategory.TORRENT))
            return
        }

        if (fileIndex < 0 || fileIndex >= torrentInfo.numFiles()) {
            Log.e(TAG, "fileIndex $fileIndex out of range")
            progressTracker.updateDownloadStatus(file.fileName, DownloadStatus.FAILED, allowedFrom = TorrentProgress.NETWORK_STATUSES, failure = DownloadFailure(DownloadFailureCategory.TORRENT))
            return
        }

        val downloadDirUri = downloadFileManager.getDownloadDirectoryUri(file)
        val subPath = downloadFileManager.getSubPath(file)

        val finalDir = StorageHelper.createDirectory(context, downloadDirUri.toString(), subPath)
        if (finalDir == null) {
            Log.e(TAG, "Could not create/access download directory")
            throw StorageAccessException()
        }

        // Cache file path/size before tracking so moveTorrentFile can find the file
        // even if the handle is gone at move time.
        val relativePath = torrentInfo.files().filePath(fileIndex)
        val expectedSize = torrentInfo.files().fileSize(fileIndex)
        fileInfoCache[file.fileName] = TorrentFileInfo(relativePath, expectedSize)

        progressBridge.trackDownload(file.fileName, fileIndex, handle)

        // The registry initialized every file to IGNORE once. Select only this file: building
        // an all-files priority vector for every bulk entry repeatedly copies huge collections
        // over JNI and can overwrite a sibling's priorities during concurrent start/finish.
        handle.filePriority(fileIndex, Priority.DEFAULT)
        // The registry fetched metadata in upload mode (no piece requests); lift it now that
        // only the wanted files have a priority.
        handle.unsetFlags(TorrentFlags.UPLOAD_MODE)

        // Only move storage if the torrent isn't already downloading to our cache directory.
        // Calling moveStorage() is asynchronous — redundant calls on the same handle fire
        // unnecessary STORAGE_MOVED_ALERT events and can briefly pause in-flight downloads.
        val torrentDataDir = File(context.cacheDir, "torrent_data").apply { mkdirs() }
        val currentSavePath = try { handle.status().swig().save_path } catch (_: Exception) { null }
        if (currentSavePath != torrentDataDir.absolutePath) {
            handle.moveStorage(torrentDataDir.absolutePath)
        }

        handle.resume()

        Log.i(TAG, "Torrent resumed for ${file.fileName} — TorrentProgressBridge takes over")
    }

    suspend fun finishDownload(file: DownloadableFileEntity) = withContext(Dispatchers.IO) {
        val magnet = file.torrentMagnet ?: return@withContext
        mutexFor(magnet).withLock { finishDownloadLocked(file, magnet) }
    }

    private fun finishDownloadLocked(file: DownloadableFileEntity, magnet: String) {
        val fileIndex = file.torrentFileIndex ?: return

        fileInfoCache.remove(file.fileName)
        val handle = registry.getCachedHandle(magnet)
        progressBridge.untrackDownload(file.fileName, fileIndex)

        val stillTracked = if (handle != null) progressBridge.countTrackedForHandle(handle) else 0
        if (stillTracked == 0) {
            registry.releaseHandle(magnet)
            Log.i(TAG, "Handle released after successful move for ${file.fileName}")
        } else {
            Log.i(TAG, "Handle kept alive after move for ${file.fileName} ($stillTracked files still active)")
        }
    }

    /**
     * Parks [file]: its priority drops to IGNORE and the bridge stops tracking it, but the
     * handle and the partial data in the cache stay, so resuming (the retry flow) continues
     * from the pieces already on disk. With nothing else active the torrent is paused.
     */
    suspend fun pauseDownload(file: DownloadableFileEntity) = withContext(Dispatchers.IO) {
        val magnet = file.torrentMagnet ?: return@withContext
        mutexFor(magnet).withLock { pauseDownloadLocked(file, magnet) }
    }

    private fun pauseDownloadLocked(file: DownloadableFileEntity, magnet: String) {
        val fileIndex = file.torrentFileIndex ?: return
        progressBridge.untrackDownload(file.fileName, fileIndex)
        val handle = registry.getCachedHandle(magnet) ?: return
        handle.filePriority(fileIndex, Priority.IGNORE)
        if (progressBridge.countTrackedForHandle(handle) == 0) {
            try { handle.pause() } catch (e: Exception) { Log.w(TAG, "pause: ${e.message}") }
        }
        Log.i(TAG, "Paused ${file.fileName} (cache kept)")
    }

    suspend fun cancelDownload(file: DownloadableFileEntity, reportStatus: Boolean = true) = withContext(Dispatchers.IO) {
        val magnet = file.torrentMagnet ?: return@withContext
        mutexFor(magnet).withLock { cancelDownloadLocked(file, magnet, reportStatus) }
    }

    private fun cancelDownloadLocked(file: DownloadableFileEntity, magnet: String, reportStatus: Boolean) {
        val fileIndex = file.torrentFileIndex ?: return

        fileInfoCache.remove(file.fileName)
        val handle = registry.getCachedHandle(magnet)
        progressBridge.untrackDownload(file.fileName, fileIndex)

        val remainingForThisTorrent = if (handle != null) progressBridge.countTrackedForHandle(handle) else 0
        if (remainingForThisTorrent == 0) {
            registry.releaseHandle(magnet)
            Log.i(TAG, "Stopped and purged torrent download for ${file.fileName}")
        } else {
            handle?.filePriority(fileIndex, Priority.IGNORE)
            Log.i(TAG, "Stopped tracking ${file.fileName}, handle kept alive ($remainingForThisTorrent files still active)")
        }

        if (reportStatus) progressTracker.updateDownloadStatus(file.fileName, DownloadStatus.STOPPED)
    }

    companion object { private const val TAG = "TorrentDownloadService" }
}
