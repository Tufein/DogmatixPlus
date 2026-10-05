package com.cortinadev.dogmatix.util

/** A game last saved on the server (any device), for the "Continue playing" shelf. */
data class RecentSave(
    val romId: Int,
    /** When it was saved, epoch millis. */
    val at: Long,
    /** The device or emulator that saved it ("Thor", "mGBA"); null when unknown. */
    val via: String?,
    val kind: SaveKind
)

/** A shelf entry tied to a library game: console + file name of the row, when and where it was played. */
data class ShelfEntry(val consoleId: String, val fileName: String, val at: Long, val via: String? = null)

/**
 * Picks the games of the "Continue playing" shelf on Home (5.0): the newest saves and states on the
 * RomM server across every device, one per game, or ES-DE's "last played" when RomM is not set up.
 * Pure JVM for the tests; the service maps the results to library rows.
 */
object ContinuePlaying {

    const val DEFAULT_LIMIT = 12

    /**
     * The games with the newest server saves / states, one per ROM, newest first. Entries without a
     * readable time, or missing on the server's disk, do not count.
     */
    fun recentServerSaves(entries: List<CloudSaveEntry>, limit: Int = DEFAULT_LIMIT): List<RecentSave> =
        entries.asSequence()
            .filter { !it.missingFromFs && it.updatedMillis != null }
            .groupBy { it.romId }
            .map { (romId, saves) ->
                val newest = saves.maxWith(compareBy<CloudSaveEntry> { it.updatedMillis ?: Long.MIN_VALUE }.thenBy { it.id })
                // The device / emulator of the newest one that says (a state often lacks what its save has).
                val via = newest.via ?: saves.sortedByDescending { it.updatedMillis }.firstNotNullOfOrNull { it.via }
                RecentSave(romId, newest.updatedMillis ?: 0L, via, newest.kind)
            }
            .sortedWith(compareByDescending<RecentSave> { it.at }.thenBy { it.romId })
            .take(limit.coerceAtLeast(0))

    /** RomM ids → the `consoleId|name` keys of the library games they are (one platform can serve several consoles). */
    fun <T> keysByRomId(games: Map<String, T>, romIdOf: (T) -> Int): Map<Int, List<String>> {
        val out = HashMap<Int, MutableList<String>>()
        games.forEach { (key, game) -> out.getOrPut(romIdOf(game)) { mutableListOf() } += key }
        out.values.forEach { it.sort() }
        return out
    }

    /** `nintendo_gba|pokemon emerald (usa)` → (`nintendo_gba`, `pokemon emerald (usa)`); null when it is not a key. */
    fun splitKey(key: String): Pair<String, String>? {
        val bar = key.indexOf('|')
        if (bar <= 0 || bar == key.lastIndex) return null
        return key.substring(0, bar) to key.substring(bar + 1)
    }

    /**
     * The library row a key stands for among [candidates] (console id + file name of each row),
     * or null. When several rows are the same game (two sources), the first one wins.
     */
    fun <R> rowForKey(key: String, candidates: List<R>, consoleOf: (R) -> String, fileNameOf: (R) -> String): R? =
        candidates.firstOrNull { RommMarks.key(consoleOf(it), fileNameOf(it)) == key }

    /** ES-DE's most recently played games, newest first, one entry per system + file. */
    fun recentPlays(plays: List<EsdePlay>, limit: Int = DEFAULT_LIMIT): List<EsdePlay> =
        plays.filter { (it.lastPlayed ?: 0L) > 0L && it.path.isNotBlank() }
            .sortedByDescending { it.lastPlayed }
            .distinctBy { it.system.lowercase() + "|" + playFileName(it).lowercase() }
            .take(limit.coerceAtLeast(0))

    /** `./sub/Game (USA).zip` → `Game (USA).zip`. */
    fun playFileName(play: EsdePlay): String = play.path.replace('\\', '/').substringAfterLast('/').trim()

    /**
     * The library row an ES-DE play stands for among [candidates]: same file name on a console whose
     * folder name is the ES-DE system ([systemMatches]); else same name without extension; else, when
     * only one row has that exact name, that one.
     */
    fun <R> rowForPlay(
        play: EsdePlay,
        candidates: List<R>,
        consoleOf: (R) -> String,
        fileNameOf: (R) -> String,
        systemMatches: (consoleId: String, system: String) -> Boolean
    ): R? {
        val name = playFileName(play)
        if (name.isEmpty()) return null
        val onSystem = candidates.filter { systemMatches(consoleOf(it), play.system) }
        onSystem.firstOrNull { fileNameOf(it).equals(name, ignoreCase = true) }?.let { return it }
        val stem = LibraryKeys.baseName(name.lowercase())
        onSystem.firstOrNull { LibraryKeys.baseName(fileNameOf(it).lowercase()) == stem }?.let { return it }
        return candidates.filter { fileNameOf(it).equals(name, ignoreCase = true) }.singleOrNull()
    }

    /** Shelf entries in order, each library game once, at most [limit]. */
    fun dedupe(entries: List<ShelfEntry>, limit: Int = DEFAULT_LIMIT): List<ShelfEntry> =
        entries.distinctBy { RommMarks.key(it.consoleId, it.fileName) }.take(limit.coerceAtLeast(0))
}
