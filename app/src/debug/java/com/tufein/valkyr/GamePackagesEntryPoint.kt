package com.tufein.valkyr

import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.GameReadinessService
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.data.service.LibraryMoveService
import com.tufein.valkyr.data.service.GamePackageService
import com.tufein.valkyr.data.service.PackageTransferService
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.DogmatixDatabase
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.data.service.ProfileService
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
