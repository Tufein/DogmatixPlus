package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cortinadev.dogmatix.data.local.entity.CollectionEntity
import com.cortinadev.dogmatix.data.local.entity.CollectionItemEntity
import kotlinx.coroutines.flow.Flow

/** A collection with how many games it holds. */
data class CollectionWithCount(val id: Long, val name: String, val createdAt: Long, val count: Int)

@Dao
interface CollectionDao {

    @Query(
        "SELECT c.id, c.name, c.createdAt, (SELECT COUNT(*) FROM collection_items i WHERE i.collectionId = c.id) AS count " +
            "FROM collections c ORDER BY c.name COLLATE NOCASE"
    )
    fun observeAll(): Flow<List<CollectionWithCount>>

    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<CollectionEntity>

    @Query("SELECT * FROM collection_items")
    suspend fun getAllItems(): List<CollectionItemEntity>

    /** `collectionId` of every collection [consoleId] / [fileName] is in. */
    @Query("SELECT collectionId FROM collection_items WHERE consoleId = :consoleId AND fileName = :fileName")
    suspend fun collectionsOf(consoleId: String, fileName: String): List<Long>

    @Query("SELECT * FROM collection_items WHERE collectionId = :collectionId ORDER BY addedAt DESC")
    suspend fun itemsOf(collectionId: Long): List<CollectionItemEntity>

    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(collection: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItem(item: CollectionItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItems(items: List<CollectionItemEntity>)

    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId AND consoleId = :consoleId AND fileName = :fileName")
    suspend fun removeItem(collectionId: Long, consoleId: String, fileName: String)

    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId")
    suspend fun clearItems(collectionId: Long)

    @Transaction
    suspend fun replaceItems(id: Long, items: List<CollectionItemEntity>) {
        if (itemsOf(id).map { it.consoleId to it.fileName }.toSet() == items.map { it.consoleId to it.fileName }.toSet()) return
        clearItems(id)
        addItems(items)
    }

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    @Transaction
    suspend fun delete(id: Long) {
        clearItems(id)
        deleteCollection(id)
    }
}
