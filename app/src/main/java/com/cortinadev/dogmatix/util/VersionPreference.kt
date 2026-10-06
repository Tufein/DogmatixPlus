package com.cortinadev.dogmatix.util

object VersionPreference {
    /** Include the disc identifier: fixing disc 1 must never redirect disc 2 to it. */
    fun key(consoleId: String, name: String): String {
        val disc = Regex("(?i)\\b(?:disc|disk|cd|side)\\s*([0-9a-z]+)").find(name)?.groupValues?.get(1)?.lowercase().orEmpty()
        return consoleId + "|" + GameTitleCleaner.words(name).sorted().joinToString(" ") + "|" + disc
    }
    fun pick(candidates: List<VersionPicker.Candidate>, regions: List<String>, languages: Set<String>, preferred: String?): VersionPicker.Candidate? =
        candidates.firstOrNull { it.id == preferred } ?: VersionPicker.best(candidates, regions, languages)
}
