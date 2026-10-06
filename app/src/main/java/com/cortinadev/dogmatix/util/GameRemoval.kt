package com.cortinadev.dogmatix.util

/** Only game artifacts may accompany an archive. Saves, states, metadata and arbitrary extensions never match. */
object GameRemoval {
    fun matches(actual: String, requested: String): Boolean {
        val name = actual.lowercase(java.util.Locale.ROOT)
        val wanted = requested.lowercase(java.util.Locale.ROOT)
        if (!DuplicateFinder.isGameFile(name)) return false
        if (name == wanted) return true
        val archived = wanted.substringAfterLast('.') in setOf("zip", "7z", "rar")
        return archived && LibraryKeys.baseName(name) == LibraryKeys.baseName(wanted)
    }
    fun safeReference(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        !name.contains('/') && !name.contains('\\') && !name.contains(':') && DuplicateFinder.isGameFile(name)
}
