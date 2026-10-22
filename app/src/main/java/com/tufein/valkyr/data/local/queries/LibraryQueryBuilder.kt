package com.tufein.valkyr.data.local.queries

import com.tufein.valkyr.util.SearchNormalizer

/**
 * 4.6: builds the SQL of the library list, its tag chips and its console counts as plain text, so
 * the statement only contains the conditions that are in use (SQLite plans a query much better
 * without `:x = 0 OR ...` switches) and so the query shapes can be unit tested without a database.
 * No Android classes here; [Built] is turned into a `SimpleSQLiteQuery` by the repository.
 *
 * Shapes that matter on a library of 100k+ rows:
 *  - text search goes through the FTS table (`docid IN (… MATCH …)`) when the index is complete and every
 *    word has 3+ characters, otherwise through the old `searchKey LIKE` scan;
 *  - the page is filtered, sorted and cut with LIMIT first, and only then are the tags of those rows read;
 *  - favourites and collection members drive the join (CROSS JOIN fixes the order) instead of being probed per library row;
 *  - the manufacturer is a subquery on the console table, not a join per file.
 */
object LibraryQueryBuilder {

    /** How the free text of a search is applied. */
    sealed interface Text {
        /** No text filter. */
        object None : Text
        /** FTS4 MATCH expression such as `virtua* tenis*` (prefix per word, all words must match). */
        data class Fts(val match: String) : Text
        /** `searchKey LIKE '%' || pattern || '%'`, the pre-4.6 behaviour (words in order, anything in between). */
        data class Like(val pattern: String) : Text
    }

    /** SQL text and its bind arguments, in order (only Int/Long/String are used). */
    class Built(val sql: String, val args: List<Any>)

    /**
     * Words shorter than this fall back to LIKE: a one or two letter prefix matches most of the library, and
     * merging that many hits is slower in FTS than the plain scan (measured on 200k rows: 183 ms against 26 ms).
     */
    const val MIN_FTS_WORD = 3

    /**
     * Picks FTS or LIKE for a user query. [ftsReady] is false while the index is still being filled for rows
     * from before 4.6 (or after it failed), so search keeps answering from the LIKE scan until it is complete.
     */
    fun text(query: String, ftsReady: Boolean): Text {
        val words = SearchNormalizer.queryWords(query)
        if (words.isEmpty()) return Text.None
        if (ftsReady && words.all { it.length >= MIN_FTS_WORD }) return Text.Fts(words.joinToString(" ") { "$it*" })
        return Text.Like(words.joinToString("%"))
    }

    /** True when an FTS answer should be asked again as LIKE: nothing found on the first page (LIKE also matches inside words). */
    fun retryWithLike(text: Text, resultEmpty: Boolean, firstPage: Boolean): Boolean = text is Text.Fts && resultEmpty && firstPage

    /** True when [e] says the FTS table or index itself is broken (worth switching it off and rebuilding), not a one-off error. */
    fun isFtsFailure(e: Throwable): Boolean {
        if (e.javaClass.simpleName != "SQLiteException" && !e.javaClass.name.startsWith("android.database.sqlite.SQLite")) return false
        val m = e.message?.lowercase() ?: return false
        return "fts" in m || "malformed" in m || "no such table" in m || "corrupt" in m
    }

    /** The old DAO convention: a [SearchNormalizer.likePattern] where '*' (or empty) means no text filter. */
    fun likeText(pattern: String): Text = if (pattern.isEmpty() || pattern == "*") Text.None else Text.Like(pattern)

    class FileFilter(
        val text: Text = Text.None,
        val manufacturer: String? = null,
        val consoleIds: List<String> = emptyList(),
        val regions: List<String> = emptyList(),
        val languages: List<String> = emptyList(),
        val videoStandards: List<String> = emptyList(),
        val contentTypes: List<String> = emptyList(),
        val fileTypes: List<String> = emptyList(),
        val favouritesOnly: Boolean = false,
        /** Only files a rescan found at or after this time; 0 = no limit. */
        val newSince: Long = 0L,
        /** Only games in this collection; 0 = any. */
        val collectionId: Long = 0L,
        val hiddenConsoles: List<String> = emptyList(),
        val hiddenTags: List<String> = emptyList(),
        /** [com.tufein.valkyr.data.model.SourceFilter] ordinal: 0 all, 1 torrent, 2 RomM, 3 direct HTTP. */
        val source: Int = 0,
        /** [com.tufein.valkyr.data.model.SortOption] ordinal: 0 A→Z, 1 Z→A, 2 biggest, 3 smallest, 4 newest. */
        val sort: Int = 0,
        val limit: Int = 100,
        val offset: Int = 0
    )

    private const val COLUMNS = "id, name, fileName, consoleId, downloadUrl, fileSize, fileExtension, torrentFileIndex, torrentMagnet, expectedHash, firstSeenAt"

    /** ORDER BY terms for [sort]; ties end in name and id so paging with OFFSET is stable. */
    fun orderBy(sort: Int, alias: String): String {
        val a = "$alias."
        return when (sort) {
            1 -> "${a}name DESC, ${a}id ASC"
            2 -> "${a}fileSize DESC, ${a}name ASC, ${a}id ASC"
            3 -> "${a}fileSize ASC, ${a}name ASC, ${a}id ASC"
            4 -> "${a}firstSeenAt DESC, ${a}name ASC, ${a}id ASC"
            else -> "${a}name ASC, ${a}id ASC"
        }
    }

    fun files(f: FileFilter): Built {
        val args = ArrayList<Any>()
        val where = ArrayList<String>()
        val from: String
        // Favourites / collection members drive the join; the other conditions must then only filter. A leading
        // unary plus keeps SQLite from using an index (the FTS id list, a console list, firstSeenAt) as the driver,
        // which for 100k+ rows made it loop the whole id list once per favourite (measured: 16 s instead of ~20 ms).
        val driven = f.favouritesOnly || f.collectionId != 0L
        val p = if (driven) "+" else ""
        when {
            f.favouritesOnly -> {
                from = "favourites fav CROSS JOIN downloadable_files df ON fav.consoleId = df.consoleId AND fav.fileName = df.fileName"
                where += "fav.profileId = COALESCE((SELECT activeId FROM personal_profile WHERE id = 0), '')"
            }
            f.collectionId != 0L -> {
                from = "collection_items ci CROSS JOIN downloadable_files df ON ci.consoleId = df.consoleId AND ci.fileName = df.fileName"
                where += "ci.collectionId = ?"; args += f.collectionId
            }
            else -> from = "downloadable_files df"
        }
        if (f.favouritesOnly && f.collectionId != 0L) {
            where += "EXISTS (SELECT 1 FROM collection_items ci WHERE ci.collectionId = ? AND ci.consoleId = df.consoleId AND ci.fileName = df.fileName)"
            args += f.collectionId
        }
        textClause(f.text, args, p)?.let { where += it }
        manufacturerClause(f.manufacturer, args, p)?.let { where += it }
        if (f.consoleIds.isNotEmpty()) { where += "${p}df.consoleId IN (${marks(f.consoleIds.size)})"; args.addAll(f.consoleIds) }
        tagClause(f.regions, args)?.let { where += it }
        tagClause(f.languages, args)?.let { where += it }
        tagClause(f.videoStandards, args)?.let { where += it }
        tagClause(f.contentTypes, args)?.let { where += it }
        tagClause(f.fileTypes, args)?.let { where += it }
        if (f.newSince != 0L) { where += "${p}df.firstSeenAt >= ?"; args += f.newSince }
        if (f.hiddenConsoles.isNotEmpty()) { where += "df.consoleId NOT IN (${marks(f.hiddenConsoles.size)})"; args.addAll(f.hiddenConsoles) }
        if (f.hiddenTags.isNotEmpty()) {
            where += "NOT EXISTS (SELECT 1 FROM downloadable_file_tags th WHERE th.fileId = df.id AND th.tag IN (${marks(f.hiddenTags.size)}))"
            args.addAll(f.hiddenTags)
        }
        when (f.source) {
            1 -> where += "df.torrentFileIndex IS NOT NULL"
            // plus: the isRomm index has two values and no statistics, SQLite must never pick it as a driver here
            2 -> where += "+df.isRomm = 1"
            3 -> where += "df.torrentFileIndex IS NULL AND +df.isRomm = 0"
        }
        val inner = COLUMNS.split(", ").joinToString(", ") { "df.$it" }
        val outer = COLUMNS.split(", ").joinToString(", ") { "sub.$it AS $it" }
        val sql = buildString {
            append("SELECT ").append(outer)
            append(", (SELECT GROUP_CONCAT(t.tag, '|') FROM downloadable_file_tags t WHERE t.fileId = sub.id) AS tags")
            append(" FROM (SELECT ").append(inner).append(" FROM ").append(from)
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY ").append(orderBy(f.sort, "df"))
            append(" LIMIT ? OFFSET ?) sub")
            append(" ORDER BY ").append(orderBy(f.sort, "sub"))
        }
        args += f.limit; args += f.offset
        return Built(sql, args)
    }

    /** Distinct tags of the files that match [text], [manufacturer] and [consoleIds] (the filter chips). */
    fun tags(text: Text, manufacturer: String?, consoleIds: List<String>): Built {
        val args = ArrayList<Any>()
        val where = ArrayList<String>()
        textClause(text, args)?.let { where += it }
        manufacturerClause(manufacturer, args)?.let { where += it }
        if (consoleIds.isNotEmpty()) { where += "df.consoleId IN (${marks(consoleIds.size)})"; args.addAll(consoleIds) }
        // Without a file filter the tag table alone answers (its rows cannot outlive their file: foreign key cascade).
        val sql = if (where.isEmpty()) "SELECT DISTINCT t.tag AS tag FROM downloadable_file_tags t ORDER BY t.tag ASC"
        else "SELECT DISTINCT t.tag AS tag FROM downloadable_file_tags t JOIN downloadable_files df ON t.fileId = df.id WHERE ${where.joinToString(" AND ")} ORDER BY t.tag ASC"
        return Built(sql, args)
    }

    /** Consoles that have files matching [text] and [manufacturer], with how many. */
    fun consoles(text: Text, manufacturer: String?): Built {
        val args = ArrayList<Any>()
        val where = ArrayList<String>()
        textClause(text, args)?.let { where += it }
        if (manufacturer != null) { where += "m.name = ?"; args += manufacturer }
        val sql = buildString {
            append("SELECT c.id AS id, c.name AS name, c.manufacturerId AS manufacturerId, c.urls AS urls, COUNT(df.id) AS fileCount")
            append(" FROM consoles c JOIN downloadable_files df ON c.id = df.consoleId")
            if (manufacturer != null) append(" JOIN manufacturers m ON c.manufacturerId = m.id")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" GROUP BY c.id, c.name, c.manufacturerId, c.urls HAVING fileCount > 0 ORDER BY c.name ASC")
        }
        return Built(sql, args)
    }

    private fun textClause(text: Text, args: MutableList<Any>, plus: String = ""): String? = when (text) {
        Text.None -> null
        is Text.Fts -> { args += text.match; "${plus}df.id IN (SELECT docid FROM downloadable_files_fts WHERE downloadable_files_fts MATCH ?)" }
        is Text.Like -> { args += text.pattern; "df.searchKey LIKE '%' || ? || '%'" }
    }

    private fun manufacturerClause(manufacturer: String?, args: MutableList<Any>, plus: String = ""): String? {
        if (manufacturer == null) return null
        args += manufacturer
        return "${plus}df.consoleId IN (SELECT c.id FROM consoles c JOIN manufacturers m ON c.manufacturerId = m.id WHERE m.name = ?)"
    }

    /** One tag category: the file has any of [tags] (categories are AND-ed by the caller). */
    private fun tagClause(tags: List<String>, args: MutableList<Any>): String? {
        if (tags.isEmpty()) return null
        args.addAll(tags)
        return "EXISTS (SELECT 1 FROM downloadable_file_tags tf WHERE tf.fileId = df.id AND tf.tag IN (${marks(tags.size)}))"
    }

    private fun marks(n: Int): String = List(n) { "?" }.joinToString(",")
}
