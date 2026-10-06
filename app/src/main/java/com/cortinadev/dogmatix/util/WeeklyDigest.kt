package com.cortinadev.dogmatix.util

import java.util.Locale

/** A file a source scan found during the week. */
data class DigestFile(val consoleId: String, val fileName: String)

/** A game on the wishlist that is still wanted ([consoleId] null = for any console). */
data class DigestWish(val title: String, val consoleId: String?)

/** How far a console the user made a goal of is. */
data class DigestGoal(val consoleId: String, val name: String, val owned: Int, val total: Int) {
    val percent: Int get() = CollectionGoals.percent(owned, total)
    val complete: Boolean get() = total > 0 && owned >= total
}

data class DigestConsole(val consoleId: String, val count: Int)

/** What the weekly notification says. Counts only; the texts come from the string resources. */
data class DigestSummary(
    /** New games (versions of one game count once) for the consoles the user has games for. */
    val newGames: Int,
    /** The consoles with the most of them, at most [WeeklyDigest.TOP_CONSOLES]. */
    val topConsoles: List<DigestConsole>,
    /** Titles of the wishlist that turned up in a source this week. */
    val wishlistHits: List<String>,
    /** Goals not complete yet, the nearest to done first, at most [WeeklyDigest.TOP_GOALS]. */
    val goals: List<DigestGoal>,
    val completedGoals: Int,
    val downloads: Int,
    val downloadedBytes: Long
) {
    /** Worth a notification: something happened. Goal progress alone is not news. */
    val hasNews: Boolean get() = newGames > 0 || wishlistHits.isNotEmpty() || downloads > 0
}

/**
 * The weekly digest (7.0): an opt-in notification that sums up the last seven days — new games in
 * the sources for the consoles you have games for, wishlist games that turned up, how far your
 * collection goals are, what you downloaded. This is the pure part: the schedule rule and the
 * summary builder. Pure JVM for the tests.
 */
object WeeklyDigest {

    const val PERIOD_DAYS = 7
    const val PERIOD_MS = PERIOD_DAYS * 24L * 3_600_000
    /** The job may run in the last day of each week. */
    const val FLEX_MS = 24L * 3_600_000
    /** Never twice within this gap, whatever the scheduler does. */
    const val MIN_GAP_MS = 5L * 24 * 3_600_000

    const val TOP_CONSOLES = 3
    const val TOP_GOALS = 3
    const val MAX_WISHES_SHOWN = 3

    /** Due when nothing was sent yet or the last one is at least [MIN_GAP_MS] old (a clock set back counts as due). */
    fun isDue(now: Long, last: Long): Boolean = last <= 0 || now < last || now - last >= MIN_GAP_MS

    /** Start of the week the digest covers. */
    fun since(now: Long): Long = now - PERIOD_MS

    /**
     * The consoles of [consoleIds] that have at least one game in the on-disk index [ownedKeys]
     * (`scope|name` as [LibraryKeys] makes them). Files loose in the download root cannot be
     * attributed to a console and do not count.
     */
    fun consolesWithGames(consoleIds: Collection<String>, ownedKeys: Set<String>): Set<String> {
        if (ownedKeys.isEmpty()) return emptySet()
        val scopes = HashSet<String>()
        for (key in ownedKeys) scopes += key.substringBefore('|')
        return consoleIds.filterTo(HashSet()) { id -> LibraryKeys.scopesFor(id).any { it.isNotEmpty() && it in scopes } }
    }

    /** Wishes that one of [files] is a match for: every word of the title, and the console when the wish names one. */
    fun wishlistHits(wishes: List<DigestWish>, files: List<DigestFile>): List<String> =
        wishes.filter { wish ->
            wish.title.isNotBlank() && files.any { file ->
                (wish.consoleId == null || wish.consoleId == file.consoleId) && GameTitleCleaner.containsAllWords(wish.title, file.fileName)
            }
        }.map { it.title.trim() }.distinctBy { it.lowercase(Locale.ROOT) }

    /**
     * Builds the summary. [newFiles] are the files found in the last week in any console;
     * [ownedConsoles] narrows the "new games" count to the consoles you have games for, while the
     * wishlist is matched against all of them (a wanted game is news wherever it turns up).
     * [downloads] is the statistics log.
     */
    fun build(
        newFiles: List<DigestFile>,
        ownedConsoles: Set<String>,
        wishes: List<DigestWish>,
        goals: List<DigestGoal>,
        downloads: List<DownloadLogEntry>,
        now: Long
    ): DigestSummary {
        val perConsole = HashMap<String, MutableSet<String>>()
        for (file in newFiles) {
            if (file.consoleId !in ownedConsoles) continue
            val key = CollectionGoals.gameKey(file.fileName)
            if (key.isNotEmpty()) perConsole.getOrPut(file.consoleId) { HashSet() } += key
        }
        val top = perConsole.map { (id, games) -> DigestConsole(id, games.size) }
            .sortedWith(compareByDescending<DigestConsole> { it.count }.thenBy { it.consoleId })
        val since = since(now)
        val week = downloads.filter { it.at in since..now }
        val open = goals.filter { it.total > 0 && !it.complete }
            .sortedWith(compareByDescending<DigestGoal> { it.percent }.thenBy { it.name.lowercase(Locale.ROOT) })
        return DigestSummary(
            newGames = perConsole.values.sumOf { it.size },
            topConsoles = top.take(TOP_CONSOLES),
            wishlistHits = wishlistHits(wishes, newFiles),
            goals = open.take(TOP_GOALS),
            completedGoals = goals.count { it.complete },
            downloads = week.size,
            downloadedBytes = week.sumOf { it.bytes }
        )
    }
}
