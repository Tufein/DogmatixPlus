package com.cortinadev.dogmatix.util

/**
 * The last few library searches, newest first. A search is remembered once the user stops typing;
 * the shorter steps typed on the way ("zel" before "zelda") are dropped when the longer one comes.
 */
object RecentSearches {
    const val MAX = 6
    private const val MIN_LENGTH = 2
    private val SPACES = Regex("\\s+")

    fun add(list: List<String>, query: String, max: Int = MAX): List<String> {
        val q = query.trim().replace(SPACES, " ")
        if (q.length < MIN_LENGTH) return list
        val lower = q.lowercase()
        val rest = list.filterNot { lower.startsWith(it.lowercase()) }
        return (listOf(q) + rest).take(max)
    }

    fun encode(list: List<String>): String = list.joinToString("\n")

    fun decode(text: String): List<String> = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
}
