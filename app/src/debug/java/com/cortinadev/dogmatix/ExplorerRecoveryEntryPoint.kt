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
    fun explorer27SettingsRepository(): SettingsRepository
    fun explorer27Extractor(): ArchiveExtractorService
    fun explorer27VerifiedCopy(): VerifiedDocumentCopy
    fun explorer27Trash(): TrashService
    fun explorer27Library(): LibraryIndexService
    fun explorer27MoveGate(): StorageMoveGate
}
