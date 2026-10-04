package com.cortinadev.dogmatix.data.repository

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cortinadev.dogmatix.DogmatixApplication
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dao.WishlistDao
import com.cortinadev.dogmatix.data.local.entity.WishlistEntity
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A wishlist entry and how many library rows match it right now. */
data class WishlistStatus(val item: WishlistEntity, val matches: Int)

/**
 * Games the user wants that no source lists yet. After every source scan the library is searched
 * for each wanted title; the first time one turns up, the user is told (once) with a notification.
 */
@Singleton
class WishlistRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: WishlistDao,
    private val fileDao: DownloadableFileDao,
    rescanStateHolder: RescanStateHolder,
    private val appSettings: com.cortinadev.dogmatix.data.local.AppSettings,
    private val settingsRepository: SettingsRepository,
    private val downloadService: com.cortinadev.dogmatix.data.service.DownloadService,
    private val libraryIndex: dagger.Lazy<com.cortinadev.dogmatix.data.service.LibraryIndexService>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val items: Flow<List<WishlistEntity>> = dao.observeAll()

    init {
        // A finished source scan (the time moves) may have brought a wanted game into the library.
        scope.launch { rescanStateHolder.lastRescanTime.drop(1).collect { checkAndNotify() } }
    }

    suspend fun add(title: String, consoleId: String?): Boolean {
        val clean = title.trim()
        if (clean.length < 2) return false
        val entry = WishlistEntity(title = clean, consoleId = consoleId?.takeIf { it.isNotBlank() })
        if (dao.getAll().any { it.key == entry.key && it.consoleId == entry.consoleId }) return false
        dao.upsert(entry)
        return true
    }

    suspend fun remove(id: Long) = dao.delete(id)

    suspend fun statuses(): List<WishlistStatus> =
        dao.getAll().sortedByDescending { it.addedAt }.map { WishlistStatus(it, matches(it)) }

    suspend fun matches(item: WishlistEntity): Int = if (item.key.isEmpty()) 0 else fileDao.countMatching(item.key, item.consoleId)

    /** Marks and announces wanted games that are in the library now; returns them. */
    suspend fun checkAndNotify(): List<WishlistEntity> {
        val found = dao.getAll().filter { it.notifiedAt == null && matches(it) > 0 }
        if (found.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        found.forEach { dao.markNotified(it.id, now) }
        notify(found)
        if (appSettings.wishlistAutoDownload.first()) autoDownload(found)
        return found
    }

    /**
     * Downloads the best version of each newly found wanted game (by the user's favourite
     * languages), unless a version is already on the device or downloading. One per console.
     */
    suspend fun autoDownload(found: List<WishlistEntity>): Int {
        val languages = settingsRepository.favoriteLanguages.first()
        val regions = com.cortinadev.dogmatix.util.VersionPicker.regionPreference(languages)
        val index = libraryIndex.get()
        val picks = found.flatMap { item ->
            fileDao.filesMatching(item.key, item.consoleId)
                .filter { com.cortinadev.dogmatix.util.GameTitleCleaner.containsAllWords(item.title, it.fileName) }
                .groupBy { it.consoleId }.mapNotNull { (_, files) ->
                if (files.any { index.isOwned(it) || downloadService.isActive(it.fileName) }) return@mapNotNull null
                val best = com.cortinadev.dogmatix.util.VersionPicker.best(
                    files.map { com.cortinadev.dogmatix.util.VersionPicker.Candidate(it.fileName, it.fileName, fileDao.tagsOf(it.id), it.fileSize) },
                    regions, languages
                ) ?: return@mapNotNull null
                files.first { it.fileName == best.id }
            }
        }.distinctBy { it.fileName }
        if (picks.isNotEmpty()) downloadService.startDownloads(picks)
        return picks.size
    }

    private fun notify(found: List<WishlistEntity>) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val titles = found.take(3).joinToString(", ") { it.title } + if (found.size > 3) " …" else ""
        val notification = NotificationCompat.Builder(context, DogmatixApplication.WISHLIST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_star)
            .setContentTitle(context.resources.getQuantityString(R.plurals.wishlist_found_title, found.size, found.size))
            .setContentText(titles)
            .setStyle(NotificationCompat.BigTextStyle().bigText(titles))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(WISHLIST_NOTIFICATION_ID, notification)
    }

    private companion object { const val WISHLIST_NOTIFICATION_ID = 4203 }
}
