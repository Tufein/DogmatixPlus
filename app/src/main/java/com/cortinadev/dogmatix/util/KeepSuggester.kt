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

    fun suggest(groups: List<DuplicateGroup>, regionPreference: List<String>, languages: Set<String>): List<KeepSuggestion> =
        groups.mapNotNull { suggest(it, regionPreference, languages) }

    fun suggest(group: DuplicateGroup, regionPreference: List<String>, languages: Set<String>): KeepSuggestion? {
        if (group.entries.size < 2) return null
        return when (group.kind) {
            // The very same file in several folders: keep the one in the shallowest, alphabetically first folder.
            DuplicateGroup.Kind.IDENTICAL -> {
                val keep = group.entries.sortedWith(compareBy({ it.folder.count { c -> c == '/' } }, { it.folder.lowercase() })).first()
                KeepSuggestion(group, keep, group.entries.filter { it.id != keep.id })
            }
            DuplicateGroup.Kind.VARIANT -> {
                val ranked = group.entries.map { entry ->
                    entry to VersionPicker.score(VersionPicker.Candidate(entry.id, entry.baseName, size = entry.size), regionPreference, languages).score
                }.sortedByDescending { it.second }
                if (ranked[0].second == ranked[1].second) return null
                val keep = ranked[0].first
                KeepSuggestion(group, keep, group.entries.filter { it.id != keep.id })
            }
        }
    }
}
