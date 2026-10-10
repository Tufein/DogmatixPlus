package com.cortinadev.dogmatix

import com.cortinadev.dogmatix.data.service.DownloadFileManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access for real download/SAF regressions; absent from the published regular app. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface StorageSafetyEntryPoint { fun fileManager(): DownloadFileManager }
