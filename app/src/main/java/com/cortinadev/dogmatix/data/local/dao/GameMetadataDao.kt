package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.cortinadev.dogmatix.data.local.entity.GameMetadataEntity

@Dao
interface GameMetadataDao {

    @Query("SELECT * FROM game_metadata WHERE lookupKey = :key")
    suspend fun get(key: String): GameMetadataEntity?

    /**
     * 6.0: the cached details the "search by feel" filters work on (genre, year, developer): only
     * real hits (a miss has an empty source) and none of the long texts. Re-emits when the cache grows.
     */
    @Query("SELECT lookupKey, genres, released, developer FROM game_metadata WHERE source != '' AND (genres != '' OR released != '')")
    fun observeKnown(): Flow<List<MetadataRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: GameMetadataEntity)
}

/** The light columns of [GameMetadataEntity] read by [GameMetadataDao.observeKnown]. */
data class MetadataRow(
    val lookupKey: String,
    val genres: String,
    val released: String,
    val developer: String
)
