package com.cortinadev.dogmatix

import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.ArchiveExtractorService
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.data.service.StorageMoveGate
import com.cortinadev.dogmatix.data.service.TrashService
import com.cortinadev.dogmatix.data.service.VerifiedDocumentCopy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Actual explorer dependencies for UI recovery regression tests, absent from release builds. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ExplorerRecoveryEntryPoint {
    fun recovery28ServiceFactory(): StorageRecoveryServiceFactory
    fun recovery28AppSettings(): com.cortinadev.dogmatix.data.local.AppSettings
    fun recovery28Hub(): com.cortinadev.dogmatix.data.service.RecoveryHubService
    fun recovery28Availability(): com.cortinadev.dogmatix.data.service.StorageAvailabilityService
    fun recovery28Downloads(): com.cortinadev.dogmatix.data.service.DownloadService
    fun recovery28DownloadHolds(): com.cortinadev.dogmatix.data.service.StorageDownloadHolds
    fun recovery28DownloadRepository(): com.cortinadev.dogmatix.data.repository.DownloadRepository
    fun recovery28Files(): com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
    fun recovery28FileManager(): com.cortinadev.dogmatix.data.service.DownloadFileManager
    fun recovery28Packages(): com.cortinadev.dogmatix.data.service.GamePackageService
    fun recovery28History(): com.cortinadev.dogmatix.data.service.OperationHistoryService
    fun recovery28Mover(): com.cortinadev.dogmatix.data.service.LibraryMoveService
    fun recovery28SmartSettings(): com.cortinadev.dogmatix.data.local.SmartStorageSettings
    fun recovery28SaveSync(): com.cortinadev.dogmatix.data.service.SaveSyncService
    fun explorer27SettingsRepository(): SettingsRepository
    fun explorer27Extractor(): ArchiveExtractorService
    fun explorer27VerifiedCopy(): VerifiedDocumentCopy
    fun explorer27Trash(): TrashService
    fun explorer27Library(): LibraryIndexService
    fun explorer27MoveGate(): StorageMoveGate
}
