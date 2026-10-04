package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus

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
        val clearable: Int = 0
    ) {
        val any: Boolean get() = stoppable + retryable + clearable > 0
    }

    fun counts(list: List<DownloadItemModel>): Counts {
        var stop = 0; var retry = 0; var clear = 0
        for (item in list) when (item.status) {
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.UNZIPPING -> stop++
            DownloadStatus.FAILED, DownloadStatus.STOPPED -> retry++
            DownloadStatus.COMPLETED -> clear++
            else -> Unit
        }
        return Counts(stop, retry, clear)
    }

    fun stoppable(list: List<DownloadItemModel>): List<String> =
        list.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.UNZIPPING }.map { it.fileName }

    fun retryable(list: List<DownloadItemModel>): List<String> =
        list.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.STOPPED }.map { it.fileName }

    fun clearable(list: List<DownloadItemModel>): List<String> =
        list.filter { it.status == DownloadStatus.COMPLETED }.map { it.fileName }
}
