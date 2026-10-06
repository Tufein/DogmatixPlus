package com.cortinadev.dogmatix.util

import java.util.Locale

/**
 * "Better version available" (7.0): for a game that is on the device, is there a file in the
 * sources that is clearly a better copy of the same game? Judged only from the file names (the
 * No-Intro / Redump `(USA) (Rev 1)` tags and the GoodTools `[!] [b1] [h1C]` flags), plus the DAT
 * status of the file on the device when the app already has one. Pure JVM for the tests.
 *
 * What counts as better, for the same game, region, languages and disc:
 *  - a newer revision (`Rev A` → `Rev B`, `v1.0` → `v1.1`, nothing → `Rev 1`);
 *  - a final release instead of a beta, prototype, demo, sample or kiosk build;
 *  - a good dump instead of a bad dump, overdump, hack or pirate copy.
 *
 * The rules lean on being sure rather than on finding much, because the answer can lead to a
 * removal: every tag that is not about quality (region, languages, "Alt", "Virtual Console",
 * "Unl", "Greatest Hits", a date…) must be exactly the same on both sides, so a different region
 * or language is never offered, and the candidate may not be worse in any respect.
 */
object BetterVersions {

    /** How trustworthy the dump looks; the order is the quality order. */
    enum class Integrity { BAD, MODIFIED, GOOD }

    /** What is wrong with a file that is not a good dump. */
    enum class Problem { BAD_DUMP, OVERDUMP, HACK, PIRATE, MODIFIED, NOT_IN_DAT }

    /** What kind of unfinished build a file is. */
    enum class PreKind { BETA, PROTO, DEMO, SAMPLE, KIOSK, OTHER }

    enum class Reason { GOOD_DUMP, FINAL_RELEASE, NEWER_REVISION }

    enum class Scheme { REV, VERSION }

    /** `Rev 1`, `Rev A` (= 1), `v1.1`; [label] is as written in the name. */
    data class Revision(val scheme: Scheme, val parts: List<Int>, val label: String)

    data class Pre(val kind: PreKind, val tag: String)

    /** One file name taken apart. */
    data class Parsed(
        /** The name without its extension. */
        val base: String,
        /** Everything that says which release this is, apart from quality: regions, languages, other tags. */
        val identity: Set<String>,
        val integrity: Integrity,
        val problem: Problem?,
        val pre: Pre?,
        val revision: Revision?,
        /** Carries the GoodTools `[!]` (verified good dump) mark. */
        val verified: Boolean,
        /** An update, DLC or patch: never compared with a game. */
        val addOn: Boolean
    )

    /** Why [candidate] is better than the current file. */
    data class Upgrade(
        val reason: Reason,
        /** What is wrong with the current file ([Reason.GOOD_DUMP]). */
        val problem: Problem? = null,
        /** What the current file is ([Reason.FINAL_RELEASE]). */
        val pre: PreKind? = null,
        /** The suggested file's revision as its name writes it ([Reason.NEWER_REVISION]). */
        val revision: String? = null
    )

    /** A game on the device. [dat] is the DAT status of its file when a DAT check ran this session. */
    data class OwnedGame(val id: String, val consoleId: String, val name: String, val dat: DatStatus? = null)

    /** A file a source lists. */
    data class Offer(val id: String, val consoleId: String, val fileName: String)

    data class Match(val owned: OwnedGame, val offer: Offer, val upgrade: Upgrade)

    // ---- Parsing ----------------------------------------------------------------------------

    private val groups = Regex("\\(([^)]*)\\)|\\[([^\\]]*)]")
    private val extension = Regex("\\.[A-Za-z0-9]{1,5}$")
    private val revisionTag = Regex("(?i)^rev(?:ision)?\\.?\\s*(\\d+(?:\\.\\d+)*|[a-z])$")
    private val versionTag = Regex("(?i)^v(?:er(?:sion)?)?\\.?\\s*(\\d+(?:\\.\\d+)*)([a-z])?$")
    private val titleVersion = Regex("(?i)\\s+v(\\d+(?:\\.\\d+)*)$")
    private val preTag = Regex("(?i)\\b(beta|proto|prototype|demo|sample|kiosk|preview|trial|taikenban|debug)\\b")
    private val addOnTag = Regex("(?i)^(update|upd|dlc|patch|add-?on|season pass|title update)\\b")
    private val modifiedTag = Regex("(?i)\\b(hack(?:ed)?|pirate|bootleg|cracked|trainer|trained)\\b")
    private val badTag = Regex("(?i)^(bad(?: (?:dump|crc|checksum))?|overdump(?:ed)?)$")
    private val flagBad = Regex("(?i)^([box])\\d*$")
    private val flagModified = Regex("(?i)^(?:h\\d*[a-z]{0,3}|p\\d*|t\\d*|f\\d*|bf\\d*)$")
    private val discTag = Regex(
        "(?i)^(disc|disk|cd|dvd|gd|side|tape|part|file)\\s*(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|[ivx]+|[a-z])(?:\\s*of\\s*\\w+)?$"
    )

    /** The name without a plausible file extension ("Game (USA).zip" → "Game (USA)"; "Game v1.1" stays). */
    fun withoutExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        val ext = name.substring(dot + 1)
        return if (extension.matches(name.substring(dot)) && ext.any { it.isLetter() }) name.substring(0, dot) else name
    }

    /** Takes a file name (or a base name) apart; see the class comment for what is recognised. */
    fun parse(name: String): Parsed {
        val base = withoutExtension(name.trim())
        var integrity = Integrity.GOOD
        var problem: Problem? = null
        var pre: Pre? = null
        var revision: Revision? = null
        var verified = false
        var addOn = false
        val identity = LinkedHashSet<String>()

        fun flag(i: Integrity, p: Problem) {
            if (i < integrity) { integrity = i; problem = p }
        }

        for (match in groups.findAll(base)) {
            val square = match.groups[2] != null
            val content = (match.groups[1] ?: match.groups[2])!!.value.trim()
            if (content.isEmpty()) continue
            val lower = content.lowercase(Locale.ROOT)
            if (square) {
                when {
                    content == "!" -> { verified = true; continue }
                    flagBad.matches(content) -> { flag(Integrity.BAD, if (lower.startsWith("o")) Problem.OVERDUMP else Problem.BAD_DUMP); continue }
                    flagModified.matches(content) -> {
                        flag(Integrity.MODIFIED, when { lower.startsWith("h") -> Problem.HACK; lower.startsWith("p") -> Problem.PIRATE; else -> Problem.MODIFIED })
                        continue
                    }
                }
            }
            when {
                addOnTag.containsMatchIn(content) -> addOn = true
                badTag.matches(content) -> flag(Integrity.BAD, if (lower.startsWith("over")) Problem.OVERDUMP else Problem.BAD_DUMP)
                modifiedTag.containsMatchIn(content) -> {
                    val word = modifiedTag.find(content)!!.value.lowercase(Locale.ROOT)
                    flag(Integrity.MODIFIED, when { word.startsWith("hack") -> Problem.HACK; word == "pirate" -> Problem.PIRATE; else -> Problem.MODIFIED })
                }
                preTag.containsMatchIn(content) -> if (pre == null) pre = Pre(preKind(preTag.find(content)!!.value), lower)
                revisionTag.matches(content) -> if (revision == null) revision = revisionOf(content)
                versionTag.matches(content) -> if (revision == null) revision = versionOf(content)
                // Disc numbers are compared on the whole name (see [discKey]), so "Disc 1 of 2" = "Disc 1".
                discTag.matches(content) -> Unit
                else -> content.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
                    .forEach { identity += if (square) "[$it" else it }
            }
        }
        if (revision == null) {
            titleVersion.find(groups.replace(base, "").trim())?.let { revision = Revision(Scheme.VERSION, numbers(it.groupValues[1]), "v" + it.groupValues[1]) }
        }
        return Parsed(base, identity, integrity, problem, pre, revision, verified, addOn)
    }

    private fun preKind(word: String): PreKind = when (word.lowercase(Locale.ROOT)) {
        "beta" -> PreKind.BETA
        "proto", "prototype" -> PreKind.PROTO
        "demo", "trial", "taikenban" -> PreKind.DEMO
        "sample" -> PreKind.SAMPLE
        "kiosk" -> PreKind.KIOSK
        else -> PreKind.OTHER
    }

    private fun revisionOf(tag: String): Revision {
        val value = revisionTag.find(tag)!!.groupValues[1]
        val parts = if (value.first().isDigit()) numbers(value) else listOf(value.first().lowercaseChar() - 'a' + 1)
        return Revision(Scheme.REV, parts, "Rev " + value.uppercase(Locale.ROOT))
    }

    private fun versionOf(tag: String): Revision {
        val m = versionTag.find(tag)!!
        val letter = m.groupValues[2]
        val parts = numbers(m.groupValues[1]) + (if (letter.isEmpty()) emptyList() else listOf(letter.first().lowercaseChar() - 'a' + 1))
        return Revision(Scheme.VERSION, parts, "v" + m.groupValues[1] + letter)
    }

    private fun numbers(text: String): List<Int> = text.split('.').map { it.toIntOrNull() ?: 0 }

    // ---- Comparing --------------------------------------------------------------------------

    /** What a file without any revision tag counts as: the original release. */
    private fun baseline(scheme: Scheme): List<Int> = if (scheme == Scheme.VERSION) listOf(1, 0) else listOf(0)

    private fun compareParts(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    /** Sign of (b - a): 1 when the candidate [b] is newer than [a], -1 when older; null when the two cannot be compared. */
    private fun compareRevisions(a: Revision?, b: Revision?): Int? = when {
        a == null && b == null -> 0
        a == null -> compareParts(b!!.parts, baseline(b.scheme))
        b == null -> compareParts(baseline(a.scheme), a.parts)
        a.scheme != b.scheme -> null
        else -> compareParts(b.parts, a.parts)
    }

    /** Disc / side / tape / part numbers of a name, so the discs of one game are never compared with each other. */
    private fun discKey(base: String): String = DuplicateFinder.titleKey(base).substringAfter('#', "")

    private fun sameGame(a: Parsed, b: Parsed): Boolean =
        GameTitleCleaner.sameTitle(a.base + ".x", b.base + ".x") && discKey(a.base) == discKey(b.base) && a.identity == b.identity

    /**
     * Whether [candidate] (a file name) is a better copy of the same game than [current] (a file
     * or base name), and why; null when it is not clearly better. [currentDat] is the DAT status of
     * the current file when a DAT check ran: a file the DAT does not know counts as a bad dump
     * (only a candidate marked `[!]` replaces it that way), a file it knows counts as good.
     */
    fun upgrade(current: String, candidate: String, currentDat: DatStatus? = null): Upgrade? =
        upgrade(parse(current), parse(candidate), currentDat)

    fun upgrade(a: Parsed, b: Parsed, currentDat: DatStatus? = null): Upgrade? {
        if (a.addOn || b.addOn) return null
        if (a.base.equals(b.base, ignoreCase = true)) return null
        if (!sameGame(a, b)) return null
        // A flagged or unfinished file is never what is offered, and a final game is never traded for one.
        if (b.integrity != Integrity.GOOD) return null
        if (a.pre == null && b.pre != null) return null
        if (a.pre != null && b.pre != null && a.pre.tag != b.pre.tag) return null

        var integrity = a.integrity
        var problem = a.problem
        var datOnly = false
        when (currentDat) {
            DatStatus.VERIFIED, DatStatus.MISNAMED -> { integrity = Integrity.GOOD; problem = null }
            DatStatus.UNKNOWN -> if (integrity == Integrity.GOOD) { integrity = Integrity.BAD; problem = Problem.NOT_IN_DAT; datOnly = true }
            else -> Unit
        }
        val dumpGain = integrity != Integrity.GOOD && (!datOnly || b.verified)
        val stageGain = a.pre != null && b.pre == null
        // The numbers of a prerelease say nothing about the final game, so a stage gain skips them.
        var revisionGain = false
        if (!stageGain) {
            val order = compareRevisions(a.revision, b.revision) ?: return null
            if (order < 0) return null
            revisionGain = order > 0
        }
        return when {
            stageGain -> Upgrade(Reason.FINAL_RELEASE, pre = a.pre!!.kind)
            dumpGain -> Upgrade(Reason.GOOD_DUMP, problem = problem)
            revisionGain -> Upgrade(Reason.NEWER_REVISION, revision = b.revision?.label)
            else -> null
        }
    }

    /** How good a file that already passed [upgrade] is, to pick the best of several: final first, then the newest revision. */
    private fun rank(p: Parsed): Long {
        val parts = p.revision?.parts.orEmpty()
        val revision = (0 until 3).fold(0L) { acc, i -> acc * 1_000 + parts.getOrElse(i) { 0 }.coerceIn(0, 999) }
        return (if (p.pre == null) 1_000_000_000_000L else 0L) + revision * 2 + (if (p.verified) 1 else 0)
    }

    /** The best of [offers] that is better than [current]; null when none is. Ties go to the name that sorts first. */
    fun best(current: String, offers: List<Offer>, currentDat: DatStatus? = null): Pair<Offer, Upgrade>? {
        val a = parse(current)
        var pick: Triple<Offer, Upgrade, Long>? = null
        for (offer in offers) {
            val b = parse(offer.fileName)
            val up = upgrade(a, b, currentDat) ?: continue
            val score = rank(b)
            val p = pick
            if (p == null || score > p.third || (score == p.third && offer.fileName.lowercase(Locale.ROOT) < p.first.fileName.lowercase(Locale.ROOT))) {
                pick = Triple(offer, up, score)
            }
        }
        return pick?.let { it.first to it.second }
    }

    // ---- Removing the old file afterwards ----------------------------------------------------

    /** What to do with the old file once the better one has finished downloading. */
    enum class CheckVerdict { REMOVE, KEEP, WAIT }

    /**
     * [state] is what the download's own check says (see `DownloadService.verification`); [expectsCheck]
     * is whether a check applies at all (the source published a hash, or the console has a DAT), in
     * which case no result yet means it is still to come. Removal happens only for a file that
     * passed; one that failed its check, or that the DAT does not know, never replaces the old one.
     */
    fun checkVerdict(state: VerifyState?, expectsCheck: Boolean): CheckVerdict = when (state) {
        VerifyState.VERIFIED, VerifyState.DAT_OK -> CheckVerdict.REMOVE
        VerifyState.MISMATCH, VerifyState.DAT_UNKNOWN -> CheckVerdict.KEEP
        VerifyState.CHECKING -> CheckVerdict.WAIT
        null -> if (expectsCheck) CheckVerdict.WAIT else CheckVerdict.REMOVE
    }

    /** Whether one of the old files [oldNames] is the new file (or its unpacked form): then nothing may be removed. */
    fun overlaps(oldNames: Collection<String>, newFileName: String): Boolean {
        val new = withoutExtension(newFileName).trim().lowercase(Locale.ROOT)
        return oldNames.any {
            val old = it.trim().lowercase(Locale.ROOT)
            old == newFileName.trim().lowercase(Locale.ROOT) || withoutExtension(old).trim() == new
        }
    }

    // ---- Matching a library against a listing -----------------------------------------------

    /** Key under which names of one game share a bucket (the same words once tags and the extension are left out). */
    private fun titleBucket(name: String): String =
        GameTitleCleaner.words(withoutExtension(name) + ".x").sorted().joinToString(" ")

    /** The key an ignored suggestion is stored under: this file on the device, that file offered. */
    fun ignoreKey(consoleId: String, currentName: String, offerFileName: String): String =
        consoleId + "|" + withoutExtension(currentName).trim().lowercase(Locale.ROOT) + ">" + offerFileName.trim().lowercase(Locale.ROOT)

    /**
     * The better version of every game in [owned] that [offers] hold, per console. [skip] leaves
     * out an offer (already on the device, already downloading; asked only for offers that could
     * match a game); [ignored] holds [ignoreKey]s the
     * user dismissed. An offer is suggested for at most one game, and each game gets only its best.
     */
    fun suggest(
        owned: List<OwnedGame>,
        offers: List<Offer>,
        ignored: Set<String> = emptySet(),
        skip: (Offer) -> Boolean = { false }
    ): List<Match> {
        if (owned.isEmpty() || offers.isEmpty()) return emptyList()
        val buckets = HashMap<String, MutableList<Offer>>()
        for (offer in offers) {
            val key = titleBucket(offer.fileName)
            if (key.isNotEmpty()) buckets.getOrPut(offer.consoleId + "\u0000" + key) { ArrayList() } += offer
        }
        val used = HashSet<String>()
        val out = ArrayList<Match>()
        for (game in owned) {
            val key = titleBucket(game.name)
            if (key.isEmpty()) continue
            val candidates = buckets[game.consoleId + "\u0000" + key].orEmpty()
                .filter { it.id !in used && ignoreKey(game.consoleId, game.name, it.fileName) !in ignored && !skip(it) }
            val (offer, upgrade) = best(game.name, candidates, game.dat) ?: continue
            used += offer.id
            out += Match(game, offer, upgrade)
        }
        return out
    }
}
