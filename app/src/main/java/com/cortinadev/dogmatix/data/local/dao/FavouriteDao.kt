package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cortinadev.dogmatix.data.local.entity.PersonalProfileEntity
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavouriteDao {

    @Query("SELECT * FROM favourites WHERE profileId = COALESCE((SELECT activeId FROM personal_profile WHERE id = 0), '')")
    fun observeAll(): Flow<List<FavouriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRaw(entry: FavouriteEntity)

    @Query("SELECT activeId FROM personal_profile WHERE id = 0")
    suspend fun activeProfile(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setActive(profile: PersonalProfileEntity)

    @Transaction
    suspend fun upsert(entry: FavouriteEntity) = insertRaw(entry.copy(profileId = activeProfile().orEmpty()))

    @Query("SELECT * FROM favourites WHERE profileId = COALESCE((SELECT activeId FROM personal_profile WHERE id = 0), '')")
    suspend fun getAll(): List<FavouriteEntity>

    @Transaction
    suspend fun upsertAll(entries: List<FavouriteEntity>) {
        val profile = activeProfile().orEmpty()
        entries.forEach { insertRaw(it.copy(profileId = profile)) }
    }

    @Query("SELECT * FROM favourites")
    suspend fun allProfiles(): List<FavouriteEntity>

    @Query("DELETE FROM favourites WHERE profileId = :id")
    suspend fun deleteProfile(id: String)

    @Query("DELETE FROM favourites WHERE consoleId = :consoleId AND fileName = :fileName AND profileId = COALESCE((SELECT activeId FROM personal_profile WHERE id = 0), '')")
    suspend fun delete(consoleId: String, fileName: String)
}
