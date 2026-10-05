package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.LruCache
import com.cortinadev.dogmatix.data.local.dao.CoverDao
import com.cortinadev.dogmatix.data.local.dao.GameMetadataDao
import com.cortinadev.dogmatix.data.local.entity.CoverEntity
import com.cortinadev.dogmatix.util.CoverPlanner
import com.cortinadev.dogmatix.util.CoverSourcePolicy
import com.cortinadev.dogmatix.util.GameTitleCleaner
import com.cortinadev.dogmatix.util.RommMarks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the cover of a library game for the 5.0 screens: the RomM server's own cover when the
 * game is on the server, else libretro-thumbnails box art (no key needed), else an image the
 * metadata lookup already cached. Results, misses included, are kept in Room and in memory, so a
 * game costs at most one lookup and scrolling back is instant.
 */
@Singleton
class CoverRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: CoverDao,
    private val metadataDao: GameMetadataDao,
    private val thumbnails: ThumbnailService,
    private val rommLibrary: RommLibraryService,
    private val rommClient: RommClient
) {
    private val memory = LruCache<String, String>(4_000)
    /** At most a few lookups at once: a fast scroll should not open dozens of connections. */
    private val lookups = Semaphore(4)

    /** The cover already known for a game (memory only, no waiting): lets a row draw it at once. */
    fun cached(consoleId: String, fileName: String): String? =
        memory.get(RommMarks.key(consoleId, fileName))?.ifEmpty { null }

    /** The best cover URL for a game, or null when none is known. */
    suspend fun coverUrl(consoleId: String, fileName: String, name: String = fileName): String? {
        val key = RommMarks.key(consoleId, fileName)
        memory.get(key)?.let { return it.ifEmpty { null } }
        rommLibrary.gameFor(consoleId, fileName)?.coverPath?.takeIf { it.isNotBlank() }?.let { path ->
            val base = rommClient.configuredBaseUrl()
            if (base.isNotEmpty()) return CoverPlanner.coverUrl(base, path).also { memory.put(key, it) }
        }
        val now = System.currentTimeMillis()
        dao.get(key)?.let { row ->
            if (CoverSourcePolicy.isFresh(row.url, row.fetchedAt, now)) {
                memory.put(key, row.url)
                return row.url.ifEmpty { null }
            }
        }
        val found = lookups.withPermit {
            // Another row asked for the same game while this one waited.
            memory.get(key)?.let { return it.ifEmpty { null } }
            val boxart = runCatching { thumbnails.boxart(consoleId, fileName) }.getOrNull()
            if (boxart != null) boxart to "libretro"
            else metadataImage(name, consoleId)?.let { it to "metadata" } ?: (null to "")
        }
        val url = found.first
        // Offline: do not remember a miss, the next look should try again.
        if (url != null || online()) {
            dao.upsert(CoverEntity(key, url.orEmpty(), found.second, now))
            memory.put(key, url.orEmpty())
        }
        return url
    }

    /** Forget the games without a cover, so they are looked up again. */
    suspend fun retryMisses(): Int {
        memory.evictAll()
        return dao.clearMisses()
    }

    /** The image a metadata lookup (RAWG / TheGamesDB) already stored for this title, if any. */
    private suspend fun metadataImage(name: String, consoleId: String): String? {
        val title = GameTitleCleaner.clean(name)
        if (title.isBlank()) return null
        val platform = consoleId.substringAfter("_", consoleId).lowercase()
        return metadataDao.get("$platform|${title.lowercase()}")?.imageUrl?.takeIf { it.isNotBlank() }
    }

    private fun online(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)
}
