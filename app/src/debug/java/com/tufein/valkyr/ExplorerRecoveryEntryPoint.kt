package com.tufein.valkyr

import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.ArchiveExtractorService
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.data.service.StorageMoveGate
import com.tufein.valkyr.data.service.TrashService
import com.tufein.valkyr.data.service.VerifiedDocumentCopy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Actual explorer dependencies for UI recovery regression tests, absent from release builds. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ExplorerRecoveryEntryPoint {
    fun recovery28ServiceFactory(): StorageRecoveryServiceFactory
    fun recovery28AppSettings(): com.tufein.valkyr.data.local.AppSettings
    fun recovery28Hub(): com.tufein.valkyr.data.service.RecoveryHubService
    fun recovery28Availability(): com.tufein.valkyr.data.service.StorageAvailabilityService
    fun recovery28Downloads(): com.tufein.valkyr.data.service.DownloadService
    fun recovery28DownloadHolds(): com.tufein.valkyr.data.service.StorageDownloadHolds
    fun recovery28DownloadRepository(): com.tufein.valkyr.data.repository.DownloadRepository
    fun recovery28Files(): com.tufein.valkyr.data.local.dao.DownloadableFileDao
    fun recovery28FileManager(): com.tufein.valkyr.data.service.DownloadFileManager
    fun recovery28Packages(): com.tufein.valkyr.data.service.GamePackageService
    fun recovery28History(): com.tufein.valkyr.data.service.OperationHistoryService
    fun recovery28Mover(): com.tufein.valkyr.data.service.LibraryMoveService
    fun recovery28SmartSettings(): com.tufein.valkyr.data.local.SmartStorageSettings
    fun recovery28SaveSync(): com.tufein.valkyr.data.service.SaveSyncService
    fun explorer27SettingsRepository(): SettingsRepository
    fun explorer27Extractor(): ArchiveExtractorService
    fun explorer27VerifiedCopy(): VerifiedDocumentCopy
    fun explorer27Trash(): TrashService
    fun explorer27Library(): LibraryIndexService
    fun explorer27MoveGate(): StorageMoveGate
}
