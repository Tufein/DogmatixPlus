package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Where a game of the "Up next" list stands. */
enum class UpNextState { QUEUED, STARTED, FINISHED;
    companion object { fun fromName(name: String?): UpNextState = entries.firstOrNull { it.name == name } ?: QUEUED }
}

/**
 * One game the player means to play next (3.0.0). Keyed by console + file name like favourites, so
 * it survives the re-index of a rescan; [title] is kept so a game a source dropped can still be named.
 */
data class UpNextEntry(
    val consoleId: String,
    val fileName: String,
    val title: String,
    val addedAt: Long,
    val state: UpNextState = UpNextState.QUEUED,
    /** When [state] last changed (started or finished); 0 while queued. */
    val changedAt: Long = 0L
) {
    val key: String get() = UpNext.key(consoleId, fileName)
}

/**
 * The rules of the "Up next" list: the player's own order, games being played and finished ones
 * (those sink to the end), no duplicates, a bounded size, and the file format (one small JSON array
 * per profile). Pure JVM for the tests; the service only stores what these return.
 */
object UpNext {

    /** More than anybody plans ahead; keeps the file and the screen small. */
    const val MAX_ENTRIES = 200

    fun key(consoleId: String, fileName: String): String = "$consoleId|$fileName"

    /**
     * The list as it is shown: the games still to play in the player's order, then the finished ones,
     * most recently finished first.
     */
    fun normalized(list: List<UpNextEntry>): List<UpNextEntry> {
        val open = list.filter { it.state != UpNextState.FINISHED }
        val done = list.filter { it.state == UpNextState.FINISHED }.sortedByDescending { it.changedAt }
        return open + done
    }

    /**
     * Adds a game after the last one still to play. Nothing changes when it is already on the list
     * (whatever its state) or when the list is full of games still to play; a full list drops its
     * oldest finished game to make room.
     */
    fun add(list: List<UpNextEntry>, entry: UpNextEntry): List<UpNextEntry> {
        if (list.any { it.key == entry.key }) return list
        var base = normalized(list)
        if (base.size >= MAX_ENTRIES) {
            val oldestDone = base.filter { it.state == UpNextState.FINISHED }.minByOrNull { it.changedAt } ?: return list
            base = base - oldestDone
        }
        val open = base.filter { it.state != UpNextState.FINISHED }
        return normalized(open + entry.copy(state = UpNextState.QUEUED, changedAt = 0L) + base.drop(open.size))
    }

    fun remove(list: List<UpNextEntry>, key: String): List<UpNextEntry> = list.filterNot { it.key == key }

    /** Removes every finished game. */
    fun clearFinished(list: List<UpNextEntry>): List<UpNextEntry> = list.filter { it.state != UpNextState.FINISHED }

    /**
     * Moves the game [key] [delta] places (-1 = up) among the games still to play. A finished game
     * does not move; nor does one already at the edge.
     */
    fun move(list: List<UpNextEntry>, key: String, delta: Int): List<UpNextEntry> {
        val ordered = normalized(list)
        val open = ordered.filter { it.state != UpNextState.FINISHED }.toMutableList()
        val from = open.indexOfFirst { it.key == key }
        if (from < 0) return ordered
        val to = (from + delta).coerceIn(0, open.size - 1)
        if (to == from) return ordered
        val item = open.removeAt(from)
        open.add(to, item)
        return open + ordered.drop(open.size)
    }

    /** Whether [key] can move [delta] places (the screen greys the button out otherwise). */
    fun canMove(list: List<UpNextEntry>, key: String, delta: Int): Boolean {
        val open = normalized(list).filter { it.state != UpNextState.FINISHED }
        val from = open.indexOfFirst { it.key == key }
        return from >= 0 && from + delta in open.indices
    }

    /**
     * Marks [key] as [state] at [now]. Starting a game moves it to the top of the games to play (it is
     * what the player is on now); a game played again after finishing goes back among the open ones.
     */
    fun setState(list: List<UpNextEntry>, key: String, state: UpNextState, now: Long): List<UpNextEntry> {
        val current = list.firstOrNull { it.key == key } ?: return list
        if (current.state == state) return normalized(list)
        val updated = current.copy(state = state, changedAt = if (state == UpNextState.QUEUED) 0L else now)
        val rest = normalized(list.filterNot { it.key == key })
        return when (state) {
            UpNextState.STARTED -> normalized(listOf(updated) + rest)
            UpNextState.QUEUED -> {
                val open = rest.filter { it.state != UpNextState.FINISHED }
                normalized(open + updated + rest.drop(open.size))
            }
            UpNextState.FINISHED -> normalized(rest + updated)
        }
    }

    /** The first [max] games still to play (the Home shelf). */
    fun open(list: List<UpNextEntry>, max: Int = Int.MAX_VALUE): List<UpNextEntry> =
        normalized(list).filter { it.state != UpNextState.FINISHED }.take(max)

    // ---- File format ---------------------------------------------------------------------------

    fun toJson(list: List<UpNextEntry>): String = JsonArray().apply {
        normalized(list).forEach { e ->
            add(JsonObject().apply {
                addProperty("console", e.consoleId)
                addProperty("file", e.fileName)
                addProperty("title", e.title)
                addProperty("added", e.addedAt)
                addProperty("state", e.state.name)
                addProperty("changed", e.changedAt)
            })
        }
    }.toString()

    /** Reads [toJson]'s format; broken entries are skipped, a broken file is an empty list. */
    fun fromJson(json: String?): List<UpNextEntry> = runCatching {
        val seen = HashSet<String>()
        JsonParser.parseString(json ?: "[]").asJsonArray.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                val console = o.get("console")?.asString?.takeIf { it.isNotBlank() } ?: return@runCatching null
                val file = o.get("file")?.asString?.takeIf { it.isNotBlank() } ?: return@runCatching null
                UpNextEntry(
                    console, file, o.get("title")?.asString.orEmpty(), o.get("added")?.asLong ?: 0L,
                    UpNextState.fromName(o.get("state")?.asString), o.get("changed")?.asLong ?: 0L
                ).takeIf { seen.add(it.key) }
            }.getOrNull()
        }.take(MAX_ENTRIES).let(::normalized)
    }.getOrDefault(emptyList())

    /**
     * The file name a profile's list is kept under: the id itself when it is plain, else a safe form
     * with a hash so two odd ids never share a file. "" (no profile) has its own file.
     */
    fun fileNameFor(profileId: String): String {
        if (profileId.isEmpty()) return "upnext_default.json"
        val safe = profileId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(48)
        return if (safe == profileId) "upnext_p_$safe.json" else "upnext_p_${safe}_${Integer.toHexString(profileId.hashCode())}.json"
    }
}
