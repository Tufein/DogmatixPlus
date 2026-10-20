package com.tufein.valkyr

import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.GameLaunchService
import com.tufein.valkyr.data.service.LibraryIndexService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NestedLibraryEntryPoint {
    fun nestedLibrary(): LibraryIndexService
    fun nestedLibrarySettings(): SettingsRepository
    fun nestedLibraryLaunches(): GameLaunchService
}
