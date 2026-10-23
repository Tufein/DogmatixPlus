package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus

/**
 * What the whole-queue buttons on the Downloads screen would act on, so each button only shows up
 * when it has something to do. Pure JVM for the tests.
 */
object QueueActions {
    data class Counts(
        /** Queued, downloading or unpacking: *Stop all*. */
        val stoppable: Int = 0,
        /** Failed or stopped: *Retry failed*. */
        val retryable: Int = 0,
        /** Completed rows: *Clear finished* (the files stay). */
        val clearable: Int = 0,
        /** Downloading or waiting in the queue, so it can be parked: *Pause all*. */
        val pausable: Int = 0,
        /** Parked by the user: *Resume all*. */
        val resumable: Int = 0
    ) {
        val any: Boolean get() = stoppable + retryable + clearable + pausable + resumable > 0
    }

    fun counts(list: List<DownloadItemModel>): Counts {
        var stop = 0; var retry = 0; var clear = 0; var pause = 0; var resume = 0
        for (item in list) when (item.status) {
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> { stop++; pause++ }
            DownloadStatus.UNZIPPING -> stop++
            DownloadStatus.PAUSED -> resume++
            DownloadStatus.FAILED, DownloadStatus.STOPPED -> retry++
            DownloadStatus.COMPLETED -> clear++
            else -> Unit
        }
        return Counts(stop, retry, clear, pause, resume)
    }

    /**
     * A torrent pauses while it transfers; a web download also while it waits in the queue, since
     * its partial file (if any) stays and *Resume* continues from it. Unpacking or copying never pauses.
     */
    fun canPause(status: DownloadStatus, isTorrent: Boolean): Boolean =
        if (isTorrent) status == DownloadStatus.DOWNLOADING
        else status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED

    /** Downloading or waiting; the service skips what cannot be parked (a torrent only pauses while it transfers). */
    fun pausable(list: List<DownloadItemModel>): List<String> =
        list.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }.map { it.fileName }

    fun resumable(list: List<DownloadItemModel>): List<String> =
        list.filter { it.status == DownloadStatus.PAUSED }.map { it.fileName }

    fun stoppable(list: List<DownloadItemModel>): List<String> =
        list.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.UNZIPPING }.map { it.fileName }

    fun retryable(list: List<DownloadItemModel>): List<String> =
        list.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.STOPPED }.map { it.fileName }

    fun clearable(list: List<DownloadItemModel>): List<String> =
        list.filter { it.status == DownloadStatus.COMPLETED }.map { it.fileName }
}
