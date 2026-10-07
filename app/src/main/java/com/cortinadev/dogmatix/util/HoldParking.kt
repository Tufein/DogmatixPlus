package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.DownloadFailureCategory
import com.cortinadev.dogmatix.data.service.HttpStatusException
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The pure parts of parking running downloads during a hold (*Pause all*, low battery, heat):
 * which downloads can be parked without losing what they have, which to park, and in what order
 * they go back in line. Pure JVM for the tests.
 */
object HoldParking {

    /**
     * A web transfer can be parked when its partial file is kept and continued: resuming is on, the
     * app noted the partial (see PartialDownloads), the server did not say `Accept-Ranges: none`, and
     * a continued transfer was really appended to (a server that ignored the range has no ranges).
     */
    fun webResumable(resumeOn: Boolean, hasRecord: Boolean, acceptRanges: String?, partialBytes: Long, appended: Boolean): Boolean {
        if (!resumeOn || !hasRecord) return false
        if (acceptRanges?.trim()?.equals("none", ignoreCase = true) == true) return false
        return partialBytes <= 0L || appended
    }

    /**
     * The downloads to park now, in list order: transferring (not waiting for a slot, the schedule or
     * a condition), not being paused already, not parked already and safe to park ([canPark]).
     */
    fun toPark(
        list: List<DownloadItemModel>,
        transferring: Set<String>,
        isPausing: (String) -> Boolean,
        parked: Set<String>,
        canPark: (String) -> Boolean
    ): List<String> = list.asSequence()
        .filter { it.status == DownloadStatus.DOWNLOADING && it.fileName in transferring }
        .map { it.fileName }
        .filter { it !in parked && !isPausing(it) && canPark(it) }
        .toList()

    /** True once the pause of a parked download landed (or the row is gone). */
    fun landed(status: DownloadStatus?): Boolean =
        status == null || status == DownloadStatus.PAUSED || status == DownloadStatus.STOPPED

    /** The parked downloads to queue again, in the order of the list: still parked by us and paused or stopped. */
    fun toRequeue(list: List<DownloadItemModel>, parked: Set<String>): List<String> =
        list.filter { it.fileName in parked && (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.STOPPED) }
            .map { it.fileName }
}

/** Whether a failed download says something about its source, or only about this device. Pure JVM for the tests. */
object SourceFailures {
    /**
     * Source-side: HTTP errors, network I/O errors (a 404 surfaces as FileNotFoundException) and
     * anything else the transfer threw. Not: storage or configuration errors ([StorageException],
     * a lost folder permission, a full or read-only disk).
     */
    fun isSourceSide(error: Throwable?): Boolean {
        // The archive/native layer may wrap a storage exception; that still says nothing
        // about the server and must not trigger a second source download onto a full disk.
        if (error != null && DownloadFailures.classify(error)?.category in DEVICE_CATEGORIES) return false
        return when (error) {
            null -> true
            is StorageException -> false
            is SecurityException -> false
            is HttpStatusException -> true
            is FileNotFoundException -> true
            is IOException -> !looksLikeDisk(error.message)
            else -> !looksLikeDisk(error.message)
        }
    }

    private fun looksLikeDisk(message: String?): Boolean {
        val m = message ?: return false
        return DISK_HINTS.any { m.contains(it, ignoreCase = true) }
    }

    private val DISK_HINTS = listOf("ENOSPC", "No space left", "EROFS", "Read-only file system", "EDQUOT", "Disk quota")
    private val DEVICE_CATEGORIES = setOf(DownloadFailureCategory.STORAGE_FULL,
        DownloadFailureCategory.STORAGE_PERMISSION, DownloadFailureCategory.STORAGE_WRITE)
}

/** A download stopped by this device's storage (no folder, cannot create or write the file), not by its source. */
open class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Whether a partial file noted for one address may be continued from another. Pure JVM for the tests. */
object PartialOwner {
    /** The same address, or a reserve address of the same source ([mirrors]: the file's other addresses there). */
    fun sameSource(recordUrl: String, downloadUrl: String, mirrors: Collection<String>): Boolean =
        recordUrl == downloadUrl || recordUrl in mirrors
}
