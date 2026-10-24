package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Where the user stands in a RetroAchievements game, by their highest award: [BEATEN] (RA's beaten
 * award, softcore or hardcore, but not yet mastered), [MASTERED] (every achievement: RA's "mastered"
 * in hardcore, and its softcore "completed", which counts here too) or [IN_PROGRESS] (some achievements
 * earned and no award yet). A game the user has not touched has no status at all.
 */
enum class RaStatus { IN_PROGRESS, BEATEN, MASTERED }

/** The Library's RetroAchievements filter; [ALL] does not narrow the list. */
enum class RaStatusFilter {
    ALL, BEATEN, MASTERED, IN_PROGRESS;

    val isActive: Boolean get() = this != ALL

    /** Whether a game with [status] (null = unknown / untouched) stays in the list. */
    fun matches(status: RaStatus?): Boolean = when (this) {
        ALL -> true
        BEATEN -> status == RaStatus.BEATEN
        MASTERED -> status == RaStatus.MASTERED
        IN_PROGRESS -> status == RaStatus.IN_PROGRESS
    }
}

/** The user's award per RA game id as last fetched, and when. */
data class RaAwards(val byGame: Map<Int, RaAwardEntry> = emptyMap(), val updatedAt: Long = 0L) {
    fun statusOf(gameId: Int?): RaStatus? = gameId?.let { RaStatuses.statusOf(byGame[it]) }
    val isEmpty: Boolean get() = byGame.isEmpty()
}

/**
 * Rules for the RetroAchievements status badges and filters: the status of an award entry, merging
 * the pages of RA's completion list, the paging plan and the cache file. Pure JVM for the tests. The
 * list is fetched in a few big pages, never per game, so the library can show a status on every row
 * without hitting RA's rate limits.
 */
object RaStatuses {

    /** A user with more RA games than this is cut off (6 pages of 500); keeps one refresh to a few calls. */
    const val MAX_PAGES = 6

    /** How long a fetched list is trusted before the Library asks again (RA asks API users to be gentle). */
    const val TTL_MS = 30L * 60_000

    fun statusOf(entry: RaAwardEntry?): RaStatus? {
        entry ?: return null
        return when (entry.award) {
            RaAward.MASTERED, RaAward.COMPLETED -> RaStatus.MASTERED
            RaAward.BEATEN, RaAward.BEATEN_SOFTCORE -> RaStatus.BEATEN
            RaAward.NONE -> if (entry.earned > 0 || entry.earnedHardcore > 0) RaStatus.IN_PROGRESS else null
        }
    }

    /** The status for an award and an earned count read from one game's own progress (the game page, no batch list). */
    fun statusOf(award: RaAward, earned: Int): RaStatus? = statusOf(RaAwardEntry(0, award, 0, earned, 0))

    /** The better of two entries for the same game (higher award, then more achievements). */
    private fun better(a: RaAwardEntry, b: RaAwardEntry): RaAwardEntry = when {
        a.award.ordinal != b.award.ordinal -> if (a.award.ordinal > b.award.ordinal) a else b
        a.earned != b.earned -> if (a.earned > b.earned) a else b
        else -> a
    }

    /** All pages as one map by game id; a game listed twice keeps its better entry. */
    fun merge(pages: List<RaCompletionPage>): Map<Int, RaAwardEntry> {
        val out = LinkedHashMap<Int, RaAwardEntry>()
        pages.forEach { page -> page.entries.forEach { e -> out[e.gameId] = out[e.gameId]?.let { better(it, e) } ?: e } }
        return out
    }

    /**
     * The offset of the next page to ask for after [page] was read at [offset] with [requested] rows
     * wanted, or null when the list is complete (a short page, [total] reached) or [pagesRead] hit the cap.
     */
    fun nextOffset(offset: Int, requested: Int, page: RaCompletionPage, pagesRead: Int): Int? {
        if (pagesRead >= MAX_PAGES || page.rows <= 0 || page.rows < requested) return null
        val next = offset + page.rows
        return next.takeIf { it < page.total }
    }

    /** Whether the list in hand is fresh enough not to ask RA again. */
    fun isFresh(awards: RaAwards, now: Long): Boolean = awards.updatedAt > 0 && now - awards.updatedAt in 0 until TTL_MS

    /** How many games of [awards] are at each status (for statistics). */
    fun counts(awards: RaAwards): Map<RaStatus, Int> =
        awards.byGame.values.mapNotNull(::statusOf).groupingBy { it }.eachCount()

    // ---- Cache file ----------------------------------------------------------------------------

    fun toJson(awards: RaAwards): String = JsonObject().apply {
        addProperty("at", awards.updatedAt)
        add("games", JsonArray().apply {
            awards.byGame.values.sortedBy { it.gameId }.forEach { e ->
                add(JsonObject().apply {
                    addProperty("id", e.gameId)
                    addProperty("a", e.award.name)
                    addProperty("t", e.total)
                    addProperty("e", e.earned)
                    addProperty("h", e.earnedHardcore)
                })
            }
        })
    }.toString()

    /** Reads [toJson]'s format; a broken file is an empty list that counts as never fetched. */
    fun fromJson(json: String?): RaAwards = runCatching {
        val root = JsonParser.parseString(json ?: "{}").asJsonObject
        val map = LinkedHashMap<Int, RaAwardEntry>()
        root.getAsJsonArray("games")?.forEach { el ->
            runCatching {
                val o = el.asJsonObject
                val id = o.get("id").asInt.takeIf { it > 0 } ?: return@runCatching
                val award = RaAward.entries.firstOrNull { it.name == o.get("a")?.asString } ?: RaAward.NONE
                map[id] = RaAwardEntry(id, award, o.get("t")?.asInt ?: 0, o.get("e")?.asInt ?: 0, o.get("h")?.asInt ?: 0)
            }
        }
        RaAwards(map, root.get("at")?.asLong ?: 0L)
    }.getOrDefault(RaAwards())
}
