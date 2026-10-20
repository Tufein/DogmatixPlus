package com.tufein.valkyr.data.repository

import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.model.DownloadItemModel
import kotlinx.coroutines.flow.StateFlow

interface DownloadRepository {
    val downloads: StateFlow<List<DownloadItemModel>>
    suspend fun getDownloads(): List<DownloadItemModel>
    suspend fun startDownload(file: DownloadableFileEntity)
    suspend fun cancelDownload(fileName: String)
    suspend fun retryDownload(fileName: String)
    suspend fun pauseDownload(fileName: String)
    suspend fun deleteDownload(fileName: String, deleteFile: Boolean = false)
}
