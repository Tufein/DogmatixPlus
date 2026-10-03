package com.cortinadev.dogmatix.util

import java.util.Locale

/**
 * Chooses the best of several versions of one game (regions, revisions, demos…) from what the
 * names say. Used by "Download best version" in the game details and by the duplicate finder's
 * suggestion of which copy to keep. Scores are only a ranking; nothing is decided silently.
 */
object VersionPicker {

    data class Candidate(val id: String, val name: String, val tags: List<String> = emptyList(), val size: Long = 0L)
    data class Ranked(val candidate: Candidate, val score: Int, val notes: List<String>)

    /** Words that mark a release as not the one to play: prototypes, demos, unofficial or bad dumps. */
    private val unwanted = listOf(
        "beta", "proto", "prototype", "demo", "sample", "unl", "unlicensed", "pirate", "hack", "homebrew",
        "bootleg", "overdump", "kiosk", "promo", "debug", "not for resale", "aftermarket", "translated", "alt"
    )
    private val badDump = Regex("""\[(b\d*|h\d*[a-z]*|o\d*|f\d*|t\d*)]""", RegexOption.IGNORE_CASE)
    private val verified = Regex("""\[!]""")
    private val revision = Regex("""(?i)\(?(?:rev\.?\s*([0-9]+|[a-z])|v\s*([0-9]+(?:\.[0-9]+)?))\)?""")

    private val europeanLanguages = setOf("NL", "FR", "DE", "ES", "IT", "PT", "SV", "DA", "NO", "FI", "PL", "RU", "CS", "EL", "TR")
    private val japaneseLanguages = setOf("JA", "JP", "JPN", "ZH", "KO")

    /** Regions in order of preference for someone who reads [languages] (upper-case codes): "World" always leads. */
    fun regionPreference(languages: Set<String>): List<String> {
        val langs = languages.map { it.uppercase(Locale.ROOT) }.toSet()
        val order = mutableListOf("World")
        val european = langs.any { it in europeanLanguages }
        if (european) order += "Europe"
        if ("EN" in langs) order += listOf("USA", "Europe")
        if (langs.any { it in japaneseLanguages }) order += "Japan"
        order += listOf("Europe", "USA", "Japan", "Australia", "Asia", "Korea", "Brazil")
        return order.distinct()
    }

    fun rank(candidates: List<Candidate>, regionPreference: List<String>, languages: Set<String>): List<Ranked> =
        candidates.map { score(it, regionPreference, languages) }
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.candidate.name.lowercase() })

    fun best(candidates: List<Candidate>, regionPreference: List<String>, languages: Set<String>): Candidate? =
        rank(candidates, regionPreference, languages).firstOrNull()?.candidate

    fun score(candidate: Candidate, regionPreference: List<String>, languages: Set<String>): Ranked {
        val text = (candidate.name + " " + candidate.tags.joinToString(" ")).lowercase(Locale.ROOT)
        val tagWords = (tagsOf(candidate.name) + candidate.tags).map { it.lowercase(Locale.ROOT) }.toSet()
        var score = 0
        val notes = mutableListOf<String>()

        val regionIndex = regionPreference.indexOfFirst { region -> region.lowercase(Locale.ROOT) in tagWords || containsWord(text, region.lowercase(Locale.ROOT)) }
        if (regionIndex >= 0) {
            score += (regionPreference.size - regionIndex) * 10
            notes += regionPreference[regionIndex]
        }
        val wantedLanguages = languages.map { it.lowercase(Locale.ROOT) }.toSet()
        if (wantedLanguages.any { it in tagWords }) { score += 15; notes += "language" }

        val flagged = unwanted.filter { it in tagWords || containsTagged(candidate.name, it) }
        if (flagged.isNotEmpty() || badDump.containsMatchIn(candidate.name)) { score -= 100; notes += "unwanted" }
        if (verified.containsMatchIn(candidate.name)) { score += 5; notes += "verified" }
        revision.find(candidate.name)?.let { m ->
            val rev = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val number = rev.toDoubleOrNull() ?: (rev.firstOrNull()?.let { it.lowercaseChar() - 'a' + 1 }?.toDouble() ?: 0.0)
            score += number.coerceIn(0.0, 5.0).toInt()
        }
        return Ranked(candidate, score, notes)
    }

    /** The words inside `(…)` and `[…]` of a file name, split on commas and `+`. */
    fun tagsOf(name: String): List<String> =
        Regex("""[(\[]([^)\]]+)[)\]]""").findAll(name)
            .flatMap { it.groupValues[1].split(',', '+', '/').map { part -> part.trim() } }
            .filter { it.isNotEmpty() }.toList()

    private fun containsWord(text: String, word: String) = Regex("""(?<![a-z])${Regex.escape(word)}(?![a-z])""").containsMatchIn(text)

    /** `(Beta)`, `(Beta 2)`, `[Proto]` — the word as the start of a bracketed tag. */
    private fun containsTagged(name: String, word: String) =
        Regex("""[(\[]\s*${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)
}
