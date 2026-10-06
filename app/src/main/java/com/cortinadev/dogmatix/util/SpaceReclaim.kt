package com.cortinadev.dogmatix.util

import java.text.Normalizer
import java.util.Locale

/** Why a game should be kept: something in the app says it matters to the user. */
enum class SpaceProtection { FAVOURITE, COLLECTION, SAVE, ACHIEVEMENT }

/** What the play data says about a game on disk. */
enum class SpacePlay {
    /** ES-DE has a play record for it. */
    PLAYED,
    /** ES-DE records plays for this console and has none for this game. */
    NEVER,
    /** There is no play data (ES-DE not set up, or nothing recorded for this console): only size and age can rank it. */
    UNKNOWN
}

/**
 * Everything the "Free up space" tool knows besides the files. The sets hold `consoleId|name`
 * keys as [SpaceReclaim.key] makes them (file name without extension, lower-case), so favourites,
 * collections, RomM saves and RetroAchievements marks line up with files on disk.
 */
data class SpaceFacts(
    /** ES-DE's play records; null when ES-DE is not set up (no play data at all). */
    val plays: List<EsdePlay>? = null,
    val favourites: Set<String> = emptySet(),
    val collections: Set<String> = emptySet(),
    /** Games with a save or state on the RomM server. */
    val saves: Set<String> = emptySet(),
    /** Games the user earned a RetroAchievements achievement in (as far as the app has seen). */
    val achievements: Set<String> = emptySet(),
    /** When a game reached the device by a download of this app (epoch millis), for files without a modified time. */
    val downloadedAt: Map<String, Long> = emptyMap()
)

/** One game on disk the tool may offer to remove. */
data class SpaceCandidate(
    val entry: GameEntry,
    val consoleId: String,
    val play: SpacePlay,
    /** When the game's files were last written, else when the app downloaded it; 0 when unknown. */
    val addedAt: Long = 0L,
    val protections: Set<SpaceProtection> = emptySet()
) {
    val id: String get() = entry.id
    val title: String get() = entry.baseName
    val bytes: Long get() = entry.size
    val fileCount: Int get() = entry.files.size
    val isProtected: Boolean get() = protections.isNotEmpty()
}

/** The ranked list plus what was left out of it. */
data class SpaceReport(
    /** Never played (or without play data), biggest first. */
    val candidates: List<SpaceCandidate>,
    /** Games ES-DE says were played: not offered. */
    val playedCount: Int = 0,
    val playedBytes: Long = 0L,
    /** ES-DE play records were found at all; false means every game is ranked by size and age only. */
    val hasPlayData: Boolean = false
) {
    val unknownCount: Int get() = candidates.count { it.play == SpacePlay.UNKNOWN }
    val bytes: Long get() = candidates.sumOf { it.bytes }
}

/** One console of the filter: how many candidate games it has and how much room they take. */
data class SpaceConsole(val consoleId: String, val games: Int, val bytes: Long)

/** A pick of games and what it frees. [reached] is false when everything allowed was picked and still fell short. */
data class SpaceSelection(val ids: Set<String>, val bytes: Long, val reached: Boolean)

/**
 * The maths of the "Free up space" tool: which games on disk were never played, in what order to
 * offer them, which ones are protected, and which biggest games free a given amount. Pure JVM for
 * the tests; the service gathers the facts and removes the files.
 *
 * A game counts as played when ES-DE has a record for it. ES-DE only keeps records of games it
 * launched, so "never played" is only said for a console ES-DE has other records for; for any
 * other console (or without ES-DE) the game is [SpacePlay.UNKNOWN] and ranked by size and age.
 */
object SpaceReclaim {

    const val GB: Long = 1024L * 1024L * 1024L

    /** The amounts the "free N GB" stepper walks through. */
    val FREE_STEPS_GB: List<Int> = listOf(1, 2, 5, 10, 20, 50, 100, 200)
    const val DEFAULT_FREE_GB = 5

    /** `consoleId|name` for a file name: decoded, lower-case, without extension (like the RomM and library keys). */
    fun key(consoleId: String, fileName: String): String =
        consoleId + "|" + LibraryKeys.baseName(FileParsingUtils.decodeUrlEncodedFileName(fileName).lowercase())

    /** The same key for a name that is already without extension. */
    fun stemKey(consoleId: String, stem: String): String = consoleId + "|" + stem.lowercase()

    /** Every key under which [entry] can be known: its files' names and its own (folder or title) name. */
    fun keysOf(entry: GameEntry): Set<String> {
        val console = entry.consoleId ?: return emptySet()
        val out = HashSet<String>()
        entry.files.forEach { out += key(console, it.name) }
        out += stemKey(console, entry.baseName)
        return out
    }

    /**
     * The games to offer from [entries], ranked: never played ones and ones without play data,
     * biggest first. Only plain games inside a console folder qualify ([DuplicateFinder.isPlainGame],
     * no playlists), so nothing that merely sits in the library folders is ever offered.
     */
    fun build(entries: List<GameEntry>, facts: SpaceFacts): SpaceReport {
        val plays = PlayMatcher(facts.plays)
        val candidates = ArrayList<SpaceCandidate>()
        var playedCount = 0
        var playedBytes = 0L
        for (entry in entries) {
            val console = entry.consoleId ?: continue
            if (!DuplicateFinder.isPlainGame(entry)) continue
            if (entry.files.all { it.name.substringAfterLast('.', "").equals("m3u", ignoreCase = true) }) continue
            val play = plays.match(entry, console)
            if (play == SpacePlay.PLAYED) {
                playedCount++
                playedBytes += entry.size
                continue
            }
            val keys = keysOf(entry)
            val protections = buildSet {
                if (keys.any { it in facts.favourites }) add(SpaceProtection.FAVOURITE)
                if (keys.any { it in facts.collections }) add(SpaceProtection.COLLECTION)
                if (keys.any { it in facts.saves }) add(SpaceProtection.SAVE)
                if (keys.any { it in facts.achievements }) add(SpaceProtection.ACHIEVEMENT)
            }
            val modified = entry.files.maxOfOrNull { it.lastModified } ?: 0L
            val added = if (modified > 0L) modified else keys.mapNotNull { facts.downloadedAt[it] }.maxOrNull() ?: 0L
            candidates += SpaceCandidate(entry, console, play, added, protections)
        }
        return SpaceReport(rank(candidates), playedCount, playedBytes, hasPlayData = !facts.plays.isNullOrEmpty())
    }

    /** Biggest first; of two equal sizes the older file (unknown age last), then by title. */
    fun rank(candidates: List<SpaceCandidate>): List<SpaceCandidate> =
        candidates.sortedWith(
            compareByDescending<SpaceCandidate> { it.bytes }
                .thenBy { if (it.addedAt > 0L) it.addedAt else Long.MAX_VALUE }
                .thenBy { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.id }
        )

    /** The candidates of one console, or all of them for null; the ranking is kept. */
    fun forConsole(candidates: List<SpaceCandidate>, consoleId: String?): List<SpaceCandidate> =
        if (consoleId == null) candidates else candidates.filter { it.consoleId == consoleId }

    /** The consoles of the filter, the one with the most room to win first. */
    fun consoles(candidates: List<SpaceCandidate>): List<SpaceConsole> =
        candidates.groupBy { it.consoleId }
            .map { (id, games) -> SpaceConsole(id, games.size, games.sumOf { it.bytes }) }
            .sortedWith(compareByDescending<SpaceConsole> { it.bytes }.thenBy { it.consoleId })

    fun totalBytes(candidates: List<SpaceCandidate>, ids: Set<String>): Long =
        candidates.filter { it.id in ids }.sumOf { it.bytes }

    /** Every game that is not protected: what "select all" ticks. */
    fun unprotectedIds(candidates: List<SpaceCandidate>): Set<String> =
        candidates.filter { !it.isProtected }.mapTo(LinkedHashSet()) { it.id }

    /**
     * The biggest unprotected games from [candidates] (already ranked) that free at least
     * [targetBytes]: biggest first, but the last pick is the smallest game that still gets there,
     * so 5 GB is not overshot by a 4 GB game taken only because it was next. Games without a known
     * size (0 bytes) free nothing and are never picked. When everything allowed is not enough all
     * of it is picked and [SpaceSelection.reached] is false.
     */
    fun selectBiggest(candidates: List<SpaceCandidate>, targetBytes: Long): SpaceSelection {
        if (targetBytes <= 0L) return SpaceSelection(emptySet(), 0L, true)
        val pool = candidates.filter { !it.isProtected && it.bytes > 0L }
        val picked = LinkedHashSet<String>()
        var freed = 0L
        for ((index, game) in pool.withIndex()) {
            val remaining = targetBytes - freed
            if (game.bytes >= remaining) {
                // Biggest first, so the smallest one that still covers what is left is the last that does.
                val last = pool.drop(index).lastOrNull { it.bytes >= remaining } ?: game
                picked += last.id
                freed += last.bytes
                return SpaceSelection(picked, freed, true)
            }
            picked += game.id
            freed += game.bytes
        }
        return SpaceSelection(picked, freed, freed >= targetBytes)
    }

    /** The next amount of the stepper in [delta]'s direction (the nearest step on that side), staying within [FREE_STEPS_GB]. */
    fun stepGb(current: Int, delta: Int): Int = when {
        delta > 0 -> FREE_STEPS_GB.firstOrNull { it > current } ?: FREE_STEPS_GB.last()
        delta < 0 -> FREE_STEPS_GB.lastOrNull { it < current } ?: FREE_STEPS_GB.first()
        else -> current
    }

    /** A start value for the stepper: the nearest step at or below a fifth of the room on offer, at least 1. */
    fun suggestedFreeGb(totalBytes: Long): Int {
        val fifth = totalBytes / 5 / GB
        return FREE_STEPS_GB.lastOrNull { it <= fifth } ?: FREE_STEPS_GB.first()
    }

    /** The title a removed game is put on the wishlist under: tags, regions, discs and versions dropped. */
    fun wishTitle(candidate: SpaceCandidate): String =
        GameTitleCleaner.clean(candidate.title + ".x").ifBlank { candidate.title }.trim()

    // ---- Matching ES-DE's records to games on disk ---------------------------------------------

    /**
     * Whether ES-DE has a record of a game. A record matches by file name (a game's own files) or by
     * file name without extension; a per-game folder only by the folder's name. A game with a disc
     * tag also matches by title, because ES-DE keeps one record for the `.m3u` of all its discs.
     */
    private class PlayMatcher(plays: List<EsdePlay>?) {
        private class Index(
            val names: Set<String>,
            val stems: Set<String>,
            val folders: Set<String>,
            val titles: Set<String>
        )

        private val systems: Set<String> = plays?.mapTo(HashSet()) { it.system }.orEmpty()
        private val played = plays.orEmpty().filter { it.playCount > 0 || (it.lastPlayed ?: 0L) > 0L }
        private val bySystem = played.groupBy { it.system }
        private val indexes = HashMap<String, Index?>()

        /** The records of every ES-DE system that is [consoleId]'s folder; null when ES-DE has none for it. */
        private fun indexFor(consoleId: String): Index? = indexes.getOrPut(consoleId) {
            val own = systems.filter { ConsoleFolderAliases.matches(consoleId, it) }
            if (own.isEmpty()) return@getOrPut null
            val records = own.flatMap { bySystem[it].orEmpty() }
            val files = records.map { fileNameOf(it).lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
            Index(
                names = files.toSet(),
                stems = files.map { LibraryKeys.baseName(it) }.toSet(),
                folders = records.mapNotNull { folderOf(it) }.toSet(),
                titles = files.map { titleOf(LibraryKeys.baseName(it)) }.filter { it.isNotEmpty() }.toSet()
            )
        }

        fun match(entry: GameEntry, consoleId: String): SpacePlay {
            val index = indexFor(consoleId) ?: return SpacePlay.UNKNOWN
            val stem = entry.baseName.lowercase(Locale.ROOT)
            // The files of a per-game folder have generic names (disc.gdi, track01.bin): only the folder says which game it is.
            val own = if (entry.isFolderGame) {
                stem in index.stems || stem in index.folders || folderName(entry) in index.folders
            } else {
                val names = entry.files.map { it.name.lowercase(Locale.ROOT) }
                names.any { it in index.names || LibraryKeys.baseName(it) in index.stems } || stem in index.stems
            }
            val hit = own || (hasDiscTag(entry.baseName) && titleOf(entry.baseName.lowercase(Locale.ROOT)) in index.titles)
            return if (hit) SpacePlay.PLAYED else SpacePlay.NEVER
        }

        private fun fileNameOf(play: EsdePlay): String = ContinuePlaying.playFileName(play)

        /** The folder an ES-DE path sits in (`./Crazy Taxi/disc.gdi` → `crazy taxi`); null for a file straight in the system folder. */
        private fun folderOf(play: EsdePlay): String? {
            val parts = play.path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            return if (parts.size >= 2) parts[parts.size - 2].trim().lowercase(Locale.ROOT) else null
        }

        private fun folderName(entry: GameEntry): String = entry.folder.substringAfterLast('/').trim().lowercase(Locale.ROOT)

        private fun hasDiscTag(name: String): Boolean = DISC_TAG.containsMatchIn(name)

        private fun titleOf(name: String): String =
            Normalizer.normalize(GameTitleCleaner.clean("$name.x"), Normalizer.Form.NFD)
                .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
    }

    private val DISC_TAG = Regex("(?i)\\b(disc|disk|cd|dvd|gd|side)\\s*(\\d+|[a-z])\\b")
}
