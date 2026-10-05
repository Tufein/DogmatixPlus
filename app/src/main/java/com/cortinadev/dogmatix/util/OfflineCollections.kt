package com.cortinadev.dogmatix.util

/**
 * 7.0 "Keep a collection on this device": the decisions, without any Android type.
 *
 * A collection switched on is looked at after every scan: the games in it that the library lists
 * (a source or the RomM server) but the device does not have yet are queued for download, a few at
 * a time ([DEFAULT_CAP]) and only as far as the free space reaches. Nothing is ever removed on its
 * own: games this feature fetched that have left their collection are only *listed*
 * ([review]), for the user to remove after a confirmation that names the files.
 */
object OfflineCollections {

    const val DEFAULT_CAP = 25
    const val MIN_CAP = 5
    const val MAX_CAP = 200
    const val CAP_STEP = 5

    /** Room kept free on top of what the games need, like the storage advisor does ([StorageInsights.shortfall]). */
    const val SPACE_MARGIN = 100L * 1024 * 1024

    /** A game as a collection keeps it: console + file name (see CollectionsRepository). */
    data class Game(val consoleId: String, val fileName: String)

    /** A collection that is switched on, with its games in the order they should be fetched. */
    data class Kept(val id: Long, val name: String, val games: List<Game>)

    /** What the library lists for a game: its size (0 = unknown) and whether it is an archive that needs room to unpack. */
    data class Offer(val size: Long, val extractable: Boolean)

    /** A game to queue, and the collections (switched on) that hold it. */
    data class Pick(val game: Game, val collectionIds: List<Long>, val size: Long, val need: Long)

    /** What happened to a game of a collection in one plan. */
    enum class Outcome { ON_DEVICE, QUEUED, NOT_IN_LIBRARY, FETCH, NO_SPACE, OVER_CAP }

    /** The games of one collection by outcome; [fetch] is what this run queues for it. */
    data class Tally(
        val collectionId: Long,
        val total: Int,
        val onDevice: Int,
        val queued: Int,
        val notInLibrary: Int,
        val fetch: Int,
        val noSpace: Int,
        val overCap: Int
    ) {
        /** Games still to come to the device (queued, about to be, or held back). */
        val missing: Int get() = queued + fetch + noSpace + overCap
    }

    data class Plan(
        val picks: List<Pick>,
        val tallies: List<Tally>,
        /** Games held back by the per-run cap; the next run takes them. */
        val overCap: Int,
        /** Games that do not fit in the free space right now. */
        val noSpace: Int,
        /** What the games that did not fit would need on disk. */
        val missingBytes: Long
    )

    /** Bytes a game needs on disk: the file, and again to unpack an archive. */
    fun need(offer: Offer): Long = offer.size.coerceAtLeast(0) * (if (offer.extractable) 2 else 1)

    /**
     * Which games to queue. [offers] holds the games the library lists (absent = not in the library
     * any more); [onDevice] and [queued] say which ones are on disk or already in the download
     * queue. A game in several collections is fetched once. At most [cap] games are picked; the
     * rest wait for the next run. [freeBytes] is the space where downloads land (null = unknown, no
     * check); [reserveBytes] is what must stay free anyway (the user's minimum plus what the queue
     * already needs). A game that does not fit is skipped, a smaller one after it may still go.
     */
    fun plan(
        kept: List<Kept>,
        offers: Map<Game, Offer>,
        onDevice: (Game) -> Boolean,
        queued: (Game) -> Boolean,
        freeBytes: Long?,
        reserveBytes: Long,
        cap: Int
    ): Plan {
        val limit = cap.coerceAtLeast(0)
        val outcomes = LinkedHashMap<Game, Outcome>()
        val holders = HashMap<Game, MutableList<Long>>()
        val picks = ArrayList<Pick>()
        var budget = freeBytes?.let { it - reserveBytes.coerceAtLeast(0) - SPACE_MARGIN }
        var missingBytes = 0L
        for (c in kept) for (game in c.games) {
            val ids = holders.getOrPut(game) { ArrayList() }
            if (c.id !in ids) ids += c.id
            if (game in outcomes) continue
            val offer = offers[game]
            outcomes[game] = when {
                onDevice(game) -> Outcome.ON_DEVICE
                queued(game) -> Outcome.QUEUED
                offer == null -> Outcome.NOT_IN_LIBRARY
                picks.size >= limit -> Outcome.OVER_CAP
                else -> {
                    val need = need(offer)
                    if (budget != null && need > budget) {
                        missingBytes += need
                        Outcome.NO_SPACE
                    } else {
                        budget = budget?.minus(need)
                        picks += Pick(game, emptyList(), offer.size.coerceAtLeast(0), need)
                        Outcome.FETCH
                    }
                }
            }
        }
        val withHolders = picks.map { it.copy(collectionIds = holders.getValue(it.game)) }
        val tallies = kept.map { c ->
            val mine = c.games.distinct().map { outcomes.getValue(it) }
            Tally(
                c.id, mine.size,
                mine.count { it == Outcome.ON_DEVICE }, mine.count { it == Outcome.QUEUED }, mine.count { it == Outcome.NOT_IN_LIBRARY },
                mine.count { it == Outcome.FETCH }, mine.count { it == Outcome.NO_SPACE }, mine.count { it == Outcome.OVER_CAP }
            )
        }
        return Plan(withHolders, tallies, outcomes.values.count { it == Outcome.OVER_CAP }, outcomes.values.count { it == Outcome.NO_SPACE }, missingBytes)
    }

    // ---- what this feature fetched, and what left its collection ----

    /** A game this feature queued because [collectionId] held it. */
    data class Fetched(val collectionId: Long, val game: Game)

    /** A fetched game that no collection switched on holds any more and that is still on the device. */
    data class Stale(val game: Game, val collectionIds: Set<Long>)

    data class Review(
        val stale: List<Stale>,
        /** Entries to drop from the record: the game left its collection and is not on the device (or coming) any more. */
        val forget: List<Fetched>
    )

    /**
     * What to offer for removal. [contents] is every collection as it is now (by id; a deleted one is
     * absent) and [kept] the ids switched on. A fetched game is stale when no collection that is
     * switched on holds it, it is on the device and not in the download queue. One that left its
     * collection and is not on the device is only forgotten. Nothing here removes anything.
     */
    fun review(
        fetched: Collection<Fetched>,
        contents: Map<Long, Set<Game>>,
        kept: Set<Long>,
        onDevice: (Game) -> Boolean,
        queued: (Game) -> Boolean
    ): Review {
        val wanted = HashSet<Game>()
        kept.forEach { id -> contents[id]?.let(wanted::addAll) }
        val stale = ArrayList<Stale>()
        val forget = ArrayList<Fetched>()
        fetched.groupBy { it.game }.forEach { (game, entries) ->
            when {
                game in wanted -> Unit
                onDevice(game) -> stale += Stale(game, entries.map { it.collectionId }.toSet())
                queued(game) -> Unit
                else -> forget += entries
            }
        }
        return Review(stale.sortedWith(compareBy({ it.game.consoleId }, { it.game.fileName.lowercase() })), forget)
    }

    /**
     * The files on disk that make up [game]: entries of its console (or loose in the download
     * folder) with a file of the same name, or the same name without its extension. This is the
     * list the confirmation shows, and exactly what a removal deletes.
     */
    fun entriesOf(game: Game, entries: List<GameEntry>): List<GameEntry> {
        val name = FileParsingUtils.decodeUrlEncodedFileName(game.fileName).lowercase()
        val base = LibraryKeys.baseName(name)
        return entries.filter { e ->
            (e.consoleId == game.consoleId || (e.consoleId == null && e.scope.isEmpty())) &&
                (e.baseName.lowercase() == base || e.files.any { f ->
                    val n = f.name.lowercase()
                    n == name || LibraryKeys.baseName(n) == base
                })
        }
    }

    // ---- small persisted records (one line each; pure text so it is testable on the JVM) ----

    /** How the last run went, for the status line. */
    data class RunInfo(
        val at: Long,
        val queued: Int,
        val noSpace: Int,
        val overCap: Int,
        val missingBytes: Long
    ) {
        fun encode(): String = "$at|$queued|$noSpace|$overCap|$missingBytes"

        companion object {
            fun decode(text: String?): RunInfo? {
                val p = text?.split('|') ?: return null
                if (p.size != 5) return null
                return RunInfo(p[0].toLongOrNull() ?: return null, p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null,
                    p[3].toIntOrNull() ?: return null, p[4].toLongOrNull() ?: return null)
            }
        }
    }

    fun encode(f: Fetched): String = "${f.collectionId}|${f.game.consoleId}|${f.game.fileName}"

    /** The inverse of [encode]; null for a line that does not parse. */
    fun decodeFetched(line: String): Fetched? {
        val p = line.split('|', limit = 3)
        if (p.size != 3 || p[1].isEmpty() || p[2].isEmpty()) return null
        return Fetched(p[0].toLongOrNull() ?: return null, Game(p[1], p[2]))
    }

    fun clampCap(cap: Int): Int = cap.coerceIn(MIN_CAP, MAX_CAP)
}
