package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 5.0: the cover found for a library game, keyed like the RomM marks (`consoleId|file name stem`,
 * see RommMarks.key) so it survives rescans. [url] "" records a miss, kept for a while so a game
 * without a cover is not looked up again on every scroll.
 */
@Entity(tableName = "covers")
data class CoverEntity(
    @PrimaryKey val key: String,
    val url: String,
    /** Where the cover came from: "libretro", "metadata" or "" for a miss. */
    val source: String,
    val fetchedAt: Long
)
