package com.cortinadev.dogmatix.util

import java.util.Locale

/**
 * "Search by feel": filters over the details the app has really cached in `game_metadata`
 * (genres, release year, developer from RAWG / TheGamesDB). Nothing here invents data: a game with
 * no cached details simply is not matched by a genre or decade filter, and [Coverage] says how many
 * titles the filters can know about. Pure, so it is unit-tested.
 */
object LibraryDiscovery {

    /** What is known about one title. [year] is null when the release date was missing or odd. */
    data class Meta(val genres: Set<String>, val year: Int?, val developer: String)

    /** Genre and decade selection; empty sets mean "any". A game must match both (OR within one set). */
    data class Filter(val genres: Set<String> = emptySet(), val decades: Set<Int> = emptySet()) {
        val isActive: Boolean get() = genres.isNotEmpty() || decades.isNotEmpty()
        /** Number of active filter rows (genre, decade) for the "N filters" badge. */
        val activeCount: Int get() = (if (genres.isNotEmpty()) 1 else 0) + (if (decades.isNotEmpty()) 1 else 0)

        /** A game without [meta] never matches an active filter. */
        fun matches(meta: Meta?): Boolean {
            if (!isActive) return true
            if (meta == null) return false
            if (genres.isNotEmpty() && meta.genres.none { it in genres }) return false
            if (decades.isNotEmpty()) {
                val year = meta.year ?: return false
                if (decadeOf(year) !in decades) return false
            }
            return true
        }
    }

    /** How much the filters can know: [knownTitles] cached titles (with a genre or a year) against [libraryFiles] rows. */
    data class Coverage(val knownTitles: Int, val libraryFiles: Int)

    /** Option lists derived from the cached details: genre → titles, decade → titles, most common first. */
    class Index(val byKey: Map<String, Meta>) {
        val genres: List<Pair<String, Int>>
        val decades: List<Pair<Int, Int>>
        val knownTitles: Int get() = byKey.size

        init {
            val g = HashMap<String, Int>()
            val d = HashMap<Int, Int>()
            byKey.values.forEach { meta ->
                meta.genres.forEach { g[it] = (g[it] ?: 0) + 1 }
                meta.year?.let { y -> decadeOf(y).let { dec -> d[dec] = (d[dec] ?: 0) + 1 } }
            }
            genres = g.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }
            decades = d.entries.sortedBy { it.key }.map { it.key to it.value }
        }

        fun metaFor(consoleId: String, name: String): Meta? = byKey[lookupKey(consoleId, name)]

        companion object { val EMPTY = Index(emptyMap()) }
    }

    /**
     * The key `GameMetadataService` stores a lookup under: `platform|cleaned title` in lower case,
     * where platform is the console id without its manufacturer prefix.
     */
    fun lookupKey(consoleId: String, name: String): String {
        val title = GameTitleCleaner.clean(name)
        return "${consoleId.substringAfter("_", consoleId).lowercase()}|${title.lowercase()}"
    }

    /** Builds an [Index] from raw cache rows; rows with neither genre nor year are left out. */
    fun buildIndex(rows: List<RawRow>): Index {
        val map = HashMap<String, Meta>(rows.size * 2)
        for (row in rows) {
            val meta = meta(row.genres, row.released, row.developer)
            if (meta.genres.isNotEmpty() || meta.year != null) map[row.lookupKey] = meta
        }
        return Index(map)
    }

    data class RawRow(val lookupKey: String, val genres: String, val released: String, val developer: String)

    fun meta(genresRaw: String, released: String, developer: String): Meta =
        Meta(parseGenres(genresRaw), parseYear(released), developer.trim())

    /** The cache joins genres with `|`. */
    fun parseGenres(raw: String): Set<String> =
        raw.split('|').map { canonicalGenre(it) }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())

    private val genreAliases = mapOf(
        "role-playing" to "Role-Playing", "role playing" to "Role-Playing", "rpg" to "Role-Playing",
        "platformer" to "Platform", "platform" to "Platform",
        "educational" to "Education", "education" to "Education",
        "massively multiplayer" to "MMO", "mmo" to "MMO",
        "board" to "Board", "card" to "Card", "casual" to "Casual", "indie" to "Indie"
    )

    /**
     * One display name for the two databases' spellings: RAWG's "Role-playing games (RPG)" and
     * TheGamesDB's "Role-Playing" are the same genre, "Platformer" and "Platform" too.
     */
    fun canonicalGenre(raw: String): String {
        var g = raw.replace(Regex("\\([^)]*\\)"), " ").trim().lowercase(Locale.ROOT)
        g = g.removeSuffix(" games").removeSuffix(" game").trim()
        if (g.isEmpty()) return ""
        genreAliases[g]?.let { return it }
        return g.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }

    /** The 4-digit year at the start of a release date ("1996", "1996-06-23"); null outside 1950..2100. */
    fun parseYear(released: String): Int? {
        val digits = released.trim().take(4)
        if (digits.length != 4 || !digits.all { it.isDigit() }) return null
        return digits.toInt().takeIf { it in 1950..2100 }
    }

    fun decadeOf(year: Int): Int = year / 10 * 10

    /**
     * Keeps the rows whose game matches [filter]. [keyOf] gives a row's lookup key; rows are tested
     * in order and at most [limit] are taken. [consumed] is how many input rows were looked at, so a
     * caller can continue after them.
     */
    class Matched<T>(val rows: List<T>, val consumed: Int)

    fun <T> match(rows: List<T>, index: Index, filter: Filter, limit: Int, keyOf: (T) -> String): Matched<T> {
        val out = ArrayList<T>()
        var consumed = 0
        for (row in rows) {
            if (out.size >= limit) break
            consumed++
            if (filter.matches(index.byKey[keyOf(row)])) out.add(row)
        }
        return Matched(out, consumed)
    }

    /** A title the user can ask details for. */
    data class Target(val key: String, val name: String, val consoleId: String)

    /**
     * Which of [candidates] to look up for "Fetch details for the shown games": titles that are not
     * cached ([known]) and were not tried already ([tried]), one per title, at most [cap].
     */
    fun fetchTargets(candidates: List<Target>, known: Set<String>, tried: Set<String>, cap: Int): List<Target> {
        val seen = HashSet<String>()
        val out = ArrayList<Target>()
        for (c in candidates) {
            if (out.size >= cap) break
            if (c.key in known || c.key in tried || !seen.add(c.key)) continue
            out.add(c)
        }
        return out
    }
}
