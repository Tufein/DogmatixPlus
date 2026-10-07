package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadStatus

/** Torrent network updates stop once the file is handed to local copy/extraction. */
object TorrentProgress {
    val NETWORK_STATUSES: Set<DownloadStatus> = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)

    fun acceptsUpdates(status: DownloadStatus?): Boolean =
        status in NETWORK_STATUSES

    /** Only verified bytes of this exact file can complete its transfer. */
    fun statusUpdate(current: DownloadStatus?, verifiedBytes: Long, expectedSize: Long): DownloadStatus? {
        if (!acceptsUpdates(current) || expectedSize <= 0 || verifiedBytes < 0) return null
        val next = if (verifiedBytes >= expectedSize) DownloadStatus.COMPLETED else DownloadStatus.DOWNLOADING
        return next.takeIf { it != current }
    }

    /** Read selected indices directly from the native vector without copying the entire collection. */
    inline fun fileBytes(fileIndex: Int, fileCount: Int, read: (Int) -> Long): Long? {
        if (fileIndex < 0 || fileIndex >= fileCount) return null
        return read(fileIndex).takeIf { it >= 0 }
    }
}
