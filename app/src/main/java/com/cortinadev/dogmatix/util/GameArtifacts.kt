package com.cortinadev.dogmatix.util

import java.util.ArrayDeque
import java.util.Locale

/** Local game files and their descriptor dependencies, with separate rules for play and removal. */
object GameArtifacts {
    private val descriptors = setOf("cue", "m3u", "gdi")

    /** A flat local name in an explicitly recognized ROM/disc/audio format, never an unknown config/key. */
    fun safeLaunchReference(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        !name.startsWith('.') && name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() } && DuplicateFinder.isKnownGameFormat(name)

    /** A descriptor may refer to game files in descendants, never parents or private files. */
    fun safeLaunchPath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
        path.split('/').let { parts -> parts.size <= 66 && parts.all { part ->
            part.isNotBlank() && part != "." && part != ".." && !part.startsWith('.') &&
                part.none { it == '\\' || it == ':' || it.isISOControl() }
        } } && safeLaunchReference(path.substringAfterLast('/'))

    private fun resolveDescendant(descriptor: String, reference: String): String? {
        val relative = reference.replace('\\', '/').removePrefix("./")
        if (!safeLaunchPath(relative)) return null
        val parent = descriptor.substringBeforeLast('/', "")
        return (if (parent.isEmpty()) relative else "$parent/$relative").takeIf(::safeLaunchPath)
    }

    /** Read-only dependency plan across folders. Removal retains its stricter flat rules. */
    fun playPaths(names: List<String>, requested: String, references: (String) -> List<String>): List<String> {
        fun key(path: String) = ArchivePlan.portableKey(path)
        val byKey = names.filter(::safeLaunchPath).groupBy(::key)
        val archived = requested.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("zip", "7z", "rar")
        val selected = names.filter { path -> safeLaunchPath(path) && path.substringAfterLast('/').let { name ->
            name.equals(requested, true) || archived && LibraryKeys.baseName(name).equals(LibraryKeys.baseName(requested), true)
        } }.toMutableList()
        val queue = ArrayDeque(selected)
        val visited = HashSet<String>()
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (!visited.add(key(path))) continue
            check(visited.size <= Constants.MAX_ARCHIVE_ENTRIES) { "Too many game dependencies" }
            check(byKey[key(path)]?.size == 1) { "Ambiguous game path" }
            if (path.substringAfterLast('.').lowercase(Locale.ROOT) !in descriptors) continue
            for (reference in references(path)) {
                val resolved = resolveDescendant(path, reference) ?: continue
                val matches = byKey[key(resolved)].orEmpty()
                check(matches.size <= 1) { "Ambiguous game dependency" }
                matches.singleOrNull()?.let { child ->
                    if (child !in selected) { selected += child; queue.add(child) }
                }
            }
        }
        return selected
    }

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
