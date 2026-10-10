package com.cortinadev.dogmatix

import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.GameLaunchService
import com.cortinadev.dogmatix.data.service.LibraryIndexService
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
