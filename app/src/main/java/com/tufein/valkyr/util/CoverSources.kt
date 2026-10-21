package com.tufein.valkyr.util

/**
 * The cover source chain (4.3.0): which sources are asked for a game's cover, in which order, and
 * what a lookup leaves behind in the `covers` table. Pure JVM for the tests; [com.tufein.valkyr.data.service.CoverRepository]
 * does the asking.
 *
 * - RomM always comes first (a game on the user's own server has the user's own cover); the
 *   other sources follow in the user's order, each with its own switch.
 * - A miss is stored with the sources that really answered "no cover" (`miss:libretro+screenscraper`).
 *   A source that was offline, rate limited, paused for the day or not set up yet is left out, so
 *   it is asked again as soon as it can answer, while the others are not asked again until the
 *   miss gets old ([CoverSourcePolicy.MISS_TTL_MS]).
 * - A cover from a source the user switched off is looked up again.
 */
object CoverSources {

    enum class Source(val key: String) {
        ROMM("romm"),
        LIBRETRO("libretro"),
        SCREENSCRAPER("screenscraper"),
        /** 4.4.0: TheGamesDB box art (free API key). */
        THEGAMESDB("thegamesdb"),
        /** 4.4.0: SteamGridDB community artwork (free API key). */
        STEAMGRIDDB("steamgriddb"),
        /** 4.4.0: IGDB covers (free Twitch client id and secret). */
        IGDB("igdb"),
        /** The image a RAWG / TheGamesDB lookup already stored: a Room read, no request. */
        METADATA("metadata");

        /** Costs no request: a miss from it alone (offline) is not worth storing. */
        val local: Boolean get() = this == METADATA

        /** Needs a key of the user's own before it can be asked (ScreenScraper has its developer login, the others an API key). */
        val needsKey: Boolean get() = this == SCREENSCRAPER || this == THEGAMESDB || this == STEAMGRIDDB || this == IGDB

        companion object {
            fun of(key: String?): Source? = entries.firstOrNull { it.key == key?.trim() }
        }
    }

    /**
     * The order of the sources after RomM, until the user changes it: the free sources first, then
     * the ones that need a key (4.4.0: TheGamesDB, SteamGridDB, IGDB after ScreenScraper), the cached metadata image last.
     */
    val DEFAULT_ORDER = listOf(Source.LIBRETRO, Source.SCREENSCRAPER, Source.THEGAMESDB, Source.STEAMGRIDDB, Source.IGDB, Source.METADATA)

    /**
     * The 4.4.0 sources start switched off: until the user has saved a setting for the switches, they
     * are off, and saving their first key switches the source on (see [CoverApis.Keys.newlyConfigured]).
     */
    val OFF_UNTIL_SET_UP: Set<Source> = setOf(Source.THEGAMESDB, Source.STEAMGRIDDB, Source.IGDB)

    const val ORDER_KEY = "covers43_order"
    const val DISABLED_KEY = "covers43_disabled"
    const val NON_BOX_KEY = "covers43_non_box"

    /** Type tags of the settings (see [SettingSchema]); the ScreenScraper login is in [ScreenScraper.SETTING_TYPES]. */
    val SETTING_TYPES: Map<String, String> = mapOf(ORDER_KEY to "s", DISABLED_KEY to "ss", NON_BOX_KEY to "b")

    /** The saved order: unknown names dropped, RomM never in it, a source missing from it put back at its default place. */
    fun orderOf(text: String?): List<Source> {
        val out = text.orEmpty().split(',').mapNotNull { Source.of(it) }.filter { it != Source.ROMM }.distinct().toMutableList()
        DEFAULT_ORDER.forEachIndexed { index, source -> if (source !in out) out.add(index.coerceAtMost(out.size), source) }
        return out
    }

    fun encodeOrder(order: List<Source>): String = order.filter { it != Source.ROMM }.distinct().joinToString(",") { it.key }

    /**
     * The switched-off sources. Before the user ever touched a switch ([keys] null) the 4.4.0 sources
     * are off unless they are set up with a key of the user's own ([withKey]): read at use time, so a
     * restore without switches leaves a source with a kept key on.
     */
    fun disabledOf(keys: Set<String>?, withKey: Set<Source> = emptySet()): Set<Source> =
        if (keys == null) OFF_UNTIL_SET_UP - withKey else keys.mapNotNull { Source.of(it) }.toSet()

    /** [order] with [source] moved [delta] places (works on the value, so a double press never moves the wrong entry). */
    fun move(order: List<Source>, source: Source, delta: Int): List<Source> {
        val from = order.indexOf(source)
        if (from < 0) return order
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (to == from) return order
        return order.toMutableList().apply { removeAt(from); add(to, source) }
    }

    /** What the user chose: the order after RomM, the sources switched off, and whether title screens and snaps may stand in. */
    data class Chain(
        val order: List<Source> = DEFAULT_ORDER,
        val disabled: Set<Source> = emptySet(),
        val nonBoxArt: Boolean = false
    ) {
        /** RomM, then the user's order, without the sources switched off. */
        val active: List<Source> get() = (listOf(Source.ROMM) + order).distinct().filter { it !in disabled }
        fun enabled(source: Source): Boolean = source !in disabled
    }

    /** One source's answer for one game. */
    sealed interface Answer {
        data class Found(val url: String) : Answer
        /** The source answered and has no cover for this game. */
        data object Miss : Answer
        /** No answer: offline, rate limited, paused for the day, server busy, login refused. Never stored as a miss. */
        data object Unavailable : Answer
    }

    /** A row of the `covers` table. */
    data class Cached(val url: String, val source: String, val fetchedAt: Long)

    sealed interface Plan {
        /** A cover is known and still trusted. */
        data class Use(val url: String) : Plan
        /** A recent miss and nothing new to ask. */
        data object KnownMiss : Plan
        /**
         * Ask [sources] in this order. [alreadyTried] are the sources a fresh miss already holds,
         * [since] its time (kept, so asking a new source does not extend the old answers).
         */
        data class Ask(val sources: List<Source>, val alreadyTried: Set<Source> = emptySet(), val since: Long? = null) : Plan
    }

    const val MISS_PREFIX = "miss:"

    /** The `source` column of a miss: which sources answered "none". */
    fun missRecord(tried: Set<Source>): String =
        MISS_PREFIX + tried.filter { it != Source.ROMM }.sortedBy { it.ordinal }.joinToString("+") { it.key }

    /**
     * The sources a stored miss holds an answer from, or null for a found cover. A miss from before
     * 4.3.0 ("") was asked of libretro-thumbnails and the metadata cache.
     */
    fun triedBy(source: String): Set<Source>? = when {
        source.isEmpty() -> setOf(Source.LIBRETRO, Source.METADATA)
        source.startsWith(MISS_PREFIX) -> source.removePrefix(MISS_PREFIX).split('+').mapNotNull { Source.of(it) }.toSet()
        else -> null
    }

    /**
     * What to do for a game that is not on the RomM server: use [cached], or ask which sources.
     * [usable] are the sources that can answer right now (ScreenScraper only with its credentials
     * and while it is not paused). [unconfigured] are the sources that are not set up at all.
     */
    fun plan(chain: Chain, usable: Set<Source>, cached: Cached?, now: Long, unconfigured: Set<Source> = emptySet()): Plan {
        val candidates = chain.active.filter { it != Source.ROMM && it in usable }
        if (cached == null) return Plan.Ask(candidates)
        if (cached.url.isNotEmpty()) {
            val from = Source.of(cached.source)
            // A cover from a source the user switched off is replaced; an unknown tag (older rows) is kept.
            // So is one from a source that is no longer set up (ScreenScraper without developer credentials).
            val wanted = from == null || (chain.enabled(from) && from !in unconfigured)
            return if (wanted && CoverSourcePolicy.isFresh(cached.url, cached.fetchedAt, now)) Plan.Use(cached.url) else Plan.Ask(candidates)
        }
        if (!CoverSourcePolicy.isFresh("", cached.fetchedAt, now)) return Plan.Ask(candidates)
        val tried = triedBy(cached.source).orEmpty()
        // Only the sources new to this game (switched on, set up or back from a pause since).
        val ask = candidates.filter { it !in tried }
        if (ask.isEmpty()) return Plan.KnownMiss
        return Plan.Ask(ask, tried, cached.fetchedAt)
    }

    /**
     * The outcome of asking: the URL (or null), what to store ([record] null = store nothing) and
     * whether the answer may be kept in memory for the rest of the run.
     */
    data class Settled(val url: String?, val record: String?, val fetchedAt: Long, val remember: Boolean)

    fun settle(answers: List<Pair<Source, Answer>>, ask: Plan.Ask, now: Long): Settled {
        answers.firstOrNull { it.second is Answer.Found }?.let { (source, answer) ->
            return Settled((answer as Answer.Found).url, source.key, now, remember = true)
        }
        val definite = answers.filter { it.second == Answer.Miss }.map { it.first }.toSet()
        val unavailable = answers.any { it.second == Answer.Unavailable }
        val tried = ask.alreadyTried + definite
        // Only a source that costs a request is worth remembering; with none (offline) nothing is stored.
        val record = if (tried.any { !it.local }) missRecord(tried) else null
        return Settled(null, record, ask.since ?: now, remember = !unavailable)
    }

    /** Hits, misses and errors of one source on one day (the status line in Cover sources). */
    data class Tally(val day: Long = 0, val hits: Int = 0, val misses: Int = 0, val errors: Int = 0) {
        enum class Kind { HIT, MISS, ERROR }

        /** [kind] counted on [today]; a tally of an earlier day starts again from zero. */
        fun count(today: Long, kind: Kind): Tally {
            val base = if (day == today) this else Tally(today)
            return when (kind) {
                Kind.HIT -> base.copy(hits = base.hits + 1)
                Kind.MISS -> base.copy(misses = base.misses + 1)
                Kind.ERROR -> base.copy(errors = base.errors + 1)
            }
        }

        /** What counts for [today]: nothing when the tally is from another day. */
        fun on(today: Long): Tally = if (day == today) this else Tally(today)

        fun encode(): String = "$day:$hits:$misses:$errors"

        companion object {
            fun decode(text: String?): Tally {
                val parts = text.orEmpty().split(':').map { it.toLongOrNull() }
                if (parts.size != 4 || parts.any { it == null || it < 0 }) return Tally()
                return Tally(parts[0]!!, parts[1]!!.toInt(), parts[2]!!.toInt(), parts[3]!!.toInt())
            }
        }
    }

    fun answerKind(answer: Answer): Tally.Kind = when (answer) {
        is Answer.Found -> Tally.Kind.HIT
        Answer.Miss -> Tally.Kind.MISS
        Answer.Unavailable -> Tally.Kind.ERROR
    }
}
