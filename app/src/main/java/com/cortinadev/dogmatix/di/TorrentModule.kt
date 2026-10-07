package com.cortinadev.dogmatix.di

import android.content.Context
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.TorrentFileIndexer
import com.cortinadev.dogmatix.data.service.TorrentHandleRegistry
import com.cortinadev.dogmatix.data.service.TorrentMetadataFetcher
import com.cortinadev.dogmatix.data.service.TorrentProgressBridge
import com.cortinadev.dogmatix.data.service.DownloadProgressTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the torrent engine.
 *
 * All services annotated with @Singleton + @Inject are discovered automatically
 * by Hilt via constructor injection:
 *   TorrentScrapingService, TorrentDownloadService, AppStatusNotificationService
 *
 * The classes below need explicit @Provides because they are either constructed
 * without an @Inject constructor (TorrentHandleRegistry is plain-constructed so
 * the module controls the single instance) or depend on the registry instance
 * that must be the same object everywhere.
 */
@Module
@InstallIn(SingletonComponent::class)
object TorrentModule {

    /**
     * The single libtorrent4j session owner. Explicit @Provides so every
     * injection site gets the exact same instance — critical because the
     * registry holds the handle cache and the session reference.
     */
    @Provides
    @Singleton
    fun provideTorrentHandleRegistry(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
        progressBridge: TorrentProgressBridge
    ): TorrentHandleRegistry = TorrentHandleRegistry(context, settingsRepository, progressBridge)

    @Provides
    @Singleton
    fun provideTorrentMetadataFetcher(
        registry: TorrentHandleRegistry
    ): TorrentMetadataFetcher = TorrentMetadataFetcher(registry)

    @Provides
    @Singleton
    fun provideTorrentFileIndexer(): TorrentFileIndexer = TorrentFileIndexer()

    /**
     * The application-scoped registry registers this bridge once as an AlertListener;
     * notification service restarts do not interrupt torrent progress or error alerts.
     */
    @Provides
    @Singleton
    fun provideTorrentProgressBridge(
        progressTracker: DownloadProgressTracker
    ): TorrentProgressBridge = TorrentProgressBridge(progressTracker)
}
