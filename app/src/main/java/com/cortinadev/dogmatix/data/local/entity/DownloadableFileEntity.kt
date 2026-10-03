package com.cortinadev.dogmatix.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cortinadev.dogmatix.util.SearchNormalizer

@Entity(
    tableName = "downloadable_files",
    foreignKeys = [
        ForeignKey(
            entity = ConsoleEntity::class,
            parentColumns = ["id"],
            childColumns = ["consoleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("consoleId"), Index(value = ["consoleId", "sourceUrl"])]
)
data class DownloadableFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val fileName: String,
    val consoleId: String,
    val downloadUrl: String,
    val fileSize: Long = 0L,
    val fileExtension: String = "",
    val torrentFileIndex: Int? = null,
    val torrentMagnet: String? = null,
    /** Lenient form of [name] used for searching; see [SearchNormalizer]. */
    @ColumnInfo(defaultValue = "")
    val searchKey: String = SearchNormalizer.key(name),
    /** Hash the source publishes for the file, as `algorithm:hex` (`sha1:…`, `md5:…`, `crc32:…`); null when unknown. */
    @ColumnInfo(defaultValue = "NULL")
    val expectedHash: String? = null,
    /** The source (URL as configured) this row came from; a rescan replaces one source's rows at a time. '' = indexed before 2.0. */
    @ColumnInfo(defaultValue = "")
    val sourceUrl: String = "",
    /** When a rescan first found this file; 0 = it came with the source's first scan (not "new"). */
    @ColumnInfo(defaultValue = "0")
    val firstSeenAt: Long = 0L,
) {
    val isTorrent: Boolean get() = torrentFileIndex != null && torrentMagnet != null
}
