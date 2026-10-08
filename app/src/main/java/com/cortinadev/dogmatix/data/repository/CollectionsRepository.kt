package com.cortinadev.dogmatix.data.repository

import com.cortinadev.dogmatix.data.local.dao.CollectionDao
import com.cortinadev.dogmatix.data.local.dao.CollectionWithCount
import com.cortinadev.dogmatix.data.local.entity.CollectionEntity
import com.cortinadev.dogmatix.data.local.entity.CollectionItemEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.util.SourceCollection
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Own collections of games ("Couch co-op", "To finish"). Like favourites, a game is kept by console
 * + file name, so it stays in its collections through rescans and lights up again when a source
 * lists it once more.
 */
@Singleton
class CollectionsRepository @Inject constructor(private val dao: CollectionDao) {

    val collections: Flow<List<CollectionWithCount>> = dao.observeAll()

    /** Creates a collection; returns its id, or the id of an existing one with the same name. */
    suspend fun create(name: String): Long? {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) return null
        dao.getAll().firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it.id }
        return dao.insert(CollectionEntity(name = clean))
    }

    suspend fun rename(id: Long, name: String) {
        val clean = name.trim().take(60)
        if (clean.isNotEmpty()) dao.rename(id, clean)
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun collectionsOf(file: DownloadableFileEntity): Set<Long> = dao.collectionsOf(file.consoleId, file.fileName).toSet()

    /** Puts [file] in or takes it out of collection [id]; returns whether it is in it now. */
    suspend fun toggle(id: Long, file: DownloadableFileEntity): Boolean {
        val inIt = id in collectionsOf(file)
        if (inIt) dao.removeItem(id, file.consoleId, file.fileName)
        else dao.addItem(CollectionItemEntity(id, file.consoleId, file.fileName))
        return !inIt
    }

    suspend fun identities(): Map<Long, String> = dao.getAll().associate { it.id to it.name }

    /** All collections with their games, for an export. */
    suspend fun export(): List<SourceCollection> {
        val items = dao.getAllItems().groupBy { it.collectionId }
        return dao.getAll().map { c -> SourceCollection(c.name, items[c.id].orEmpty().map { it.consoleId to it.fileName }) }
    }

    /** Adds the collections of an import to the ones here (same name = same collection); returns how many games were new. */
    suspend fun import(incoming: List<SourceCollection>): Int {
        var added = 0
        incoming.forEach { c ->
            val id = create(c.name) ?: return@forEach
            val have = dao.itemsOf(id).map { it.consoleId to it.fileName }.toSet()
            val fresh = c.items.filter { it !in have }
            dao.addItems(fresh.map { (console, file) -> CollectionItemEntity(id, console, file) })
            added += fresh.size
        }
        return added
    }
}
