package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cortinadev.dogmatix.data.local.entity.CoverEntity

@Dao
interface CoverDao {

    @Query("SELECT * FROM covers WHERE `key` = :key")
    suspend fun get(key: String): CoverEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CoverEntity)

    /** Forget the misses, so the next look tries again (Settings → Look → covers, "look again"). */
    @Query("DELETE FROM covers WHERE url = ''")
    suspend fun clearMisses(): Int

    /** How many games have no cover (the health check counts them; "look again" clears them). */
    @Query("SELECT COUNT(*) FROM covers WHERE url = ''")
    suspend fun countMisses(): Int
}
