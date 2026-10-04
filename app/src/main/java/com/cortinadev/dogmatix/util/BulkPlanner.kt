package com.cortinadev.dogmatix.util

/** One library row as the bulk download sees it. */
data class BulkCandidate(
    val id: Long,
    val consoleId: String,
    /** Cleaned title, for grouping the versions of one game. */
    val titleKey: String,
    val fileName: String,
    val size: Long,
    val tags: List<String>,
    val owned: Boolean,
    val downloading: Boolean
)

/** What "Download all" would do with the rows the filters show. */
data class BulkPlan(
    val chosen: List<BulkCandidate>,
    val totalBytes: Long,
    val skippedOwned: Int,
    val skippedActive: Int,
    /** Other versions of a game left out because only the best one is taken. */
    val skippedVersions: Int,
    /** Free space where the downloads go, or null when unknown. */
    val freeBytes: Long?
) {
    /** False when the downloads would not fit (with a 2 % margin); unknown space counts as enough. */
    val fits: Boolean get() = freeBytes == null || totalBytes <= freeBytes - freeBytes / 50

    /** How much is missing to fit (0 when it fits or the free space is unknown). */
    val shortBytes: Long get() = if (freeBytes == null || fits) 0L else totalBytes - (freeBytes - freeBytes / 50)

    /** Per console: (console id, games, bytes), biggest first. */
    val perConsole: List<Triple<String, Int, Long>> get() =
        chosen.groupBy { it.consoleId }.map { (c, rows) -> Triple(c, rows.size, rows.sumOf { it.size }) }.sortedByDescending { it.third }
}

object BulkPlanner {

    /**
     * Most games one "Download all" may queue: a whole console set (a few thousand games), but not
     * every console at once by a mistaken tap. The dialog shows the count and size before anything starts.
     */
    const val MAX_FILES = 3000

    /**
     * Skips what is on disk or already downloading; with [bestOnly], keeps one version per game
     * (per console and cleaned title), the one [VersionPicker] ranks first.
     */
    fun plan(
        candidates: List<BulkCandidate>,
        bestOnly: Boolean,
        regionPreference: List<String>,
        languages: Set<String>,
        freeBytes: Long?
    ): BulkPlan {
        val owned = candidates.count { it.owned }
        val active = candidates.count { !it.owned && it.downloading }
        val open = candidates.filter { !it.owned && !it.downloading }
        val chosen = if (!bestOnly) open else open.groupBy { it.consoleId to it.titleKey }.values.mapNotNull { group ->
            if (group.size == 1) group.first() else {
                val best = VersionPicker.best(group.map { VersionPicker.Candidate(it.fileName, it.fileName, it.tags, it.size) }, regionPreference, languages)
                group.firstOrNull { it.fileName == best?.id } ?: group.first()
            }
        }
        val limited = chosen.take(MAX_FILES)
        return BulkPlan(limited, limited.sumOf { it.size }, owned, active, open.size - chosen.size, freeBytes)
    }
}
