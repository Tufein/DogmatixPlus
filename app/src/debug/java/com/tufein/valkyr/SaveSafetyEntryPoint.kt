package com.tufein.valkyr

import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.SaveSyncService
import com.tufein.valkyr.data.service.BackupService
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
