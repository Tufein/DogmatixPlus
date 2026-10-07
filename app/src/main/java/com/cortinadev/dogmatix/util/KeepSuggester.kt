package com.cortinadev.dogmatix.util

/** For one group of duplicates: the copy to keep and the ones that can go. */
data class KeepSuggestion(val group: DuplicateGroup, val keep: GameEntry, val remove: List<GameEntry>) {
    val reclaimable: Long get() = remove.sumOf { it.size }
}

/**
 * Proposes which copy of each duplicate to keep. Only a proposal: the screen still shows every file
 * that would be removed and asks first. Where nothing clearly sets one copy apart (two variants that
 * rank the same), no suggestion is made, so a wrong guess never picks a region for the user.
 */
object KeepSuggester {

    fun suggest(groups: List<DuplicateGroup>, regionPreference: List<String>, languages: Set<String>): List<KeepSuggestion> {
        val preference = VersionPreferences.of(regionPreference, languages)
        return suggest(groups) { preference }
    }

    /** With the user's version preference per group (a console may have its own order). */
    fun suggest(groups: List<DuplicateGroup>, preferenceOf: (DuplicateGroup) -> VersionPreference): List<KeepSuggestion> =
        groups.mapNotNull { suggest(it, preferenceOf) }

    fun suggest(group: DuplicateGroup, regionPreference: List<String>, languages: Set<String>): KeepSuggestion? =
        VersionPreferences.of(regionPreference, languages).let { p -> suggest(group) { p } }

    fun suggest(group: DuplicateGroup, preferenceOf: (DuplicateGroup) -> VersionPreference): KeepSuggestion? {
        if (group.entries.size < 2) return null
        return when (group.kind) {
            // The very same file in several folders: keep the one in the shallowest, alphabetically first folder.
            DuplicateGroup.Kind.IDENTICAL -> {
                val keep = group.entries.sortedWith(compareBy({ it.folder.count { c -> c == '/' } }, { it.folder.lowercase() })).first()
                KeepSuggestion(group, keep, group.entries.filter { it.id != keep.id })
            }
            DuplicateGroup.Kind.VARIANT -> {
                val ranked = VersionPicker.rank(group.entries.map { VersionPicker.Candidate(it.id, it.baseName, size = it.size) }, preferenceOf(group))
                if (ranked[0].score == ranked[1].score) return null
                val keep = group.entries.first { it.id == ranked[0].candidate.id }
                KeepSuggestion(group, keep, group.entries.filter { it.id != keep.id })
            }
        }
    }
}
