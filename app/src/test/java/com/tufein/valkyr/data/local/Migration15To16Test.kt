package com.tufein.valkyr.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The migration is plain SQL text, so its shape is checked here; the run against a real v15 file is in androidTest. */
class Migration15To16Test {

    private val oldTable = "CREATE VIRTUAL TABLE IF NOT EXISTS `downloadable_files_fts` USING FTS4(`name` TEXT NOT NULL, content=`downloadable_files`)"
    private val oldTriggers = listOf(
        Migration15To16.Trigger("room_fts_content_sync_downloadable_files_fts_BEFORE_UPDATE",
            "CREATE TRIGGER room_fts_content_sync_downloadable_files_fts_BEFORE_UPDATE BEFORE UPDATE ON `downloadable_files` BEGIN DELETE FROM `downloadable_files_fts` WHERE `docid`=OLD.`rowid`; END"),
        Migration15To16.Trigger("room_fts_content_sync_downloadable_files_fts_BEFORE_DELETE",
            "CREATE TRIGGER room_fts_content_sync_downloadable_files_fts_BEFORE_DELETE BEFORE DELETE ON `downloadable_files` BEGIN DELETE FROM `downloadable_files_fts` WHERE `docid`=OLD.`rowid`; END"),
        Migration15To16.Trigger("room_fts_content_sync_downloadable_files_fts_AFTER_UPDATE",
            "CREATE TRIGGER room_fts_content_sync_downloadable_files_fts_AFTER_UPDATE AFTER UPDATE ON `downloadable_files` BEGIN INSERT INTO `downloadable_files_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END"),
        Migration15To16.Trigger("room_fts_content_sync_downloadable_files_fts_AFTER_INSERT",
            "CREATE TRIGGER room_fts_content_sync_downloadable_files_fts_AFTER_INSERT AFTER INSERT ON `downloadable_files` BEGIN INSERT INTO `downloadable_files_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END")
    )

    @Test
    fun newColumnsAreAddedWithDefaultsSoEveryRowSurvives() {
        val sql = Migration15To16.plan(null)
        assertTrue(sql.contains("ALTER TABLE `downloadable_files` ADD COLUMN `searchWords` TEXT NOT NULL DEFAULT ''"))
        assertTrue(sql.contains("ALTER TABLE `downloadable_files` ADD COLUMN `isRomm` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(sql.contains("UPDATE `downloadable_files` SET `isRomm` = 1 WHERE `downloadUrl` LIKE '%/api/roms/%/content/%'"))
    }

    @Test
    fun nothingDestructiveHappensToDataTables() {
        val sql = Migration15To16.plan(null) + Migration15To16.plan(Migration15To16.OldFts(oldTable, oldTriggers))
        sql.forEach {
            assertFalse(it, it.startsWith("DELETE", ignoreCase = true))
            assertFalse(it, it.startsWith("INSERT", ignoreCase = true))
            if (it.startsWith("DROP TABLE")) assertEquals("DROP TABLE IF EXISTS `downloadable_files_fts`", it)
            if (it.startsWith("DROP INDEX")) assertEquals("DROP INDEX IF EXISTS `index_downloadable_files_consoleId`", it)
        }
        // the only UPDATE is the isRomm flag
        assertEquals(listOf("UPDATE `downloadable_files` SET `isRomm` = 1 WHERE `downloadUrl` LIKE '%/api/roms/%/content/%'"),
            sql.filter { it.startsWith("UPDATE") }.distinct())
    }

    @Test
    fun theOldSyncTriggersGoBeforeAnyColumnChange() {
        val sql = Migration15To16.plan(null)
        val firstAlter = sql.indexOfFirst { it.startsWith("ALTER TABLE") }
        val update = sql.indexOfFirst { it.startsWith("UPDATE") }
        val dropTriggers = sql.withIndex().filter { it.value.startsWith("DROP TRIGGER") }.map { it.index }
        assertEquals(4, dropTriggers.size)
        assertTrue(dropTriggers.all { it < firstAlter && it < update })
        assertTrue(sql.indexOf("DROP TABLE IF EXISTS `downloadable_files_fts`") < firstAlter)
        // the new triggers are created last, after the UPDATE, so the backfill below fills the index and the migration does not
        val createTriggers = sql.withIndex().filter { it.value.startsWith("CREATE TRIGGER") }.map { it.index }
        assertEquals(4, createTriggers.size)
        assertTrue(createTriggers.all { it > update })
        assertEquals(sql.lastIndex, createTriggers.last())
    }

    @Test
    fun indexesMatchTheEntityDeclarations() {
        val sql = Migration15To16.plan(null)
        val expected = listOf(
            "index_downloadable_files_firstSeenAt" to "`firstSeenAt`",
            "index_downloadable_files_name" to "`name`",
            "index_downloadable_files_consoleId_searchKey" to "`consoleId`, `searchKey`",
            "index_downloadable_files_consoleId_fileName" to "`consoleId`, `fileName`",
            "index_downloadable_files_isRomm" to "`isRomm`",
            "index_favourites_profileId" to "`profileId`"
        )
        expected.forEach { (name, columns) ->
            assertTrue(name, sql.any { it.startsWith("CREATE INDEX IF NOT EXISTS `$name` ON ") && it.endsWith("($columns)") })
        }
        // the 2.0 / 4.0 indexes stay untouched
        assertFalse(sql.any { it.contains("index_downloadable_files_consoleId_sourceUrl") })
        assertFalse(sql.any { it.contains("index_downloadable_files_fileName") })
    }

    @Test
    fun withoutAnOldDefinitionTheFixedFtsTextIsUsed() {
        val sql = Migration15To16.plan(null)
        assertTrue(sql.contains(Migration15To16.FTS_TABLE_SQL))
        assertTrue(Migration15To16.FTS_TABLE_SQL.contains("FTS4(`searchWords` TEXT NOT NULL, content=`downloadable_files`)"))
        Migration15To16.FTS_TRIGGERS_SQL.forEach { assertTrue(it, sql.contains(it)) }
        assertEquals(4, Migration15To16.FTS_TRIGGERS_SQL.size)
        assertFalse(Migration15To16.FTS_TRIGGERS_SQL.any { it.contains("`name`") })
    }

    @Test
    fun theOldDefinitionIsCopiedWithTheColumnRenamed() {
        val sql = Migration15To16.plan(Migration15To16.OldFts(oldTable, oldTriggers))
        val create = sql.filter { it.startsWith("CREATE VIRTUAL TABLE") }
        assertEquals(listOf("CREATE VIRTUAL TABLE IF NOT EXISTS `downloadable_files_fts` USING FTS4(`searchWords` TEXT NOT NULL, content=`downloadable_files`)"), create)
        val triggers = sql.filter { it.startsWith("CREATE TRIGGER") }
        assertEquals(4, triggers.size)
        assertTrue(triggers.all { it.contains("`searchWords`") && !it.contains("`name`") })
        // the old trigger names are dropped by name too
        oldTriggers.forEach { assertTrue(it.name, sql.contains("DROP TRIGGER IF EXISTS `${it.name}`")) }
    }

    @Test
    fun anUnexpectedOldDefinitionFallsBackToTheFixedText() {
        val odd = Migration15To16.OldFts("CREATE VIRTUAL TABLE `downloadable_files_fts` USING FTS4(`title`)", oldTriggers.take(2))
        val sql = Migration15To16.plan(odd)
        assertTrue(sql.contains(Migration15To16.FTS_TABLE_SQL))
        Migration15To16.FTS_TRIGGERS_SQL.forEach { assertTrue(it, sql.contains(it)) }
    }
}
