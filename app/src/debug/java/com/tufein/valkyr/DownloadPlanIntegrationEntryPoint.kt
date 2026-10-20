package com.tufein.valkyr

import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.DogmatixDatabase
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.DownloadPlanService
import com.tufein.valkyr.data.service.DownloadService
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
