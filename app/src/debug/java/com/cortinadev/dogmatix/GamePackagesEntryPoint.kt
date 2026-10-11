package com.cortinadev.dogmatix

import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.GameReadinessService
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.data.service.LibraryMoveService
import com.cortinadev.dogmatix.data.service.GamePackageService
import com.cortinadev.dogmatix.data.service.PackageTransferService
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.DogmatixDatabase
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.service.ProfileService
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
    fun packageTransfers(): PackageTransferService
    fun gamePackages(): GamePackageService
    fun packageAppSettings(): AppSettings
    fun packageDatabase(): DogmatixDatabase
    fun packageFiles(): DownloadableFileRepository
    fun packageProfiles(): ProfileService
}
