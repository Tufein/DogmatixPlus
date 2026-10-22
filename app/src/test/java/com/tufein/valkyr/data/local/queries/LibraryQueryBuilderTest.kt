package com.tufein.valkyr.data.local.queries

import com.tufein.valkyr.data.local.queries.LibraryQueryBuilder.FileFilter
import com.tufein.valkyr.data.local.queries.LibraryQueryBuilder.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryBuilderTest {

    private fun placeholders(sql: String) = sql.count { it == '?' }

    @Test
    fun textUsesFtsOnlyWhenReadyAndEveryWordIsLongEnough() {
        assertEquals(Text.Fts("virtua* tenis*"), LibraryQueryBuilder.text("Virtual Tenis", ftsReady = true))
        assertEquals(Text.Fts("yugio*"), LibraryQueryBuilder.text("yu-gi-oh", ftsReady = true))
        assertEquals(Text.Fts("mega* man* 64*"), LibraryQueryBuilder.text("mega man 64", ftsReady = true))
        // not complete yet: the LIKE scan answers, with the pre-4.6 pattern
        assertEquals(Text.Like("virtua%teni"), LibraryQueryBuilder.text("virtual tenis", ftsReady = false))
        // one-letter words cannot use the prefix index
        assertEquals(Text.Like("mega%man%x"), LibraryQueryBuilder.text("mega man x", ftsReady = true))
        assertEquals(Text.Like("m"), LibraryQueryBuilder.text("m", ftsReady = true))
        // nothing to search for
        assertEquals(Text.None, LibraryQueryBuilder.text("", ftsReady = true))
        assertEquals(Text.None, LibraryQueryBuilder.text("  !!! ", ftsReady = false))
    }

    @Test
    fun likeTextKeepsTheOldStarConvention() {
        assertEquals(Text.None, LibraryQueryBuilder.likeText("*"))
        assertEquals(Text.None, LibraryQueryBuilder.likeText(""))
        assertEquals(Text.Like("mari"), LibraryQueryBuilder.likeText("mari"))
    }

    @Test
    fun plainListHasNoJoinsNoSwitchesAndBindsOnlyLimitAndOffset() {
        val q = LibraryQueryBuilder.files(FileFilter())
        assertFalse(q.sql, q.sql.contains(" WHERE df."))
        assertFalse(q.sql, q.sql.contains(" OR :"))
        assertFalse(q.sql, q.sql.contains("favourites"))
        assertFalse(q.sql, q.sql.contains("consoles"))
        assertFalse(q.sql, q.sql.contains("LEFT JOIN"))
        assertEquals(listOf<Any>(100, 0), q.args)
        assertEquals(q.args.size, placeholders(q.sql))
    }

    @Test
    fun theTagsAreReadForThePageOnly() {
        val q = LibraryQueryBuilder.files(FileFilter(limit = 50, offset = 100))
        val limitAt = q.sql.indexOf("LIMIT ? OFFSET ?")
        val tagsAt = q.sql.indexOf("GROUP_CONCAT")
        assertTrue(q.sql, tagsAt in 0 until q.sql.indexOf(" FROM (SELECT "))
        assertTrue(q.sql, limitAt > q.sql.indexOf("FROM downloadable_files df"))
        // the page is cut inside the sub-select; the outer query only reads tags and keeps the order
        assertTrue(q.sql, q.sql.indexOf(") sub ORDER BY sub.name ASC, sub.id ASC") > limitAt)
        assertEquals(listOf<Any>(50, 100), q.args)
    }

    @Test
    fun ftsTextIsADocidLookup() {
        val q = LibraryQueryBuilder.files(FileFilter(text = Text.Fts("mari*")))
        assertTrue(q.sql, q.sql.contains("df.id IN (SELECT docid FROM downloadable_files_fts WHERE downloadable_files_fts MATCH ?)"))
        assertFalse(q.sql, q.sql.contains("LIKE '%'"))
        assertEquals(listOf<Any>("mari*", 100, 0), q.args)
    }

    @Test
    fun likeTextIsTheOldSearchKeyScan() {
        val q = LibraryQueryBuilder.files(FileFilter(text = Text.Like("virtua%teni")))
        assertTrue(q.sql, q.sql.contains("df.searchKey LIKE '%' || ? || '%'"))
        assertEquals(listOf<Any>("virtua%teni", 100, 0), q.args)
    }

    @Test
    fun everyFilterBindsInTheOrderItsPlaceholderAppears() {
        val q = LibraryQueryBuilder.files(FileFilter(
            text = Text.Fts("mari*"), manufacturer = "Nintendo", consoleIds = listOf("gba", "gbc"),
            regions = listOf("USA", "Europe"), languages = listOf("English"), videoStandards = listOf("NTSC"),
            contentTypes = listOf("Demo"), fileTypes = listOf("Beta"),
            newSince = 1_000L, hiddenConsoles = listOf("x", "y", "z"), hiddenTags = listOf("Adult"),
            source = 3, sort = 4, limit = 20, offset = 40
        ))
        assertEquals(q.args.size, placeholders(q.sql))
        assertEquals(
            listOf<Any>("mari*", "Nintendo", "gba", "gbc", "USA", "Europe", "English", "NTSC", "Demo", "Beta", 1_000L, "x", "y", "z", "Adult", 20, 40),
            q.args
        )
        assertTrue(q.sql, q.sql.contains("df.torrentFileIndex IS NULL AND df.isRomm = 0"))
        assertTrue(q.sql, q.sql.contains("ORDER BY df.firstSeenAt DESC, df.name ASC, df.id ASC LIMIT ? OFFSET ?"))
    }

    @Test
    fun sourceFiltersUseTheColumnNotALikeScan() {
        assertTrue(LibraryQueryBuilder.files(FileFilter(source = 1)).sql.contains("df.torrentFileIndex IS NOT NULL"))
        assertTrue(LibraryQueryBuilder.files(FileFilter(source = 2)).sql.contains("df.isRomm = 1"))
        assertFalse(LibraryQueryBuilder.files(FileFilter(source = 2)).sql.contains("api/roms"))
        assertFalse(LibraryQueryBuilder.files(FileFilter(source = 0)).sql.contains("isRomm"))
    }

    @Test
    fun sortKeepsTheOldRulesAndEndsInTheId() {
        fun order(sort: Int) = LibraryQueryBuilder.orderBy(sort, "df")
        assertEquals("df.name ASC, df.id ASC", order(0))
        assertEquals("df.name DESC, df.id ASC", order(1))
        assertEquals("df.fileSize DESC, df.name ASC, df.id ASC", order(2))
        assertEquals("df.fileSize ASC, df.name ASC, df.id ASC", order(3))
        assertEquals("df.firstSeenAt DESC, df.name ASC, df.id ASC", order(4))
        assertEquals(order(0), order(99))
    }

    @Test
    fun favouritesAndCollectionsDriveTheJoin() {
        val fav = LibraryQueryBuilder.files(FileFilter(favouritesOnly = true))
        assertTrue(fav.sql, fav.sql.contains("FROM favourites fav CROSS JOIN downloadable_files df ON fav.consoleId = df.consoleId AND fav.fileName = df.fileName"))
        assertTrue(fav.sql, fav.sql.contains("fav.profileId = COALESCE((SELECT activeId FROM personal_profile WHERE id = 0), '')"))
        val col = LibraryQueryBuilder.files(FileFilter(collectionId = 7))
        assertTrue(col.sql, col.sql.contains("FROM collection_items ci CROSS JOIN downloadable_files df ON ci.consoleId = df.consoleId AND ci.fileName = df.fileName"))
        assertEquals(listOf<Any>(7L, 100, 0), col.args)
        val both = LibraryQueryBuilder.files(FileFilter(favouritesOnly = true, collectionId = 7))
        assertTrue(both.sql, both.sql.contains("EXISTS (SELECT 1 FROM collection_items ci WHERE ci.collectionId = ?"))
        assertEquals(both.args.size, placeholders(both.sql))
    }

    @Test
    fun tagsAndConsolesBuildTheirOwnShapes() {
        val allTags = LibraryQueryBuilder.tags(Text.None, null, emptyList())
        assertFalse(allTags.sql, allTags.sql.contains("downloadable_files"))
        assertTrue(allTags.args.isEmpty())
        val some = LibraryQueryBuilder.tags(Text.Fts("mari*"), "Nintendo", listOf("gba"))
        assertTrue(some.sql, some.sql.contains("JOIN downloadable_files df ON t.fileId = df.id"))
        assertEquals(listOf<Any>("mari*", "Nintendo", "gba"), some.args)
        assertEquals(some.args.size, placeholders(some.sql))

        val consoles = LibraryQueryBuilder.consoles(Text.Like("mari"), "Nintendo")
        assertTrue(consoles.sql, consoles.sql.contains("JOIN manufacturers m ON c.manufacturerId = m.id"))
        assertEquals(listOf<Any>("mari", "Nintendo"), consoles.args)
        assertEquals(consoles.args.size, placeholders(consoles.sql))
        val noManufacturer = LibraryQueryBuilder.consoles(Text.None, null)
        assertFalse(noManufacturer.sql, noManufacturer.sql.contains("manufacturers"))
        assertTrue(noManufacturer.args.isEmpty())
    }
}
