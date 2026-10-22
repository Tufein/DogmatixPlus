package com.tufein.valkyr.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 4.6: schema 15 -> 16, "fast search on big libraries". Every row survives; nothing is rewritten except
 * the new `isRomm` flag (one UPDATE) and nothing slow runs here: the words of the old rows are filled
 * afterwards by `DownloadableFileRepository.backfillSearchKeys()` in small transactions, and searches use
 * the old LIKE scan until that is done.
 *
 *  - `searchWords` (what the FTS index reads, see SearchNormalizer.words) and `isRomm` columns on downloadable_files;
 *  - the indexes the library queries need, and `index_favourites_profileId`; the redundant `(consoleId)` index goes;
 *  - the FTS table is recreated over `searchWords` instead of `name` (empty here, filled by the sync triggers as
 *    the backfill updates rows). Its SQL and its sync triggers are taken from the existing ones (Room wrote them,
 *    so Room's own schema check accepts the copy), with fixed fallback text for the case that they cannot be read.
 *
 * Not part of this migration, on purpose: populating the FTS index. A failure or a long run there cannot
 * keep the app from opening; at worst search stays on the LIKE scan.
 */
object Migration15To16 : Migration(15, 16) {

    private const val TAG = "Migration15To16"
    private const val FTS = "downloadable_files_fts"
    private const val TRIGGER_PREFIX = "room_fts_content_sync_"

    /** A column (or any identifier) the old FTS SQL names; it is renamed to [NEW_COLUMN] in the copy. */
    private const val OLD_COLUMN = "`name`"
    private const val NEW_COLUMN = "`searchWords`"

    /** What Room wrote for the pre-4.6 FTS table, if it could be read. */
    class OldFts(val tableSql: String, val triggers: List<Trigger>)
    class Trigger(val name: String, val sql: String)

    /** Fallback text, in the form Room 2.8 generates for `@Fts4(contentEntity = DownloadableFileEntity::class)` over `searchWords`. */
    internal const val FTS_TABLE_SQL =
        "CREATE VIRTUAL TABLE IF NOT EXISTS `downloadable_files_fts` USING FTS4(`searchWords` TEXT NOT NULL, content=`downloadable_files`)"
    internal val FTS_TRIGGER_NAMES = listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT").map { "${TRIGGER_PREFIX}${FTS}_$it" }
    internal val FTS_TRIGGERS_SQL = listOf(
        "CREATE TRIGGER IF NOT EXISTS ${FTS_TRIGGER_NAMES[0]} BEFORE UPDATE ON `downloadable_files` BEGIN DELETE FROM `downloadable_files_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS ${FTS_TRIGGER_NAMES[1]} BEFORE DELETE ON `downloadable_files` BEGIN DELETE FROM `downloadable_files_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS ${FTS_TRIGGER_NAMES[2]} AFTER UPDATE ON `downloadable_files` BEGIN INSERT INTO `downloadable_files_fts`(`docid`, `searchWords`) VALUES (NEW.`rowid`, NEW.`searchWords`); END",
        "CREATE TRIGGER IF NOT EXISTS ${FTS_TRIGGER_NAMES[3]} AFTER INSERT ON `downloadable_files` BEGIN INSERT INTO `downloadable_files_fts`(`docid`, `searchWords`) VALUES (NEW.`rowid`, NEW.`searchWords`); END"
    )

    /** The indexes of 4.6, exactly the ones DownloadableFileEntity / FavouriteEntity declare (Room checks them by name and columns). */
    internal val INDEX_SQL = listOf(
        "CREATE INDEX IF NOT EXISTS `index_downloadable_files_firstSeenAt` ON `downloadable_files` (`firstSeenAt`)",
        "CREATE INDEX IF NOT EXISTS `index_downloadable_files_name` ON `downloadable_files` (`name`)",
        "CREATE INDEX IF NOT EXISTS `index_downloadable_files_consoleId_searchKey` ON `downloadable_files` (`consoleId`, `searchKey`)",
        "CREATE INDEX IF NOT EXISTS `index_downloadable_files_consoleId_fileName` ON `downloadable_files` (`consoleId`, `fileName`)",
        "CREATE INDEX IF NOT EXISTS `index_downloadable_files_isRomm` ON `downloadable_files` (`isRomm`)",
        "CREATE INDEX IF NOT EXISTS `index_favourites_profileId` ON `favourites` (`profileId`)"
    )

    internal const val ADD_SEARCH_WORDS = "ALTER TABLE `downloadable_files` ADD COLUMN `searchWords` TEXT NOT NULL DEFAULT ''"
    internal const val ADD_IS_ROMM = "ALTER TABLE `downloadable_files` ADD COLUMN `isRomm` INTEGER NOT NULL DEFAULT 0"
    internal const val BACKFILL_IS_ROMM = "UPDATE `downloadable_files` SET `isRomm` = 1 WHERE `downloadUrl` LIKE '%/api/roms/%/content/%'"
    internal const val DROP_OLD_CONSOLE_INDEX = "DROP INDEX IF EXISTS `index_downloadable_files_consoleId`"

    override fun migrate(db: SupportSQLiteDatabase) {
        val old = try { readOldFts(db) } catch (e: Exception) { Log.w(TAG, "Old FTS definition not readable: ${e.javaClass.simpleName}"); null }
        // A database that already has the columns (an interrupted earlier run, or a test that rebuilds old tables) must not fail on ADD COLUMN.
        val columns = try { columnsOf(db) } catch (e: Exception) { emptySet() }
        plan(old).forEach { sql ->
            val skip = (sql == ADD_SEARCH_WORDS && "searchWords" in columns) || (sql == ADD_IS_ROMM && "isRomm" in columns)
            if (!skip) db.execSQL(sql)
        }
    }

    private fun columnsOf(db: SupportSQLiteDatabase): Set<String> {
        val names = HashSet<String>()
        db.query("PRAGMA table_info(`downloadable_files`)").use { c ->
            val i = c.getColumnIndex("name")
            while (c.moveToNext()) names += c.getString(i)
        }
        return names
    }

    /** The statements in order. Pure, so the shape of the migration is unit tested without a database. */
    fun plan(old: OldFts?): List<String> {
        val statements = ArrayList<String>()
        // The old sync triggers and table go first: the UPDATE below must not run them against a column set that changes.
        (old?.triggers?.map { it.name }.orEmpty() + FTS_TRIGGER_NAMES).distinct()
            .forEach { statements += "DROP TRIGGER IF EXISTS `$it`" }
        statements += "DROP TABLE IF EXISTS `$FTS`"
        statements += ADD_SEARCH_WORDS
        statements += ADD_IS_ROMM
        statements += BACKFILL_IS_ROMM
        statements += DROP_OLD_CONSOLE_INDEX
        statements += INDEX_SQL
        val derived = derive(old)
        statements += derived?.first ?: FTS_TABLE_SQL
        statements += derived?.second ?: FTS_TRIGGERS_SQL
        return statements
    }

    /** Room's own old FTS text with the column renamed, or null when it does not look as expected (then the fixed text is used). */
    private fun derive(old: OldFts?): Pair<String, List<String>>? {
        if (old == null) return null
        val table = old.tableSql
        if (!table.contains(OLD_COLUMN) || !table.contains("content=", ignoreCase = true) || !table.contains("FTS4", ignoreCase = true)) return null
        val triggers = old.triggers.map { it.sql.replace(OLD_COLUMN, NEW_COLUMN) }
        // the two BEFORE triggers only name the docid; the two AFTER triggers copy the column, so those must carry the new name
        if (triggers.size != FTS_TRIGGER_NAMES.size || triggers.any { it.contains(OLD_COLUMN) } || triggers.count { it.contains(NEW_COLUMN) } != 2) return null
        return table.replace(OLD_COLUMN, NEW_COLUMN) to triggers
    }

    private fun readOldFts(db: SupportSQLiteDatabase): OldFts? {
        var tableSql: String? = null
        db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '$FTS'").use { c ->
            if (c.moveToFirst() && !c.isNull(0)) tableSql = c.getString(0)
        }
        val triggers = ArrayList<Trigger>()
        db.query("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'downloadable_files' AND name LIKE '$TRIGGER_PREFIX%'").use { c ->
            while (c.moveToNext()) if (!c.isNull(1)) triggers += Trigger(c.getString(0), c.getString(1))
        }
        return tableSql?.let { OldFts(it, triggers) }
    }
}
