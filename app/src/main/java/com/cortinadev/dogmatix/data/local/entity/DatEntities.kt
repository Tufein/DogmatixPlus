package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The DAT file (No-Intro, Redump, …) imported for a console; its contents are [DatRomEntity] rows. */
@Entity(tableName = "dat_sets")
data class DatSetEntity(
    @PrimaryKey val consoleId: String,
    val name: String,
    val version: String,
    val games: Int,
    val roms: Int,
    val importedAt: Long = System.currentTimeMillis()
)

/** One file of one game as the DAT describes it; hashes are lower-case hex, null when the DAT has none. */
@Entity(
    tableName = "dat_roms",
    indices = [Index(value = ["consoleId", "crc"]), Index(value = ["consoleId", "sha1"])]
)
data class DatRomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val consoleId: String,
    val gameName: String,
    val romName: String,
    val size: Long,
    val crc: String?,
    val md5: String?,
    val sha1: String?
)
