package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A list the user made ("Couch co-op", "To finish"); its games are [CollectionItemEntity] rows. */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** A game in a collection. Keyed by console + file name, like favourites, so it survives rescans. */
@Entity(
    tableName = "collection_items",
    primaryKeys = ["collectionId", "consoleId", "fileName"],
    indices = [Index(value = ["consoleId", "fileName"])]
)
data class CollectionItemEntity(
    val collectionId: Long,
    val consoleId: String,
    val fileName: String,
    val addedAt: Long = System.currentTimeMillis()
)
