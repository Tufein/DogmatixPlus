package com.tufein.valkyr

import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.DogmatixDatabase
import com.tufein.valkyr.data.local.SourcePickSettings
import com.tufein.valkyr.data.service.DownloadService
import com.tufein.valkyr.data.service.PartialDownloads
import com.tufein.valkyr.data.service.SourceTrackService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Real debug application graph access for source-switch instrumentation; absent from release APKs. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ManualSourceEntryPoint {
    fun database(): DogmatixDatabase
    fun downloads(): DownloadService
    fun partials(): PartialDownloads
    fun sourcePick(): SourcePickSettings
    fun sourceTrack(): SourceTrackService
    fun appSettings(): AppSettings
}
