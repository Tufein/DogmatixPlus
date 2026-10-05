package com.cortinadev.dogmatix.util

import java.util.Locale

/** What a console's "have / total" is measured against. */
enum class CollectionBasis {
    /** A DAT check ran this session: games of the DAT no file matched are missing. */
    DAT_VERIFIED,

    /** A DAT is imported; a game counts as had when a file on disk carries its DAT name. */
    DAT_NAMES,

    /** No DAT: the games the sources list (versions of one game count once) against what is on disk. */
    SOURCES
}

/** One console's completion. [total] 0 means nothing to measure against. */
data class ConsoleProgress(
    val consoleId: String,
    val name: String,
    val owned: Int,
    val total: Int,
    val basis: CollectionBasis,
    val goal: Boolean = false
) {
    val fraction: Float get() = CollectionGoals.fraction(owned, total)
    val percent: Int get() = CollectionGoals.percent(owned, total)
    val complete: Boolean get() = total > 0 && owned >= total
    val missing: Int get() = (total - owned).coerceAtLeast(0)
}

/** Counts and missing titles of one console. */
data class CollectionTally(val total: Int, val owned: Int, val missing: List<String>)

/**
 * The maths of the Collection goals tool: how complete a console is, from the sources' listing or
 * a DAT, against the file names found on disk. Pure JVM for the tests.
 */
object CollectionGoals {

    /** 0..1, 0 when there is nothing to measure against. */
    fun fraction(owned: Int, total: Int): Float =
        if (total <= 0) 0f else (owned.toFloat() / total).coerceIn(0f, 1f)

    /** Whole percent, rounded DOWN so 99.8 % never reads as done; 100 only when really complete. */
    fun percent(owned: Int, total: Int): Int =
        if (total <= 0) 0 else if (owned >= total) 100 else ((owned.toLong() * 100) / total).toInt().coerceIn(0, 99)

    /** All consoles together; only consoles with something to measure count. */
    fun overall(rows: List<ConsoleProgress>): Pair<Int, Int> {
        val counted = rows.filter { it.total > 0 }
        return counted.sumOf { it.owned } to counted.sumOf { it.total }
    }

    /** Goals first, then A-Z by name. */
    fun sort(rows: List<ConsoleProgress>): List<ConsoleProgress> =
        rows.sortedWith(compareByDescending<ConsoleProgress> { it.goal }.thenBy { it.name.lowercase(Locale.ROOT) })

    /** `scope|name` index keys grouped by scope (see [LibraryKeys]): one pass for all consoles. */
    fun namesByScope(keys: Set<String>): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        for (key in keys) {
            val bar = key.indexOf('|')
            if (bar < 0) continue
            out.getOrPut(key.substring(0, bar)) { HashSet() } += key.substring(bar + 1)
        }
        return out
    }

    /** The on-disk names (lower-case, with and without extension) that count for a console's [scopes]. */
    fun ownedNames(scopes: Set<String>, byScope: Map<String, Set<String>>): Set<String> {
        val out = HashSet<String>()
        for (scope in scopes) byScope[scope]?.let { out.addAll(it) }
        return out
    }

    /** Whether the listed file [fileName] is on disk for a console whose scopes are [scopes]. */
    fun isOwned(scopes: Set<String>, fileName: String, keys: Set<String>): Boolean {
        if (keys.isEmpty()) return false
        val name = FileParsingUtils.decodeUrlEncodedFileName(fileName).lowercase()
        val base = LibraryKeys.baseName(name)
        return scopes.any { scope -> "$scope|$name" in keys || "$scope|$base" in keys }
    }

    /** One game = one cleaned title: "Game (USA).zip" and "Game (Europe).7z" are the same game. */
    fun gameKey(fileName: String): String {
        val title = GameTitleCleaner.clean(FileParsingUtils.decodeUrlEncodedFileName(fileName))
        return SearchNormalizer.key(title).ifEmpty { title.lowercase() }
    }

    /**
     * What the sources list for a console: [fileNames] grouped into games, a game is had when any of
     * its versions is [owned]. Missing titles are the cleaned titles, A-Z.
     */
    fun sourceTally(fileNames: List<String>, owned: (String) -> Boolean): CollectionTally {
        val titles = LinkedHashMap<String, String>()
        val have = HashSet<String>()
        for (file in fileNames) {
            val key = gameKey(file)
            if (key.isEmpty()) continue
            if (key !in titles) titles[key] = GameTitleCleaner.clean(FileParsingUtils.decodeUrlEncodedFileName(file)).ifBlank { file }
            if (key !in have && owned(file)) have += key
        }
        val missing = titles.filterKeys { it !in have }.values.sortedBy { it.lowercase(Locale.ROOT) }
        return CollectionTally(titles.size, have.size, missing)
    }

    /**
     * A console's DAT against the names on disk ([ownedNames], lower-case): a DAT game is had when a
     * file (or a file's name without extension) equals its name. Rough on purpose; the DAT check
     * (hashes) is the exact one and wins when it ran.
     */
    fun datTally(gameNames: Collection<String>, ownedNames: Set<String>): CollectionTally {
        val games = gameNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val missing = games.filter { it.lowercase() !in ownedNames }.sortedBy { it.lowercase(Locale.ROOT) }
        return CollectionTally(games.size, games.size - missing.size, missing)
    }

    /** The tally of a finished DAT check: [gameCount] games, [missing] of them without a good file. */
    fun reportTally(gameCount: Int, missing: List<String>): CollectionTally {
        val total = maxOf(gameCount, missing.size)
        return CollectionTally(total, total - missing.size, missing.sortedBy { it.lowercase(Locale.ROOT) })
    }
}
