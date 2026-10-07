package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadFailure
import com.cortinadev.dogmatix.data.model.DownloadFailureCategory
import com.cortinadev.dogmatix.data.service.DebridAuthException
import com.cortinadev.dogmatix.data.service.HttpStatusException
import com.cortinadev.dogmatix.data.service.LowStorageException
import com.cortinadev.dogmatix.data.service.TorrentMetadataTimeoutException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

/** Errors remain in diagnostics; only this small, safe summary reaches download rows. */
object DownloadFailures {
    fun classify(error: Throwable): DownloadFailure? {
        val causes = causesOf(error)
        if (causes.any { it is CancellationException }) return null

        // Storage errors may be nested inside archive/native IO errors. The device remedy wins.
        if (causes.any { it is LowStorageException || containsAny(it.message, FULL_HINTS) })
            return DownloadFailure(DownloadFailureCategory.STORAGE_FULL)
        if (causes.any { it is SecurityException || it is StorageAccessException } ||
            causes.any { containsAny(it.message, PERMISSION_HINTS) })
            return DownloadFailure(DownloadFailureCategory.STORAGE_PERMISSION)
        if (causes.any { it is StorageException })
            return DownloadFailure(DownloadFailureCategory.STORAGE_WRITE)

        causes.filterIsInstance<HttpStatusException>().firstOrNull()?.let { return http(it.code) }
        if (causes.any { it is DebridAuthException })
            return DownloadFailure(DownloadFailureCategory.HTTP_AUTHENTICATION)
        if (causes.any { it is SocketTimeoutException || it is TorrentMetadataTimeoutException })
            return DownloadFailure(DownloadFailureCategory.TIMEOUT)
        if (causes.any { it is DownloadExtractionException })
            return DownloadFailure(DownloadFailureCategory.EXTRACTION)
        if (causes.any { it is IOException }) return DownloadFailure(DownloadFailureCategory.NETWORK)
        return DownloadFailure(DownloadFailureCategory.UNKNOWN)
    }

    /** Native alerts can carry paths and credentials. Interpret only known device error markers. */
    fun torrentAlert(message: String, fileError: Boolean): DownloadFailure = when {
        containsAny(message, FULL_HINTS) -> DownloadFailure(DownloadFailureCategory.STORAGE_FULL)
        containsAny(message, PERMISSION_HINTS) -> DownloadFailure(DownloadFailureCategory.STORAGE_PERMISSION)
        fileError -> DownloadFailure(DownloadFailureCategory.STORAGE_WRITE)
        else -> DownloadFailure(DownloadFailureCategory.TORRENT)
    }

    fun http(code: Int): DownloadFailure = DownloadFailure(
        category = when (code) {
            401, 403 -> DownloadFailureCategory.HTTP_AUTHENTICATION
            404, 410 -> DownloadFailureCategory.HTTP_NOT_FOUND
            408, 504 -> DownloadFailureCategory.TIMEOUT
            429 -> DownloadFailureCategory.HTTP_RATE_LIMITED
            in 500..599 -> DownloadFailureCategory.HTTP_SERVER
            else -> DownloadFailureCategory.HTTP_OTHER
        },
        httpStatusCode = code
    )

    private fun causesOf(error: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val result = ArrayList<Throwable>()
        var current: Throwable? = error
        while (current != null && result.size < 16 && seen.add(current)) {
            result += current
            current = current.cause
        }
        return result
    }

    private fun containsAny(message: String?, markers: List<String>): Boolean =
        message != null && markers.any { message.contains(it, ignoreCase = true) }

    private val FULL_HINTS = listOf("ENOSPC", "No space left", "EDQUOT", "Disk quota", "Disk full")
    private val PERMISSION_HINTS = listOf("EACCES", "EPERM", "EROFS", "Permission denied", "Read-only file system")
}

/** Lost/unconfigured SAF access is actionable even when its API only returns null. */
class StorageAccessException : StorageException("Download folder is not accessible")

/** Adds the extraction stage without carrying private provider text into [DownloadFailure]. */
class DownloadExtractionException(cause: Throwable? = null) : Exception("Archive extraction failed", cause)
