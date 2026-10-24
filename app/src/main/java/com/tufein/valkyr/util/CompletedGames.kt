package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A game the player marked as completed (5.5). Keyed by console + file name like favourites, so a rescan keeps it. */
data class CompletedEntry(val consoleId: String, val fileName: String, val markedAt: Long) {
    val key: String get() = CompletedGames.key(consoleId, fileName)
}

/** The Library's "Completed" filter. */
enum class CompletedFilter {
    ALL, COMPLETED, NOT_COMPLETED;

    val isActive: Boolean get() = this != ALL

    /** Whether a game whose completed state is [completed] stays in the list. */
    fun matches(completed: Boolean): Boolean = when (this) {
        ALL -> true
        COMPLETED -> completed
        NOT_COMPLETED -> !completed
    }
}

/**
 * The player's own "completed" flag per game (a manual mark, independent of RetroAchievements), one
 * small JSON array per profile. Pure JVM for the tests; [com.tufein.valkyr.data.service.CompletedService]
 * only stores what these return.
 */
object CompletedGames {

    /** More than any library holds; keeps the file small and the list bounded. */
    const val MAX_ENTRIES = 20_000

    fun key(consoleId: String, fileName: String): String = "$consoleId|$fileName"

    /** Marks a game; nothing changes when it is already marked (its first date stays). */
    fun mark(list: List<CompletedEntry>, entry: CompletedEntry): List<CompletedEntry> {
        if (list.any { it.key == entry.key }) return list
        if (list.size >= MAX_ENTRIES) return list
        return list + entry
    }

    fun unmark(list: List<CompletedEntry>, key: String): List<CompletedEntry> = list.filterNot { it.key == key }

    /** The flag flipped: marked games lose it, others get it. */
    fun toggle(list: List<CompletedEntry>, entry: CompletedEntry): List<CompletedEntry> =
        if (list.any { it.key == entry.key }) unmark(list, entry.key) else mark(list, entry)

    fun toJson(list: List<CompletedEntry>): String = JsonArray().apply {
        list.sortedWith(compareBy({ it.consoleId }, { it.fileName })).forEach { e ->
            add(JsonObject().apply {
                addProperty("console", e.consoleId)
                addProperty("file", e.fileName)
                addProperty("at", e.markedAt)
            })
        }
    }.toString()

    /** Reads [toJson]'s format; broken entries are skipped, a broken file is an empty list. */
    fun fromJson(json: String?): List<CompletedEntry> = runCatching {
        val seen = HashSet<String>()
        JsonParser.parseString(json ?: "[]").asJsonArray.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                val console = o.get("console")?.asString?.takeIf { it.isNotBlank() } ?: return@runCatching null
                val file = o.get("file")?.asString?.takeIf { it.isNotBlank() } ?: return@runCatching null
                CompletedEntry(console, file, o.get("at")?.asLong ?: 0L).takeIf { seen.add(it.key) }
            }.getOrNull()
        }.take(MAX_ENTRIES)
    }.getOrDefault(emptyList())

    /**
     * The file name a profile's flags are kept under: the id itself when it is plain, else a safe form
     * with a hash so two odd ids never share a file. "" (no profile) has its own file.
     */
    fun fileNameFor(profileId: String): String {
        if (profileId.isEmpty()) return "completed_default.json"
        val safe = profileId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(48)
        return if (safe == profileId) "completed_p_$safe.json" else "completed_p_${safe}_${Integer.toHexString(profileId.hashCode())}.json"
    }
}
