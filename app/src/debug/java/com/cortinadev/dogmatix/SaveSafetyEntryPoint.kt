package com.cortinadev.dogmatix

import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.SaveSyncService
import com.cortinadev.dogmatix.data.service.BackupService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Real storage/service graph used only by save-safety instrumentation tests. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SaveSafetyEntryPoint {
    fun saveSafetySyncService(): SaveSyncService
    fun saveSafetySettingsRepository(): SettingsRepository
    fun saveSafetyAppSettings(): AppSettings
    fun saveSafetyBackupService(): BackupService
}
