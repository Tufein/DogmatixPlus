package com.cortinadev.dogmatix

import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.GameReadinessService
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.data.service.LibraryMoveService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GamePackagesEntryPoint {
    fun packagesLibrary(): LibraryIndexService
    fun packagesSettings(): SettingsRepository
    fun packageReadiness(): GameReadinessService
    fun packageMover(): LibraryMoveService
}
