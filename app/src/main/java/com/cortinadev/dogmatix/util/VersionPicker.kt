package com.cortinadev.dogmatix.util

import java.util.Locale

/**
 * Chooses the best of several versions of one game (regions, revisions, demos…) from what the
 * tags say. The entry point of every automatic pick (the game page, "Download best version", bulk
 * downloads, the wishlist, list import, Best games, the duplicate finder's suggestion); the ranking
 * itself, with the reasons behind it, is [VersionCompare]. Scores are only a ranking; nothing is
 * decided silently.
 */
object VersionPicker {

    /** One version to choose from. [name] may be a URL-encoded file name: it is decoded before it is read. */
    data class Candidate(val id: String, val name: String, val tags: List<String> = emptyList(), val size: Long = 0L)

    /** A candidate ([index] = its position in the list given) with its place in the ranking and the reasons for it ([detail]). */
    data class Ranked(val candidate: Candidate, val index: Int, val detail: VersionCompare.Ranked) {
        val score: Int get() = detail.score
    }

    private val europeanLanguages = setOf("NL", "FR", "DE", "ES", "IT", "PT", "SV", "DA", "NO", "FI", "PL", "RU", "CS", "EL", "TR")
    private val japaneseLanguages = setOf("JA", "JP", "JPN", "ZH", "KO")
    private val percentEscape = Regex("%[0-9A-Fa-f]{2}")

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

    /**
     * [name] as a person reads it: listings hand over URL-encoded file names ("Game%20%28USA%29.zip"),
     * other sources plain ones. Only a name with an escape is decoded, so a plain "+" stays a "+".
     */
    fun readable(name: String): String = if (percentEscape.containsMatchIn(name)) FileParsingUtils.decodeUrlEncodedFileName(name) else name

    fun rank(candidates: List<Candidate>, regionPreference: List<String>, languages: Set<String>): List<Ranked> =
        rank(candidates, VersionPreferences.of(regionPreference, languages))

    /** [candidates] best first by [preference]. */
    fun rank(candidates: List<Candidate>, preference: VersionPreference): List<Ranked> {
        // Ranked by position, so two candidates with the same id or name stay apart.
        val versions = candidates.mapIndexed { i, c -> VersionCompare.Version(i.toString(), c.name, c.tags, c.size, readable(c.name).substringAfterLast('.', "")) }
        return VersionCompare.rank(versions, preference).map { val i = it.version.id.toInt(); Ranked(candidates[i], i, it) }
    }

    fun best(candidates: List<Candidate>, regionPreference: List<String>, languages: Set<String>): Candidate? =
        best(candidates, VersionPreferences.of(regionPreference, languages))

    fun best(candidates: List<Candidate>, preference: VersionPreference): Candidate? = rank(candidates, preference).firstOrNull()?.candidate

    /** The words inside `(…)` and `[…]` of a file name, split on commas and `+`. */
    fun tagsOf(name: String): List<String> =
        Regex("""[(\[]([^)\]]+)[)\]]""").findAll(name)
            .flatMap { it.groupValues[1].split(',', '+', '/').map { part -> part.trim() } }
            .filter { it.isNotEmpty() }.toList()
}
