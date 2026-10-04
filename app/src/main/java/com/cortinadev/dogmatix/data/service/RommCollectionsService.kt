package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.local.dao.CollectionDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dao.FavouriteDao
import com.cortinadev.dogmatix.data.local.entity.CollectionItemEntity
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.cortinadev.dogmatix.data.repository.CollectionsRepository
import com.cortinadev.dogmatix.util.RommSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps own collections and RomM collections alike. Only games the library lists from a RomM
 * source (`romm://…`) can be matched both ways, because only those carry their RomM id.
 *  - **From RomM**: each RomM collection becomes (or adds to) the collection of the same name here.
 *  - **To RomM**: each collection here gets a RomM collection of the same name, holding the RomM
 *    games of it; games from other sources are skipped and counted.
 */
@Singleton
class RommCollectionsService @Inject constructor(
    private val romm: RommClient,
    private val collections: CollectionsRepository,
    private val collectionDao: CollectionDao,
    private val fileDao: DownloadableFileDao,
    private val favouriteDao: FavouriteDao
) {
    data class Result(val collections: Int, val games: Int, val skipped: Int)

    /** rom id → (console, file name) of the RomM rows in the library. */
    private suspend fun rommRows(): Map<Int, Pair<String, String>> =
        fileDao.rommRows().mapNotNull { r -> RommSource.romIdOf(r.downloadUrl)?.let { it to (r.consoleId to r.fileName) } }.toMap()

    private var cachedId: Int? = null
    private suspend fun myId(): Int? = cachedId ?: romm.myUserId().also { cachedId = it }

    suspend fun pull(): Result = withContext(Dispatchers.IO) {
        val rows = rommRows()
        var games = 0
        var skipped = 0
        val remote = romm.collections()
        remote.forEach { c ->
            if (c.isFavourite) {
                // RomM's hearts become stars here, not a collection called "Favourites".
                if (c.userId != null && c.userId != myId()) return@forEach
                val have = favouriteDao.getAll().map { it.consoleId to it.fileName }.toSet()
                val items = c.romIds.mapNotNull { rows[it] }
                skipped += c.romIds.size - items.size
                val fresh = items.filter { it !in have }
                favouriteDao.upsertAll(fresh.map { (console, file) -> FavouriteEntity(console, file) })
                games += fresh.size
                return@forEach
            }
            val id = collections.create(c.name) ?: return@forEach
            val have = collectionDao.itemsOf(id).map { it.consoleId to it.fileName }.toSet()
            val items = c.romIds.mapNotNull { rows[it] }
            skipped += c.romIds.size - items.size
            val fresh = items.filter { it !in have }
            collectionDao.addItems(fresh.map { (console, file) -> CollectionItemEntity(id, console, file) })
            games += fresh.size
        }
        Result(remote.size, games, skipped)
    }

    suspend fun push(): Result = withContext(Dispatchers.IO) {
        val byKey = rommRows().entries.associate { (romId, key) -> key to romId }
        // Only the account's own collections can be changed; another user's public one of the
        // same name gets a twin of ours instead of a permission error.
        val me = myId()
        val remote = romm.collections()
            .filter { !it.isFavourite && (me == null || it.userId == null || it.userId == me) }
            .associateBy { it.name.lowercase() }
        var games = 0
        var skipped = 0
        val local = collectionDao.getAll()
        local.forEach { c ->
            val items = collectionDao.itemsOf(c.id)
            val romIds = items.mapNotNull { byKey[it.consoleId to it.fileName] }
            skipped += items.size - romIds.size
            val existing = remote[c.name.lowercase()]
            val target = existing?.id ?: romm.createCollection(c.name)
            romm.setCollectionRoms(target, c.name, ((existing?.romIds ?: emptyList()) + romIds).distinct())
            games += romIds.size
        }
        Result(local.size, games, skipped)
    }
}
