package com.cortinadev.dogmatix.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cortinadev.dogmatix.util.SearchNormalizer

/**
 * A game the user wants but no source lists yet. After every source scan the library is searched
 * for [title] (optionally within one console); the first time it turns up the user is told.
 */
@Entity(tableName = "wishlist")
data class WishlistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Console the game must be for; null = any console. */
    @ColumnInfo(defaultValue = "NULL") val consoleId: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** When the user was told the game is in the library; null while still wanted. */
    @ColumnInfo(defaultValue = "NULL") val notifiedAt: Long? = null
) {
    /** [SearchNormalizer] key the library is searched with. */
    val key: String get() = SearchNormalizer.key(title)
}
