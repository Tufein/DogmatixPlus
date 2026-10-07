package com.cortinadev.dogmatix.util

/**
 * The versions of one game as the game page shows them: ranked by the user's preference, each with
 * the reasons for its place, and the pick ([VersionPreference.pick]: the version fixed for this
 * game, else the best ranked) first. Pure JVM.
 */
class VersionRanking<T> private constructor(
    /** Display order: the pick first, then the rest by rank. */
    val rows: List<Row<T>>,
    /** Best first, as [VersionCompare.rank] returned them (for the side-by-side and the pin dialog). */
    val ranked: List<VersionCompare.Ranked>,
    val explanation: VersionCompare.Explanation?,
    /** What a "best version" action takes; null with a single version. */
    val pick: T?,
    val preference: VersionPreference
) {
    data class Row<T>(
        val item: T,
        val ranked: VersionCompare.Ranked,
        /** Why it ranks below the best ranked one (null for that one). */
        val whyNot: VersionCompare.WhyNot?,
        val isPick: Boolean,
        /** Ranked first by the preference. */
        val isTop: Boolean,
        /** The version fixed for this game. */
        val isFixed: Boolean
    )

    companion object {
        fun <T> of(items: List<T>, candidate: (T) -> VersionPicker.Candidate, preference: VersionPreference, fixed: String?): VersionRanking<T> {
            val candidates = items.map(candidate)
            val ranked = VersionPicker.rank(candidates, preference)
            val picked = if (items.size > 1) VersionPreference.pick(candidates, preference, fixed) else null
            val pickIndex = picked?.let { p -> candidates.indexOfFirst { it === p } } ?: -1
            val fixedIndex = candidates.indexOfFirst { it.id == fixed }
            val details = ranked.map { it.detail }
            val explanation = VersionCompare.explainBest(details)
            val whyNot = explanation?.whyNot.orEmpty().associateBy { it.version.version.id }
            val rows = ranked.mapIndexed { place, r ->
                Row(items[r.index], r.detail, whyNot[r.detail.version.id], r.index == pickIndex, place == 0, r.index == fixedIndex)
            }.sortedByDescending { it.isPick }
            return VersionRanking(rows, details, explanation, items.getOrNull(pickIndex), preference)
        }
    }
}
