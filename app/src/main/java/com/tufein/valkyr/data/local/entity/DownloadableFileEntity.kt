package com.tufein.valkyr.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tufein.valkyr.util.SearchNormalizer

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
    // 4.6 (DB v16): the indexes the library queries need (see DogmatixDatabase.MIGRATION_15_16 for the same list as SQL).
    // (consoleId) alone is gone: (consoleId, sourceUrl) already serves every lookup by console and the foreign key.
    indices = [
        Index(value = ["consoleId", "sourceUrl"]),
        Index("fileName"),
        Index("firstSeenAt"),
        Index("name"),
        Index(value = ["consoleId", "searchKey"]),
        Index(value = ["consoleId", "fileName"]),
        Index("isRomm")
    ]
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
    /**
     * 4.6: the words of [name] for the full-text index (see [SearchNormalizer.words]); '' on rows from before
     * 4.6 until the background backfill has filled it.
     */
    @ColumnInfo(defaultValue = "")
    val searchWords: String = SearchNormalizer.words(name),
    /** 4.6: [downloadUrl] is a RomM download (`/api/roms/{id}/content/`); kept as a column so the RomM filter needs no LIKE scan. */
    @ColumnInfo(defaultValue = "0")
    val isRomm: Boolean = isRommContentUrl(downloadUrl),
) {
    val isTorrent: Boolean get() = torrentFileIndex != null && torrentMagnet != null

    companion object {
        private const val ROMM_API = "/api/roms/"
        private const val ROMM_CONTENT = "/content/"

        /** Same as SQL `url LIKE '%/api/roms/%/content/%'` (ASCII case-insensitive); the migration backfills with that SQL. */
        fun isRommContentUrl(url: String): Boolean {
            val api = url.indexOf(ROMM_API, ignoreCase = true)
            return api >= 0 && url.indexOf(ROMM_CONTENT, api + ROMM_API.length, ignoreCase = true) >= 0
        }
    }
}
