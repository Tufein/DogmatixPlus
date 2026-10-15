package com.cortinadev.dogmatix.util

/**
 * 3.5 "Games on your home screen": the pure parts of a game shortcut, both the icon pinned from a
 * game page and the recently played games in the long-press menu of the app icon.
 *
 * A shortcut carries one library key, `<consoleId>|<fileName>` (the library row, the same pair the
 * game page's route uses). Tapping it looks that key up in the library again and starts what the
 * library row says; nothing else from the intent (no path, no URI) is ever launched.
 */
object GameShortcuts {
    /** Shortcut ids are this prefix plus the library key, so a game has one shortcut wherever it is shown. */
    const val ID_PREFIX = "game:"

    /** The action of a game shortcut's intent and its one extra, the library key. */
    const val ACTION_PLAY = "com.cortinadev.dogmatix.action.PLAY_GAME"
    const val EXTRA_GAME = "com.cortinadev.dogmatix.GAME"

    /** How many recently played games the icon menu lists, ahead of the quick actions. */
    const val RECENT_COUNT = 3

    /** Settings: the recently played games in the icon menu (default on). */
    const val RECENT_MENU_KEY = "pin35_recent_menu"

    /** Their backup types, for [SettingSchema]. */
    val SETTING_TYPES: Map<String, String> = mapOf(RECENT_MENU_KEY to "b")

    // A console id is a short slug and a file name at most 255 bytes: these caps also keep the shortcut id (prefix + key) short.
    private const val MAX_CONSOLE = 64
    private const val MAX_FILE = 255

    /** One game of the library, as a shortcut names it. */
    data class Key(val consoleId: String, val fileName: String) {
        /** What the intent carries. */
        val value: String get() = "$consoleId|$fileName"
        val shortcutId: String get() = ID_PREFIX + value
    }

    /** The key of a library row, or null when it cannot be carried safely (a separator in the console id, control characters). */
    fun key(consoleId: String, fileName: String): Key? {
        if (consoleId.isBlank() || consoleId.length > MAX_CONSOLE || '|' in consoleId || consoleId.any { it.isISOControl() }) return null
        if (fileName.isBlank() || fileName.length > MAX_FILE || fileName.any { it.isISOControl() }) return null
        return Key(consoleId, fileName)
    }

    /** A key written by [Key.value]: the console id ends at the first `|` (a file name may hold one). */
    fun parse(value: String?): Key? {
        val text = value ?: return null
        val bar = text.indexOf('|')
        if (bar <= 0) return null
        return key(text.substring(0, bar), text.substring(bar + 1))
    }

    /** The key of a shortcut id, or null for another kind of shortcut. */
    fun parseId(id: String?): Key? = id?.takeIf { it.startsWith(ID_PREFIX) }?.let { parse(it.removePrefix(ID_PREFIX)) }

    /** The game an intent asks to start, or null for any other intent or a malformed key. */
    fun fromIntent(action: String?, extra: String?): Key? = if (action == ACTION_PLAY) parse(extra) else null

    /** The clean title a shortcut shows (tags, extension and disc number off), falling back to the bare name. */
    fun label(name: String): String {
        val readable = VersionPicker.readable(name).trim()
        return GameTitleCleaner.clean(readable).ifBlank { LibraryKeys.baseName(readable) }.ifBlank { name }
    }

    /** One game that was started from Dogmatix+, as the action history has it: the console and the file that was opened. */
    data class RecentPlay(val consoleId: String, val fileName: String, val at: Long)

    /**
     * The games [profileId] started, newest first, at most [limit]. A game counts once: the same
     * file started again, or another file with the same name (its `.cue` and its `.m3u`, a `.zip`
     * and what it held), is the same game. [entries] are the action history, oldest first.
     */
    fun recentPlays(entries: List<ActionEntry>, profileId: String, limit: Int): List<RecentPlay> {
        if (limit <= 0) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<RecentPlay>()
        for (entry in entries.sortedByDescending { it.at }) {
            if (entry.kind != ActionKind.PLAYED || entry.profileId != profileId) continue
            val consoleId = entry.consoleId?.takeIf { it.isNotBlank() } ?: continue
            val fileName = entry.fileName?.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(RommMarks.key(consoleId, fileName))) continue
            out += RecentPlay(consoleId, fileName, entry.at)
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * The library file names of [consoleId] (among [candidates]) a started file belongs to, best
     * first: the same name, the same name in other case, then the same name without extension
     * (the game was started from what its archive held, or from its playlist). Empty when none.
     */
    fun libraryMatches(consoleId: String, played: String, candidates: Collection<String>): List<String> {
        val exact = candidates.filter { it == played }
        val otherCase = candidates.filter { it != played && it.equals(played, ignoreCase = true) }
        val key = RommMarks.key(consoleId, played)
        val sameName = candidates.filter { !it.equals(played, ignoreCase = true) && RommMarks.key(consoleId, it) == key }
        return (exact + otherCase + sameName).distinct()
    }

    /**
     * The game shortcuts among [ids] that stand for [consoleId] / [fileName], the game that just
     * went (or came back): the same library key, or the same name without extension in any case
     * (a removal names the library row, a shortcut may name another file of the same game).
     */
    fun affected(ids: Collection<String>, consoleId: String, fileName: String): List<String> {
        val wanted = RommMarks.key(consoleId, fileName)
        return ids.filter { id -> parseId(id)?.let { RommMarks.key(it.consoleId, it.fileName) == wanted } == true }.distinct()
    }

    /**
     * The games the icon menu shows: [games] in their order without repeats, at most [count];
     * nothing while the setting is off ([show]).
     */
    fun <T> menuGames(games: List<T>, show: Boolean, idOf: (T) -> String, count: Int = RECENT_COUNT): List<T> =
        if (!show) emptyList() else games.distinctBy(idOf).take(count.coerceAtLeast(0))

    /**
     * The square in the middle of a [width] × [height] cover (`left, top, right, bottom`): what a
     * home-screen icon shows of it.
     */
    fun squareCrop(width: Int, height: Int): IntArray {
        val side = minOf(width, height).coerceAtLeast(0)
        val left = ((width - side) / 2).coerceAtLeast(0)
        val top = ((height - side) / 2).coerceAtLeast(0)
        return intArrayOf(left, top, left + side, top + side)
    }
}
