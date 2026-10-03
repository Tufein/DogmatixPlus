package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cortinadev.dogmatix.data.local.entity.DatRomEntity
import com.cortinadev.dogmatix.data.local.entity.DatSetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DatDao {

    @Query("SELECT * FROM dat_sets ORDER BY consoleId")
    fun observeSets(): Flow<List<DatSetEntity>>

    @Query("SELECT * FROM dat_sets WHERE consoleId = :consoleId")
    suspend fun setOf(consoleId: String): DatSetEntity?

    @Query("SELECT * FROM dat_roms WHERE consoleId = :consoleId AND crc = :crc")
    suspend fun byCrc(consoleId: String, crc: String): List<DatRomEntity>

    @Query("SELECT * FROM dat_roms WHERE consoleId = :consoleId AND sha1 = :sha1")
    suspend fun bySha1(consoleId: String, sha1: String): List<DatRomEntity>

    @Query("SELECT * FROM dat_roms WHERE consoleId = :consoleId")
    suspend fun romsOf(consoleId: String): List<DatRomEntity>

    @Query("SELECT COUNT(DISTINCT gameName) FROM dat_roms WHERE consoleId = :consoleId")
    suspend fun gameCount(consoleId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSet(set: DatSetEntity)

    @Insert
    suspend fun insertRoms(roms: List<DatRomEntity>)

    @Query("DELETE FROM dat_roms WHERE consoleId = :consoleId")
    suspend fun deleteRoms(consoleId: String)

    @Query("DELETE FROM dat_sets WHERE consoleId = :consoleId")
    suspend fun deleteSet(consoleId: String)

    /** Replaces the DAT of [set]'s console in one go. */
    @Transaction
    suspend fun replace(set: DatSetEntity, roms: List<DatRomEntity>) {
        deleteRoms(set.consoleId)
        roms.chunked(500).forEach { insertRoms(it) }
        upsertSet(set)
    }

    @Transaction
    suspend fun remove(consoleId: String) {
        deleteRoms(consoleId)
        deleteSet(consoleId)
    }
}
