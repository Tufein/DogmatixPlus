package com.cortinadev.dogmatix.util

/**
 * "Suggested for you" (3.0.0): games from the user's own sources that are not on the device yet,
 * ranked against what the player already cares about, with only data the app already has: the
 * games they starred, played (ES-DE), downloaded, put in collections or on Up next ("seeds"), the
 * genres / years / developers cached in `game_metadata`, and RetroAchievements lists cached by Best
 * games. Every suggestion carries the [Reason]s it was picked for, built from those facts only, so
 * the screen never claims more than is known. Pure JVM for the tests; the service feeds it.
 */
object ForYou {

    /** Why a game is a seed; also what "Because you …" says. */
    enum class SeedKind { PLAYED, FAVOURITE, UP_NEXT, COLLECTION, DOWNLOADED,
        /** On the device: only its series counts ("Same series as …"), see [suggest]. */
        OWNED }

    /** A game the player cares about. [title] is the readable name the reasons show. */
    data class Seed(val consoleId: String, val fileName: String, val title: String, val kind: SeedKind)

    /** One library row, light. [owned] = on the device. */
    data class Row(val id: Long, val consoleId: String, val fileName: String, val name: String, val owned: Boolean)

    /** A title on a console's RetroAchievements list: its place and whether that place rests on player counts. */
    data class Popular(val rank: Int, val byPlayers: Boolean)

    enum class ReasonKind {
        /** "Because you played / starred / … [title]": shares genres or the developer with that seed, same console. */
        BECAUSE,
        /** "Same series as [title]". */
        SERIES,
        /** High on the console's RetroAchievements list ([Reason.byPlayers] says by players or by set size). */
        POPULAR,
        /** "[genre], a genre you play a lot". */
        GENRE,
        /** Released in the decade the player's games are mostly from. */
        DECADE,
        /** "You have only [count] games for [consoleId]". */
        FEW_ON_CONSOLE
    }

    data class Reason(
        val kind: ReasonKind,
        val title: String = "",
        val seedKind: SeedKind? = null,
        val consoleId: String = "",
        val genre: String = "",
        val decade: Int = 0,
        val count: Int = 0,
        val byPlayers: Boolean = false
    )

    /** A suggestion: the row to show, the other versions of the same title (for the best pick), and why. */
    data class Suggestion(val row: Row, val versions: List<Row>, val score: Int, val reasons: List<Reason>) {
        val key: String get() = UpNext.key(row.consoleId, row.fileName)
    }

    /** How strongly each kind of seed says "I like this". */
    fun weight(kind: SeedKind): Int = when (kind) {
        SeedKind.FAVOURITE -> 4
        SeedKind.PLAYED -> 3
        SeedKind.UP_NEXT -> 3
        SeedKind.COLLECTION -> 2
        SeedKind.DOWNLOADED -> 1
        SeedKind.OWNED -> 1
    }

    /** Below this a game is not worth suggesting; a console or decade match alone never reaches it. */
    const val MIN_SCORE = 5
    const val MAX_PER_CONSOLE = 8
    const val MAX_PER_SERIES = 3
    /** Versions kept per title for the version pick. */
    private const val MAX_VERSIONS = 8
    /** A console counts as "few owned" with 1 to this many games on the device and at least [FEW_MIN_LISTED] in the sources. */
    const val FEW_OWNED_MAX = 3
    const val FEW_MIN_LISTED = 20
    private const val FEW_BONUS = 2

    // ---- Titles ----------------------------------------------------------------------------------

    private val weakWords = setOf("the", "a", "an", "of", "and", "in", "to", "for", "on", "vs", "edition", "version", "collection", "game")
    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    /** The series words of a cleaned title, as [SimilarGames.seriesWords] makes them (without cleaning twice). */
    fun seriesWords(cleanTitle: String): List<String> =
        cleanTitle.lowercase().split(splitter)
            .filter { it.length > 1 && it !in weakWords && !it.all { c -> c.isDigit() } && !(it.length <= 4 && it.all { c -> c in "ivxlc" }) }

    /**
     * The series a title belongs to: its first two series words ("super mario"), or a single long
     * word ("tetris"); null when the title says too little to tell.
     */
    fun seriesKey(words: List<String>): String? = when {
        words.size >= 2 -> "${words[0]} ${words[1]}"
        words.size == 1 && words[0].length >= 4 -> words[0]
        else -> null
    }

    /** The same key as [LibraryDiscovery.lookupKey], from a title that is already cleaned. */
    fun lookupKey(consoleId: String, cleanTitle: String): String =
        "${consoleId.substringAfter("_", consoleId).lowercase()}|${cleanTitle.lowercase()}"

    private fun titleKey(consoleId: String, cleanTitle: String) = "$consoleId|${cleanTitle.lowercase()}"

    // ---- Taste -----------------------------------------------------------------------------------

    private class SeedInfo(val seed: Seed, val clean: String, val words: List<String>, val meta: LibraryDiscovery.Meta?) {
        val weight = weight(seed.kind)
    }

    /** What the seeds say about the player: the genres, consoles and decade they lean to. */
    class Taste internal constructor(
        val topGenres: List<String>,
        val consoleWeight: Map<String, Int>,
        val topDecade: Int?
    )

    private fun taste(seeds: List<SeedInfo>): Taste {
        val genres = HashMap<String, Int>()
        val consoles = HashMap<String, Int>()
        val decades = HashMap<Int, Int>()
        val decadeSeeds = HashMap<Int, Int>()
        for (s in seeds) {
            consoles.merge(s.seed.consoleId, s.weight, Int::plus)
            s.meta?.genres?.forEach { genres.merge(it, s.weight, Int::plus) }
            s.meta?.year?.let { y ->
                val d = LibraryDiscovery.decadeOf(y)
                decades.merge(d, s.weight, Int::plus)
                decadeSeeds.merge(d, 1, Int::plus)
            }
        }
        // A genre "played a lot" needs more than one starred game behind it.
        val top = genres.entries.filter { it.value >= 6 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(3).map { it.key }
        val decade = decades.entries.filter { (decadeSeeds[it.key] ?: 0) >= 3 }
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { -it.key })?.key
        return Taste(top, consoles, decade)
    }

    // ---- Ranking ---------------------------------------------------------------------------------

    private class Acc(var best: Row, val versions: MutableList<Row>, val score: Int, val reasons: List<Reason>, val series: String?)

    /**
     * The best [limit] suggestions from [rows] (any order; the caller bounds how many it reads).
     * Games on the device count as seeds for their series only, so a sequel of an owned game is
     * found without telling the player they "played" it.
     *
     * A row is left out when it is on the device, when another version of the same title is (on the
     * same console), when it is a seed's own title, when [excluded] holds its key or [hiddenConsoles]
     * its console. [meta] gives the cached details of a (console, cleaned title); [popular] holds,
     * per console, RetroAchievements titles by [BestGames.titleKey]. At most [MAX_PER_CONSOLE] games per
     * console and [MAX_PER_SERIES] per series, so one big family does not fill the list.
     */
    fun suggest(
        seeds: List<Seed>,
        rows: List<Row>,
        meta: (consoleId: String, lookupKey: String) -> LibraryDiscovery.Meta?,
        popular: Map<String, Map<String, Popular>> = emptyMap(),
        excluded: Set<String> = emptySet(),
        hiddenConsoles: Set<String> = emptySet(),
        limit: Int = 30,
        checkActive: () -> Unit = {}
    ): List<Suggestion> {
        val infos = seeds.filter { it.kind != SeedKind.OWNED }.distinctBy { UpNext.key(it.consoleId, it.fileName) }.map { s ->
            val clean = GameTitleCleaner.clean(s.title.ifBlank { s.fileName })
            SeedInfo(s, clean, seriesWords(clean), meta(s.consoleId, lookupKey(s.consoleId, clean)))
        }
        val taste = taste(infos)
        val cleans = arrayOfNulls<String>(rows.size)
        fun cleanOf(i: Int): String = cleans[i] ?: GameTitleCleaner.clean(rows[i].name.ifBlank { rows[i].fileName }).also { cleans[i] = it }
        val maxConsoleWeight = taste.consoleWeight.values.maxOrNull() ?: 0
        val seedTitles = infos.mapTo(HashSet()) { titleKey(it.seed.consoleId, it.clean) }

        // Best seed per series key, per (console, genre) and per (console, developer).
        fun better(a: SeedInfo?, b: SeedInfo) = if (a == null || b.weight > a.weight) b else a
        val bySeries = HashMap<String, SeedInfo>()
        val byGenre = HashMap<String, SeedInfo>()
        val byDeveloper = HashMap<String, SeedInfo>()
        for (s in infos) {
            seriesKey(s.words)?.let { bySeries[it] = better(bySeries[it], s) }
            s.meta?.genres?.forEach { g -> val k = "${s.seed.consoleId}|$g"; byGenre[k] = better(byGenre[k], s) }
            s.meta?.developer?.lowercase()?.takeIf { it.isNotBlank() }?.let { d -> val k = "${s.seed.consoleId}|$d"; byDeveloper[k] = better(byDeveloper[k], s) }
        }
        rows.forEachIndexed { i, row ->
            if (i % 2000 == 1999) checkActive()
            if (!row.owned || row.consoleId in hiddenConsoles) return@forEachIndexed
            val clean = cleanOf(i)
            val words = seriesWords(clean)
            val key = seriesKey(words) ?: return@forEachIndexed
            if (key !in bySeries) bySeries[key] = SeedInfo(Seed(row.consoleId, row.fileName, clean, SeedKind.OWNED), clean, words, null)
        }

        val ownedTitles = HashSet<String>()
        val ownedPerConsole = HashMap<String, Int>()
        val listedPerConsole = HashMap<String, Int>()
        val groups = HashMap<String, Acc>()
        val skipped = HashSet<String>() // titles already scored too low
        for ((i, row) in rows.withIndex()) {
            if (i % 2000 == 1999) checkActive()
            if (row.consoleId in hiddenConsoles) continue
            listedPerConsole.merge(row.consoleId, 1, Int::plus)
            val clean = cleanOf(i)
            val tKey = titleKey(row.consoleId, clean)
            if (row.owned) {
                // Titles, not files: a game in several files or versions counts once.
                if (ownedTitles.add(tKey)) ownedPerConsole.merge(row.consoleId, 1, Int::plus)
                continue
            }
            if (tKey in seedTitles || UpNext.key(row.consoleId, row.fileName) in excluded || tKey in skipped) continue
            groups[tKey]?.let { acc ->
                if (acc.versions.size < MAX_VERSIONS) acc.versions += row
                continue
            }
            val words = seriesWords(clean)
            val series = seriesKey(words)
            val m = meta(row.consoleId, lookupKey(row.consoleId, clean))
            var score = 0
            val reasons = ArrayList<Reason>()

            // Same series as a seed (another title of it, not the seed itself).
            val seriesSeed = series?.let { bySeries[it] } ?: words.firstOrNull()?.let { w -> bySeries[w]?.takeIf { it.words.size == 1 } }
            if (seriesSeed != null) {
                score += 6 + minOf(seriesSeed.weight, 3)
                reasons += Reason(ReasonKind.SERIES, title = seriesSeed.clean.ifBlank { seriesSeed.seed.title }, seedKind = seriesSeed.seed.kind)
            }
            // Shares genres or the developer with a seed of the same console.
            if (m != null) {
                var because: SeedInfo? = null
                var shared = 0
                for (g in m.genres) {
                    val s = byGenre["${row.consoleId}|$g"] ?: continue
                    shared++
                    because = better(because, s)
                }
                val dev = m.developer.lowercase().takeIf { it.isNotBlank() }?.let { byDeveloper["${row.consoleId}|$it"] }
                if (shared > 0 || dev != null) {
                    score += minOf(shared, 2) * 2 + (if (dev != null) 2 else 0) + minOf((dev ?: because)!!.weight, 3) / 2
                    val who = dev ?: because!!
                    if (seriesSeed?.seed != who.seed) reasons += Reason(ReasonKind.BECAUSE, title = who.clean.ifBlank { who.seed.title }, seedKind = who.seed.kind)
                }
                val genre = m.genres.firstOrNull { it in taste.topGenres }
                if (genre != null) {
                    score += 2
                    reasons += Reason(ReasonKind.GENRE, genre = genre)
                }
                if (taste.topDecade != null && m.year != null && LibraryDiscovery.decadeOf(m.year) == taste.topDecade) {
                    score += 1
                    reasons += Reason(ReasonKind.DECADE, decade = taste.topDecade)
                }
            }
            // High on the console's RetroAchievements list.
            val pop = popular[row.consoleId]?.takeIf { it.isNotEmpty() }?.let { list ->
                BestGames.titleKey(clean).takeIf { it.isNotEmpty() }?.let { list[it] }
            }
            if (pop != null) {
                score += if (pop.rank <= 10) 4 else 3
                reasons += Reason(ReasonKind.POPULAR, consoleId = row.consoleId, byPlayers = pop.byPlayers)
            }
            // The consoles the player already plays get a small lift; never a reason on its own.
            if (maxConsoleWeight > 0) score += 2 * (taste.consoleWeight[row.consoleId] ?: 0) / maxConsoleWeight

            // Kept a little below the bar: the "few games on this console" lift is only known at the end.
            val strong = reasons.any { it.kind != ReasonKind.DECADE }
            if (!strong || score < MIN_SCORE - FEW_BONUS) { skipped += tKey; continue }
            groups[tKey] = Acc(row, arrayListOf(row), score, reasons, series)
        }

        // Consoles with only a few games on the device: a popular game there gets a nudge and says so.
        val few = listedPerConsole.filter { (id, listed) -> listed >= FEW_MIN_LISTED && (ownedPerConsole[id] ?: 0) in 1..FEW_OWNED_MAX }
        val ranked = groups.entries.asSequence()
            .filter { it.key !in ownedTitles }
            .map { (_, acc) ->
                val owned = ownedPerConsole[acc.best.consoleId] ?: 0
                if (acc.best.consoleId in few && acc.reasons.any { it.kind == ReasonKind.POPULAR }) {
                    Suggestion(acc.best, acc.versions, acc.score + FEW_BONUS, acc.reasons + Reason(ReasonKind.FEW_ON_CONSOLE, consoleId = acc.best.consoleId, count = owned)) to acc.series
                } else Suggestion(acc.best, acc.versions, acc.score, acc.reasons) to acc.series
            }
            .filter { it.first.score >= MIN_SCORE }
            .sortedWith(compareByDescending<Pair<Suggestion, String?>> { it.first.score }
                .thenBy { GameTitleCleaner.clean(it.first.row.name).lowercase() }
                .thenBy { it.first.row.consoleId })
            .toList()

        val perConsole = HashMap<String, Int>()
        val perSeries = HashMap<String, Int>()
        val out = ArrayList<Suggestion>()
        for ((s, series) in ranked) {
            if (out.size >= limit) break
            if ((perConsole[s.row.consoleId] ?: 0) >= MAX_PER_CONSOLE) continue
            if (series != null && (perSeries[series] ?: 0) >= MAX_PER_SERIES) continue
            perConsole.merge(s.row.consoleId, 1, Int::plus)
            series?.let { perSeries.merge(it, 1, Int::plus) }
            out += s.copy(reasons = order(s.reasons))
        }
        return out
    }

    /** The reasons the card shows, strongest first. */
    private fun order(reasons: List<Reason>): List<Reason> = reasons.sortedBy {
        when (it.kind) {
            ReasonKind.SERIES -> 0
            ReasonKind.BECAUSE -> 1
            ReasonKind.POPULAR -> 2
            ReasonKind.FEW_ON_CONSOLE -> 3
            ReasonKind.GENRE -> 4
            ReasonKind.DECADE -> 5
        }
    }

    /** At most [max] reasons for a card. */
    fun shown(reasons: List<Reason>, max: Int = 2): List<Reason> = reasons.take(max)
}
