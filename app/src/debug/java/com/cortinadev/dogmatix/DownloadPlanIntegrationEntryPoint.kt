package com.cortinadev.dogmatix

import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.DogmatixDatabase
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.DownloadPlanService
import com.cortinadev.dogmatix.data.service.DownloadService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug graph access for real Room/queue integration regressions; absent from release builds. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadPlanIntegrationEntryPoint {
    fun database(): DogmatixDatabase
    fun downloads(): DownloadService
    fun plans(): DownloadPlanService
    fun appSettings(): AppSettings
    fun planSettings(): SettingsRepository
}
