package com.tufein.valkyr.util

import java.text.Normalizer

/**
 * Turns names and user queries into a lenient, comparable form so that searching
 * "yugioh" finds "Yu-Gi-Oh!" and "virtual tenis" finds "Virtua Tennis".
 *
 * Normalization applied to both sides:
 *  - lower-case, accents stripped ("Pokémon" -> "pokemon")
 *  - anything that is not a letter or digit removed ("Yu-Gi-Oh!" -> "yugioh")
 *  - repeated letters collapsed ("tennis" -> "tenis")
 */
object SearchNormalizer {

    /** Key stored alongside each file: the whole name squashed into one token. */
    fun key(name: String): String = squash(name)

    /**
     * SQL LIKE pattern for a user query, to be wrapped as `'%' || pattern || '%'`.
     * Each word is normalized and words are joined with `%`, so they must appear in
     * order but anything may sit between them. Words of 5+ characters drop their last
     * character to forgive a trailing typo or plural ("virtual" still finds "virtua").
     */
    fun likePattern(query: String): String = queryWords(query).joinToString("%")

    /**
     * The normalized words of a user query, each already shortened like [likePattern] describes.
     * Shared by the LIKE pattern and the full-text query so both forgive the same typos.
     */
    fun queryWords(query: String): List<String> =
        query.split(WHITESPACE)
            .map { squash(it) }
            .filter { it.isNotEmpty() }
            .map { token -> if (token.length >= 5) token.dropLast(1) else token }

    /**
     * 4.6: the words of a title as stored in `downloadable_files.searchWords` (and indexed by the
     * FTS table): every word of the name normalized like [key] (accents, repeated letters), plus the
     * whole squashed title when it has several words, so that "yugioh" and "yu gi oh" both find
     * "Yu-Gi-Oh!" by prefix. Space separated, no duplicates; empty when the name has no letter or digit.
     */
    fun words(name: String): String {
        val folded = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD)
        val tokens = LinkedHashSet<String>()
        val sb = StringBuilder()
        var last = ' '
        for (c in folded) {
            if (c.isLetterOrDigit()) {
                if (c != last) sb.append(c)
                last = c
            } else if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) {
                // Any other character ends the word; an accent left over from the decomposition does not.
                if (sb.isNotEmpty()) tokens += sb.toString()
                sb.setLength(0)
                last = ' '
            }
        }
        if (sb.isNotEmpty()) tokens += sb.toString()
        if (tokens.size > 1) {
            val whole = squash(name)
            if (whole.isNotEmpty()) tokens += whole
        }
        return tokens.joinToString(" ")
    }

    private val WHITESPACE = Regex("\\s+")

    private fun squash(text: String): String {
        val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(folded.length)
        var last = ' '
        for (c in folded) {
            if (!c.isLetterOrDigit()) continue
            if (c != last) sb.append(c)
            last = c
        }
        return sb.toString()
    }
}
