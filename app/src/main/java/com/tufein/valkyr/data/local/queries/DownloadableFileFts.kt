package com.tufein.valkyr.data.local.queries

import androidx.room.Entity
import androidx.room.Fts4
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity

@Fts4(contentEntity = DownloadableFileEntity::class)
@Entity(tableName = "downloadable_files_fts")
data class DownloadableFileFts(
    val name: String
)
