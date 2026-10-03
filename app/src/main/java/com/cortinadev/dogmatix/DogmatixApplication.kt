package com.cortinadev.dogmatix

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.sf.sevenzipjbinding.SevenZip
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class DogmatixApplication : Application() {
    
    @Inject
    lateinit var versionCheckerService: com.cortinadev.dogmatix.data.service.VersionCheckerService

    @Inject
    lateinit var downloadableFileRepository: com.cortinadev.dogmatix.data.repository.DownloadableFileRepository

    @Inject
    lateinit var sourcesRepository: com.cortinadev.dogmatix.data.repository.SourcesRepository

    /** Injected so it starts watching finished downloads from the first one. */
    @Inject
    lateinit var rommUploadService: com.cortinadev.dogmatix.data.service.RommUploadService

    /** Injected so the pinned RomM certificate is active from the first request. */
    @Inject
    lateinit var rommTrustService: com.cortinadev.dogmatix.data.service.RommTrustService

    /** Injected so the marks of games on the RomM server are read from the first launch. */
    @Inject
    lateinit var rommLibraryService: com.cortinadev.dogmatix.data.service.RommLibraryService

    /** Injected so the background save sync job follows its settings from the start. */
    @Inject
    lateinit var saveSyncScheduler: com.cortinadev.dogmatix.data.service.SaveSyncScheduler

    /** Injected so finished scans are checked against the wishlist from the start. */
    @Inject
    lateinit var wishlistRepository: com.cortinadev.dogmatix.data.repository.WishlistRepository

    /** Injected so the background source scan follows its settings from the start. */
    @Inject
    lateinit var autoScanScheduler: com.cortinadev.dogmatix.data.service.AutoScanScheduler

    /** Injected so the speed limit (and its night exception) applies from the first download. */
    @Inject
    lateinit var bandwidthLimiter: com.cortinadev.dogmatix.data.service.BandwidthLimiter

    /** Injected so the home-screen widget follows downloads and scans from the start. */
    @Inject
    lateinit var widgetUpdater: com.cortinadev.dogmatix.widget.WidgetUpdater

    /** Injected so finished downloads go into the statistics log from the first one. */
    @Inject
    lateinit var downloadLog: com.cortinadev.dogmatix.data.service.DownloadLog

    /** Injected so the weekly automatic backup follows its settings from the start. */
    @Inject
    lateinit var autoBackupScheduler: com.cortinadev.dogmatix.data.service.AutoBackupScheduler

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        applicationScope.launch {
            clearStaleCache()

            // Load the native 7-zip library (blocking — must run on IO thread)
            try {
                SevenZip.initSevenZipFromPlatformJAR()
                Log.d("DogmatixApplication", "7-Zip native library loaded")
            } catch (e: Exception) {
                Log.e("DogmatixApplication", "Failed to load 7-Zip native library: ${e.message}")
            }
        }

        // Check for updates on app startup
        applicationScope.launch {
            versionCheckerService.checkForUpdates(this@DogmatixApplication)
        }

        // Short names / folder aliases configured per console, kept in sync for the static helpers
        applicationScope.launch {
            sourcesRepository.aliasOverrides.collect { com.cortinadev.dogmatix.util.ConsoleAliasRegistry.overrides = it }
        }

        // Files indexed before the search key column existed need it computed once
        applicationScope.launch {
            try {
                downloadableFileRepository.backfillSearchKeys()
            } catch (e: Exception) {
                Log.e("DogmatixApplication", "Search key backfill failed: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_downloads_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(
            NotificationChannel(SYNC_CHANNEL_ID, getString(R.string.notification_channel_sync), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.notification_channel_sync_desc)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(SCAN_CHANNEL_ID, getString(R.string.notification_channel_scan), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.notification_channel_scan_desc)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(WISHLIST_CHANNEL_ID, getString(R.string.notification_channel_wishlist), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.notification_channel_wishlist_desc)
            }
        )
    }

    /**
     * Deletes cache directories left over from downloads that were interrupted before
     * they could finish copying to the user's storage and clean up after themselves.
     * Safe to run at startup because no downloads are active yet.
     */
    private fun clearStaleCache() {
        listOf("extraction_temp", "torrent_data").forEach { dir ->
            val cacheDir = File(cacheDir, dir)
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                Log.d("DogmatixApplication", "Cleared stale cache: $dir")
            }
        }
    }

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "download_channel"
        const val SYNC_CHANNEL_ID = "sync_channel"
        const val WISHLIST_CHANNEL_ID = "wishlist_channel"
        const val SCAN_CHANNEL_ID = "scan_channel"
    }
}