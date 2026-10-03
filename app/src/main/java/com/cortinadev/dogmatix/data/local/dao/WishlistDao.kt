package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cortinadev.dogmatix.data.local.entity.WishlistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WishlistDao {

    @Query("SELECT * FROM wishlist ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<WishlistEntity>>

    @Query("SELECT * FROM wishlist")
    suspend fun getAll(): List<WishlistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WishlistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<WishlistEntity>)

    @Query("DELETE FROM wishlist WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE wishlist SET notifiedAt = :at WHERE id = :id")
    suspend fun markNotified(id: Long, at: Long?)
}
