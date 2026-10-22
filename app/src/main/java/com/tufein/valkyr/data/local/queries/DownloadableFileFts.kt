package com.tufein.valkyr.data.local.queries

import androidx.room.Entity
import androidx.room.Fts4
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity

/**
 * Full-text index over [DownloadableFileEntity.searchWords] (4.6; until then it indexed the raw `name`).
 * Room keeps it in sync with triggers on `downloadable_files`, so inserts, updates and deletes need no extra code.
 */
@Fts4(contentEntity = DownloadableFileEntity::class)
@Entity(tableName = "downloadable_files_fts")
data class DownloadableFileFts(
    val searchWords: String
)
