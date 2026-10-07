package com.cortinadev.dogmatix.util

import java.util.Locale

/**
 * Ranks the versions of one game by a [VersionPreference] and says why. Every automatic "best
 * version" in the app goes through here (via [VersionPicker] and [VersionPreference.pick]), so the
 * game page's explanation and the pickers can never disagree. It builds on the parsers the app
 * already has ([BetterVersions.parse] for revisions, `[!]` and bad-dump flags, [VersionPicker.tagsOf]
 * for the tags, [TagClassifier] for regions and languages). Every reason is data (kind, weight,
 * params), never a sentence: the screens turn them into text in the user's language. Pure JVM.
 *
 * Only the tags of a name count (the `(…)` / `[…]` groups and the tags from the database), never
 * the title itself: "World Heroes (Japan)" is a Japanese release, not a world one.
 *
 * Points: a region's place in the preference is worth (n - place) x 10; a language the user reads
 * 5 to 9 (always less than one region step: 8 for the first choice, one less per later choice, one
 * more when several match), a release only in languages the user does not read -15; a verified
 * `[!]` dump 5, each step up in revision 1 (at most 5); every mark of a release to avoid -100.
 * An untagged world, American, British, Australian, Canadian or European release counts as
 * English, an untagged Japanese one as Japanese.
 */
object VersionCompare {

    /** One version of a game as the ranking sees it. [size] 0 = unknown; [format] is the file extension. */
    data class Version(
        val id: String,
        val name: String,
        val tags: List<String> = emptyList(),
        val size: Long = 0L,
        val format: String = ""
    )

    enum class Dump { VERIFIED, GOOD, BAD, OVERDUMP }
    enum class Release { FINAL, BETA, PROTO, DEMO }
    enum class Unofficial { HACK, TRANSLATION, UNLICENSED, ALTERNATE }

    /** What the tags say about one version. */
    data class Facts(
        /** Language codes, upper case, in tag order; the assumed one when the release has no language tag ([languagesAssumed]). */
        val languages: List<String>,
        val languagesAssumed: Boolean,
        /** Region tags ("Europe", "USA"), canonical spelling. */
        val regions: List<String>,
        /** "Rev 1" / "v1.1" as the name writes it, or null. */
        val revision: String?,
        val dump: Dump,
        val release: Release,
        val unofficial: Set<Unofficial>
    )

    enum class Category { LANGUAGE, REGION, REVISION, RELEASE, DUMP, OFFICIAL, SIZE }

    /** Params per kind are listed after the kind. */
    enum class ReasonKind(val category: Category) {
        /** Params: the matched language codes in the user's order. [Reason.position]: place of the first one (1 = first choice). */
        LANGUAGE_MATCH(Category.LANGUAGE),
        /** As [LANGUAGE_MATCH], for a language assumed from the region (no language tag). */
        LANGUAGE_ASSUMED(Category.LANGUAGE),
        /** Params: the release's languages, none of which the user reads. */
        LANGUAGE_UNREAD(Category.LANGUAGE),
        /** Nothing says which language it is in. */
        LANGUAGE_NONE(Category.LANGUAGE),
        /** Params: the matched region. [Reason.position]: its place in the user's regions. */
        REGION_MATCH(Category.REGION),
        REGION_NONE(Category.REGION),
        /** Params: its revision. */
        REVISION_NEWEST(Category.REVISION),
        /** Params: its revision, the oldest revision of the game ("" = the original, untagged release). */
        REVISION_NEWER(Category.REVISION),
        /** Params: its revision ("" = untagged), the newest revision. */
        REVISION_OLDER(Category.REVISION),
        FINAL_RELEASE(Category.RELEASE),
        /** Params: [Release] name (BETA / PROTO). */
        PRE_RELEASE(Category.RELEASE),
        DEMO(Category.RELEASE),
        DUMP_VERIFIED(Category.DUMP),
        DUMP_GOOD(Category.DUMP),
        /** Params: [Dump] name (BAD / OVERDUMP). */
        DUMP_BAD(Category.DUMP),
        OFFICIAL(Category.OFFICIAL),
        HACK(Category.OFFICIAL),
        TRANSLATION(Category.OFFICIAL),
        UNLICENSED(Category.OFFICIAL),
        ALTERNATE(Category.OFFICIAL),
        /** Params: its size in bytes. */
        SIZE_SMALLER(Category.SIZE),
        SIZE_LARGER(Category.SIZE)
    }

    /** One reason a version ranks where it does; [weight] is its share of the score (0 = informative). */
    data class Reason(val kind: ReasonKind, val weight: Int, val params: List<String> = emptyList(), val position: Int = 0) {
        val category: Category get() = kind.category
        val positive: Boolean get() = weight > 0
        val negative: Boolean get() = weight < 0
    }

    data class Ranked(val version: Version, val rank: Int, val score: Int, val reasons: List<Reason>, val facts: Facts) {
        fun weightOf(category: Category): Int = reasons.filter { it.category == category }.sumOf { it.weight }
        fun reasonOf(category: Category): Reason? =
            reasons.filter { it.category == category }.maxWithOrNull(compareBy<Reason> { kotlin.math.abs(it.weight) }.thenBy { -it.kind.ordinal })
    }

    // ---- Tag knowledge ------------------------------------------------------------------------------

    private val betaWords = listOf("beta", "proto", "prototype", "debug", "preview")
    private val demoWords = listOf("demo", "sample", "kiosk", "promo", "not for resale", "trial", "taikenban")
    private val unlicensedWords = listOf("unl", "unlicensed", "pirate", "bootleg", "homebrew", "aftermarket")
    private val translationWords = listOf("translated", "translation")
    private val badWords = listOf("overdump", "bad dump")
    private val taggedCache = HashMap<String, Regex>()
    private val translationFlag = Regex("""\[t[+-][^\]]*]""", RegexOption.IGNORE_CASE)

    private fun tagged(word: String): Regex = synchronized(taggedCache) {
        taggedCache.getOrPut(word) { Regex("""[(\[]\s*${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE) }
    }

    /** [word] as a whole tag, or as the start of a bracketed group (`(Beta 2)`). */
    private fun flagged(word: String, name: String, tagWords: Set<String>): Boolean = word in tagWords || tagged(word).containsMatchIn(name)

    private val isoLanguages: Set<String> = Locale.getISOLanguages().map { it.uppercase(Locale.ROOT) }.toSet()

    /** The language an untagged release of a region is in. */
    private val assumedLanguage = mapOf(
        "WORLD" to "EN", "USA" to "EN", "UK" to "EN", "UNITED KINGDOM" to "EN", "BRITAIN" to "EN",
        "AUSTRALIA" to "EN", "CANADA" to "EN", "EUROPE" to "EN", "JAPAN" to "JA"
    )

    private class Parsed(val version: Version, val facts: Facts, val tagWords: Set<String>, val revisionParts: List<Int>?, val revisionIsVersion: Boolean)

    private fun parse(v: Version): Parsed {
        val name = VersionPicker.readable(v.name)
        val bv = BetterVersions.parse(name)
        val allTags = (VersionPicker.tagsOf(name) + v.tags).map { it.trim() }.filter { it.isNotEmpty() }
        val tagWords = allTags.map { it.lowercase(Locale.ROOT) }.toSet()

        var dump = if (bv.verified) Dump.VERIFIED else Dump.GOOD
        val unofficial = LinkedHashSet<Unofficial>()
        when (bv.problem) {
            BetterVersions.Problem.BAD_DUMP -> dump = Dump.BAD
            BetterVersions.Problem.OVERDUMP -> dump = Dump.OVERDUMP
            BetterVersions.Problem.HACK, BetterVersions.Problem.MODIFIED -> unofficial += Unofficial.HACK
            BetterVersions.Problem.PIRATE -> unofficial += Unofficial.UNLICENSED
            else -> Unit
        }
        if (dump != Dump.BAD && dump != Dump.OVERDUMP && badWords.any { flagged(it, name, tagWords) }) dump = Dump.BAD
        if (flagged("hack", name, tagWords)) unofficial += Unofficial.HACK
        if (unlicensedWords.any { flagged(it, name, tagWords) }) unofficial += Unofficial.UNLICENSED
        if (translationWords.any { flagged(it, name, tagWords) } || translationFlag.containsMatchIn(name)) unofficial += Unofficial.TRANSLATION
        if (flagged("alt", name, tagWords)) unofficial += Unofficial.ALTERNATE

        val pre = bv.pre?.kind
        val release = when {
            flagged("proto", name, tagWords) || flagged("prototype", name, tagWords) || pre == BetterVersions.PreKind.PROTO -> Release.PROTO
            betaWords.any { flagged(it, name, tagWords) } || pre == BetterVersions.PreKind.BETA || pre == BetterVersions.PreKind.OTHER -> Release.BETA
            demoWords.any { flagged(it, name, tagWords) } ||
                pre == BetterVersions.PreKind.DEMO || pre == BetterVersions.PreKind.SAMPLE || pre == BetterVersions.PreKind.KIOSK -> Release.DEMO
            else -> Release.FINAL
        }

        val regions = allTags.filter { TagClassifier.kindOf(it) == TagClassifier.Kind.REGION }.map(VersionPreferences::prettyRegion).distinct()
        val tagged = allTags.filter {
            val u = it.uppercase(Locale.ROOT)
            (TagClassifier.kindOf(it) == TagClassifier.Kind.LANGUAGE && u in isoLanguages) || u == "JP" || u == "JPN"
        }.map(VersionPreferences::canonicalLanguage).distinct()
        val assumed = if (tagged.isEmpty()) regions.mapNotNull { assumedLanguage[it.uppercase(Locale.ROOT)] }.distinct() else emptyList()
        val revision = bv.revision
        return Parsed(
            v,
            Facts(tagged.ifEmpty { assumed }, tagged.isEmpty() && assumed.isNotEmpty(), regions, revision?.label, dump, release, unofficial),
            tagWords, revision?.parts, revision?.scheme == BetterVersions.Scheme.VERSION
        )
    }

    /** The facts of one version on their own (for preferring versions like it). */
    fun factsOf(version: Version): Facts = parse(version).facts

    // ---- Ranking ---------------------------------------------------------------------------------

    private const val PENALTY = -100
    private const val REGION_STEP = 10
    private const val LANGUAGE_POINTS = 8
    private const val UNREAD_PENALTY = -15
    private const val VERIFIED_POINTS = 5
    private const val MAX_REVISION_POINTS = 5

    private fun compareParts(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    private val partsComparator = Comparator<List<Int>> { a, b -> compareParts(a, b) }

    /** [versions] best first, each with its score, rank (1 = best) and reasons. Never empty for a non-empty input. */
    fun rank(versions: List<Version>, pref: VersionPreference): List<Ranked> {
        if (versions.isEmpty()) return emptyList()
        val parsed = versions.map(::parse)
        val wanted = VersionPreferences.cleanLanguages(pref.languages)
        val regionKeys = pref.regions.map { it.trim().lowercase(Locale.ROOT) }

        // Revisions are compared inside the group: the untagged release is the baseline.
        val versionScheme = parsed.mapNotNull { p -> p.revisionParts?.let { p.revisionIsVersion } }.let { it.isNotEmpty() && it.all { v -> v } }
        val baseline = if (versionScheme) listOf(1, 0) else listOf(0)
        val keys = parsed.map { it.revisionParts ?: baseline }
        val distinct = keys.sortedWith(partsComparator).fold(mutableListOf<List<Int>>()) { acc, k ->
            if (acc.isEmpty() || compareParts(acc.last(), k) != 0) acc += k
            acc
        }
        val newest = parsed.indices.firstOrNull { compareParts(keys[it], distinct.last()) == 0 }?.let { parsed[it].facts.revision } ?: ""
        val oldest = parsed.indices.firstOrNull { compareParts(keys[it], distinct.first()) == 0 }?.let { parsed[it].facts.revision } ?: ""

        // What the others are, so a clean version can say it beats a beta or a bad dump.
        val anyPre = parsed.any { it.facts.release == Release.BETA || it.facts.release == Release.PROTO }
        val anyBad = parsed.any { it.facts.dump == Dump.BAD || it.facts.dump == Dump.OVERDUMP }
        val anyUnofficial = parsed.any { it.facts.unofficial.isNotEmpty() || it.facts.release == Release.DEMO }
        val sizes = parsed.map { it.version.size }.filter { it > 0 }
        val minSize = sizes.minOrNull()
        val maxSize = sizes.maxOrNull()

        val scored = parsed.mapIndexed { index, p ->
            val reasons = ArrayList<Reason>()
            val f = p.facts

            // Language: a match is always worth less than one region step.
            if (wanted.isNotEmpty()) {
                val matched = wanted.withIndex().filter { (_, code) -> code in f.languages }
                reasons += when {
                    matched.isNotEmpty() -> {
                        val first = matched.first().index
                        val points = LANGUAGE_POINTS - minOf(first, 3) + if (matched.size > 1) 1 else 0
                        Reason(if (f.languagesAssumed) ReasonKind.LANGUAGE_ASSUMED else ReasonKind.LANGUAGE_MATCH, points, matched.map { it.value }, first + 1)
                    }
                    f.languages.isNotEmpty() -> Reason(ReasonKind.LANGUAGE_UNREAD, UNREAD_PENALTY, f.languages)
                    else -> Reason(ReasonKind.LANGUAGE_NONE, 0)
                }
            }

            // Region: the first of the user's regions that is one of the version's tags (never a word of the title).
            if (regionKeys.isNotEmpty()) {
                val place = regionKeys.indexOfFirst { it in p.tagWords }
                reasons += if (place >= 0) Reason(ReasonKind.REGION_MATCH, (regionKeys.size - place) * REGION_STEP, listOf(pref.regions[place].trim()), place + 1)
                else Reason(ReasonKind.REGION_NONE, 0)
            }

            // Revision.
            if (pref.preferLatestRevision && distinct.size > 1) {
                val ordinal = distinct.indexOfFirst { compareParts(it, keys[index]) == 0 }
                val label = f.revision.orEmpty()
                reasons += when {
                    ordinal == distinct.size - 1 -> Reason(ReasonKind.REVISION_NEWEST, minOf(ordinal, MAX_REVISION_POINTS), listOf(label))
                    ordinal > 0 -> Reason(ReasonKind.REVISION_NEWER, minOf(ordinal, MAX_REVISION_POINTS), listOf(label, oldest))
                    else -> Reason(ReasonKind.REVISION_OLDER, 0, listOf(label, newest))
                }
            }

            // Final or not.
            when (f.release) {
                Release.BETA, Release.PROTO -> if (pref.preferFinal) reasons += Reason(ReasonKind.PRE_RELEASE, PENALTY, listOf(f.release.name))
                Release.DEMO -> if (pref.avoidUnofficial) reasons += Reason(ReasonKind.DEMO, PENALTY)
                Release.FINAL -> if (pref.preferFinal && anyPre) reasons += Reason(ReasonKind.FINAL_RELEASE, 0)
            }

            // Dump.
            if (pref.preferVerifiedDump) {
                when (f.dump) {
                    Dump.VERIFIED -> reasons += Reason(ReasonKind.DUMP_VERIFIED, VERIFIED_POINTS)
                    Dump.GOOD -> if (anyBad) reasons += Reason(ReasonKind.DUMP_GOOD, 0)
                    Dump.BAD, Dump.OVERDUMP -> reasons += Reason(ReasonKind.DUMP_BAD, PENALTY, listOf(f.dump.name))
                }
            }

            // Hacks, translations, unlicensed and alternate releases.
            if (pref.avoidUnofficial) {
                if (f.unofficial.isEmpty()) {
                    if (anyUnofficial && f.release != Release.DEMO) reasons += Reason(ReasonKind.OFFICIAL, 0)
                } else f.unofficial.forEach {
                    val kind = when (it) {
                        Unofficial.HACK -> ReasonKind.HACK
                        Unofficial.TRANSLATION -> ReasonKind.TRANSLATION
                        Unofficial.UNLICENSED -> ReasonKind.UNLICENSED
                        Unofficial.ALTERNATE -> ReasonKind.ALTERNATE
                    }
                    reasons += Reason(kind, PENALTY)
                }
            }

            // Tie-breaker: no points.
            val size = p.version.size
            if (size > 0 && minSize != null && maxSize != null && minSize != maxSize) {
                if (pref.sizeTieBreak == SizeTieBreak.SMALLER && size == minSize) reasons += Reason(ReasonKind.SIZE_SMALLER, 0, listOf(size.toString()))
                if (pref.sizeTieBreak == SizeTieBreak.LARGER && size == maxSize) reasons += Reason(ReasonKind.SIZE_LARGER, 0, listOf(size.toString()))
            }
            Scored(p, reasons.sortedBy { it.kind.category.ordinal }, reasons.sumOf { it.weight })
        }

        val sizeOrder: Comparator<Scored> = when (pref.sizeTieBreak) {
            SizeTieBreak.NONE -> Comparator { _, _ -> 0 }
            SizeTieBreak.SMALLER -> compareBy { if (it.parsed.version.size > 0) it.parsed.version.size else Long.MAX_VALUE }
            SizeTieBreak.LARGER -> compareByDescending { it.parsed.version.size }
        }
        // A stable sort: equal versions keep their input order after the name.
        val ordered = scored.sortedWith(
            compareByDescending<Scored> { it.score }
                .then(sizeOrder)
                .thenBy { it.parsed.version.name.lowercase(Locale.ROOT) }
        )
        return ordered.mapIndexed { i, s -> Ranked(s.parsed.version, i + 1, s.score, s.reasons, s.parsed.facts) }
    }

    private class Scored(val parsed: Parsed, val reasons: List<Reason>, val score: Int)

    fun best(versions: List<Version>, pref: VersionPreference): Version? = rank(versions, pref).firstOrNull()?.version

    // ---- Why ------------------------------------------------------------------------------------

    /** One category where the winner is ahead of the loser: what each has there (null = nothing worth noting) and by how many points. */
    data class Gap(val category: Category, val winner: Reason?, val loser: Reason?, val diff: Int)

    /** Where [a] is ahead of [b], the biggest lead first. */
    fun gaps(a: Ranked, b: Ranked): List<Gap> =
        Category.entries.mapNotNull { c ->
            val diff = a.weightOf(c) - b.weightOf(c)
            if (diff > 0) Gap(c, a.reasonOf(c), b.reasonOf(c), diff) else null
        }.sortedByDescending { it.diff }

    /** Why [version] ranks below the best: its biggest gap, or, when they tie on points, the tie-breaker that decided. */
    data class WhyNot(val version: Ranked, val gap: Gap?, val tieBreak: Reason?)

    /** "[best] wins over [runnerUp] because": the [deciding] gaps (at most 3), the [tieBreak] when they tie; plus [whyNot] for every other version. */
    data class Explanation(val best: Ranked, val runnerUp: Ranked?, val deciding: List<Gap>, val tieBreak: Reason?, val whyNot: List<WhyNot>)

    private fun tieReason(best: Ranked): Reason? = best.reasons.firstOrNull { it.category == Category.SIZE }

    fun explainBest(ranked: List<Ranked>): Explanation? {
        val best = ranked.firstOrNull() ?: return null
        val others = ranked.drop(1)
        val runnerUp = others.firstOrNull()
        val deciding = runnerUp?.let { gaps(best, it).take(3) }.orEmpty()
        val tie = if (runnerUp != null && deciding.isEmpty()) tieReason(best) else null
        val whyNot = others.map { o ->
            val gap = gaps(best, o).firstOrNull()
            WhyNot(o, gap, if (gap == null) tieReason(best) else null)
        }
        return Explanation(best, runnerUp, deciding, tie, whyNot)
    }

    // ---- Side by side -----------------------------------------------------------------------------

    enum class Field { LANGUAGE, REGION, REVISION, RELEASE, DUMP, SIZE, FORMAT }
    enum class Side { A, B, NONE }

    /** One line of the side-by-side. Values are raw: language codes, region tags, a revision label, a [Release] / [Dump] / [Unofficial] name, a size in bytes, a file extension. */
    data class Line(val field: Field, val a: List<String>, val b: List<String>, val winner: Side)

    private fun side(a: Int, b: Int) = when { a > b -> Side.A; b > a -> Side.B; else -> Side.NONE }

    /** [a] against [b] (both from the same [rank] call so the revision reasons are comparable). */
    fun compareLines(a: Ranked, b: Ranked, pref: VersionPreference): List<Line> {
        fun fmt(r: Ranked) = r.version.format.trimStart('.').uppercase(Locale.ROOT).ifEmpty { r.version.name.substringAfterLast('.', "").uppercase(Locale.ROOT) }
        val sizeWinner = if (a.version.size <= 0 || b.version.size <= 0 || a.version.size == b.version.size) Side.NONE else when (pref.sizeTieBreak) {
            SizeTieBreak.NONE -> Side.NONE
            SizeTieBreak.SMALLER -> if (a.version.size < b.version.size) Side.A else Side.B
            SizeTieBreak.LARGER -> if (a.version.size > b.version.size) Side.A else Side.B
        }
        return listOf(
            Line(Field.LANGUAGE, a.facts.languages, b.facts.languages, side(a.weightOf(Category.LANGUAGE), b.weightOf(Category.LANGUAGE))),
            Line(Field.REGION, a.facts.regions, b.facts.regions, side(a.weightOf(Category.REGION), b.weightOf(Category.REGION))),
            Line(Field.REVISION, listOfNotNull(a.facts.revision), listOfNotNull(b.facts.revision), side(a.weightOf(Category.REVISION), b.weightOf(Category.REVISION))),
            Line(Field.RELEASE, listOf(a.facts.release.name), listOf(b.facts.release.name), side(a.weightOf(Category.RELEASE), b.weightOf(Category.RELEASE))),
            Line(
                Field.DUMP,
                listOf(a.facts.dump.name) + a.facts.unofficial.map { it.name },
                listOf(b.facts.dump.name) + b.facts.unofficial.map { it.name },
                side(a.weightOf(Category.DUMP) + a.weightOf(Category.OFFICIAL), b.weightOf(Category.DUMP) + b.weightOf(Category.OFFICIAL))
            ),
            Line(Field.SIZE, listOfNotNull(a.version.size.takeIf { it > 0 }?.toString()), listOfNotNull(b.version.size.takeIf { it > 0 }?.toString()), sizeWinner),
            Line(Field.FORMAT, listOf(fmt(a)).filter { it.isNotEmpty() }, listOf(fmt(b)).filter { it.isNotEmpty() }, Side.NONE)
        )
    }

    // ---- Preferring versions like one --------------------------------------------------------------

    /**
     * [current] with [version]'s regions put first (the others keep their order after them) and its
     * languages added after the user's own ones when missing: preferring a region never pushes the
     * languages the user reads down. With [followRevisionRule], "newest revision" is switched on or
     * off by whether [version] is the newest one ([isNewestRevision]). Everything else stays.
     */
    fun pin(version: Facts, current: VersionPreference, followRevisionRule: Boolean = false, isNewestRevision: Boolean = true): VersionPreference =
        current.copy(
            regions = VersionPreferences.cleanRegions(version.regions + current.regions),
            languages = VersionPreferences.cleanLanguages(current.languages + version.languages),
            preferLatestRevision = if (followRevisionRule) isNewestRevision else current.preferLatestRevision
        )

    /** What "prefer versions like this" stores. [shown] is the preference the chosen scope ends up with. */
    data class PinPlan(
        /** The preference for all consoles afterwards (unchanged when only one console is changed). */
        val global: VersionPreference,
        /** The console's own order afterwards; null = none. */
        val override: ConsoleOverride?,
        /** For all consoles: the console's own order hides (part of) the change there, and it stays. */
        val hiddenByOverride: Boolean,
        /** The console's own order is (partly) cleared so the change shows there. */
        val clearsOverride: Boolean,
        val shown: VersionPreference
    )

    /**
     * The one place the pin dialog's preview and its action come from, so they cannot differ.
     * [global] is the preference for all consoles now (pinned or default), [override] the console's
     * own order. For all consoles ([forConsole] false) the global preference gets [facts] first and
     * the revision rule may follow; when the console's own order would hide a changed list there,
     * [clearOverride] drops that list of the override. For one console only the lists that change
     * are stored in its override; the rules stay global, so the revision rule does not apply.
     */
    fun planPin(
        facts: Facts,
        global: VersionPreference,
        override: ConsoleOverride?,
        forConsole: Boolean,
        followRevisionRule: Boolean,
        isNewestRevision: Boolean,
        clearOverride: Boolean
    ): PinPlan {
        val own = override?.takeUnless { it.isEmpty }
        if (forConsole) {
            val base = VersionPreferences.withOverride(global, own)
            val next = pin(facts, base)
            val stored = ConsoleOverride(
                regions = if (next.regions != base.regions) next.regions else own?.regions,
                languages = if (next.languages != base.languages) next.languages else own?.languages
            ).takeUnless { it.isEmpty }
            return PinPlan(global, stored, hiddenByOverride = false, clearsOverride = false, shown = VersionPreferences.withOverride(global, stored))
        }
        val next = pin(facts, global, followRevisionRule, isNewestRevision)
        val hidesRegions = own?.regions != null && next.regions != global.regions
        val hidesLanguages = own?.languages != null && next.languages != global.languages
        val hidden = hidesRegions || hidesLanguages
        val clears = hidden && clearOverride
        val stored = if (!clears) own else ConsoleOverride(
            regions = if (hidesRegions) null else own?.regions,
            languages = if (hidesLanguages) null else own?.languages
        ).takeUnless { it.isEmpty }
        return PinPlan(next, stored, hiddenByOverride = hidden && !clears, clearsOverride = clears, shown = next)
    }

    /** Whether [target] belongs to the newest revision among [ranked] (true when there is only one revision). */
    fun isNewestRevision(target: Ranked, ranked: List<Ranked>): Boolean {
        val r = target.reasons.firstOrNull { it.category == Category.REVISION } ?: return true
        return r.kind == ReasonKind.REVISION_NEWEST || ranked.none { it.reasons.any { x -> x.kind == ReasonKind.REVISION_NEWEST } }
    }
}
