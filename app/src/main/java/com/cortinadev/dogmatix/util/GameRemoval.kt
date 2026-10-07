package com.cortinadev.dogmatix.util

/** Archive companions/references require recognized game formats. Saves, states and metadata are protected. */
object GameRemoval {
    fun matches(actual: String, requested: String): Boolean {
        val name = actual.lowercase(java.util.Locale.ROOT)
        val wanted = requested.lowercase(java.util.Locale.ROOT)
        if (!DuplicateFinder.isGameFile(name)) return false
        if (name == wanted) return true
        val archived = wanted.substringAfterLast('.') in setOf("zip", "7z", "rar")
        return archived && DuplicateFinder.isKnownGameFormat(name) && LibraryKeys.baseName(name) == LibraryKeys.baseName(wanted)
    }
    fun safeReference(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        !name.contains('/') && !name.contains('\\') && !name.contains(':') &&
        DuplicateFinder.isGameFile(name) && DuplicateFinder.isKnownGameFormat(name)
}
