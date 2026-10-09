package com.cortinadev.dogmatix

import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.DogmatixDatabase
import com.cortinadev.dogmatix.data.local.SourcePickSettings
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.PartialDownloads
import com.cortinadev.dogmatix.data.service.SourceTrackService
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
