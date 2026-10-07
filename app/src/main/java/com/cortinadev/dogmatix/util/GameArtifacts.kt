package com.cortinadev.dogmatix.util

import java.util.ArrayDeque
import java.util.Locale

/** Local game files and their descriptor dependencies, with separate rules for play and removal. */
object GameArtifacts {
    private val descriptors = setOf("cue", "m3u", "gdi")

    /** A flat local name in an explicitly recognized ROM/disc/audio format, never an unknown config/key. */
    fun safeLaunchReference(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        !name.startsWith('.') && name.none { it == '/' || it == '\\' || it == ':' } && DuplicateFinder.isKnownGameFormat(name)

    /**
     * Reads only selected descriptors for Play. Removal also reads other descriptors so their shared
     * files stay on disk. References are restricted to game files in this same folder; saves and
     * paths outside it never become dependencies.
     */
    fun plan(names: List<String>, requested: String, protectShared: Boolean, references: (String) -> List<String>): List<String> {
        fun descriptor(name: String) = name.substringAfterLast('.').lowercase(Locale.ROOT) in descriptors
        fun key(name: String) = name.lowercase(Locale.ROOT)
        val archived = requested.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("zip", "7z", "rar")
        val selected = names.filter { name ->
            if (protectShared) GameRemoval.matches(name, requested)
            else safeLaunchReference(name) && (name.equals(requested, true) ||
                archived && LibraryKeys.baseName(name).equals(LibraryKeys.baseName(requested), true))
        }.toMutableList()
        val shared = if (protectShared) names.filter { it !in selected && descriptor(it) }
            .flatMap(references).map(::key).toSet() else emptySet()
        selected.removeAll { key(it) in shared }
        val queue = ArrayDeque(selected)
        val visited = HashSet<String>()
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!visited.add(key(name)) || !descriptor(name)) continue
            for (reference in references(name)) {
                val safe = if (protectShared) GameRemoval.safeReference(reference) else safeLaunchReference(reference)
                if (!safe || key(reference) in shared) continue
                names.firstOrNull { it.equals(reference, ignoreCase = true) }?.let { child ->
                    if (child !in selected) { selected += child; queue.add(child) }
                }
            }
        }
        return selected
    }
}
