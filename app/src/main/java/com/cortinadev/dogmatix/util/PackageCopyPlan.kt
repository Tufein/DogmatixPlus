package com.cortinadev.dogmatix.util

import java.util.Locale

/** What happens to a game when a file of the same name, but not the same file, is already at the destination. */
enum class CopyConflictChoice { SKIP, RENAME }

/**
 * The preview of copying game packages to another place (an SD card or USB folder, a NAS):
 * every file with its size and where it goes, what is already there, what clashes, the space it
 * needs, and the save files that stay behind. Pure JVM for the tests; the service feeds it the
 * packages and a lookup of what the destination already holds, and carries the plan out.
 *
 * Rules:
 *  - nothing at the destination is ever replaced: a file of the same name and size is "probably
 *    the same" and is compared byte for byte when copying (kept either way); any other file or
 *    folder in the way is a clash;
 *  - a game with a clash is left out ([CopyConflictChoice.SKIP], the default) or goes to a new
 *    name as a whole ([CopyConflictChoice.RENAME]): its folder (or, for a single file, the file)
 *    gets " (2)", " (3)"…, so the references between its files (cue → bin, m3u → discs) stay
 *    valid. Games of loose files get a folder of their own then;
 *  - a file several selected games share (same place, same contents) is listed and copied once;
 *  - save files and save states never travel with a game ([isSaveOrState]); they are listed.
 */
object PackageCopyPlan {
    /** Bytes kept free at the destination on top of what the copy needs. */
    const val MARGIN_BYTES = 32L * 1024 * 1024
    private const val MAX_RENAME = 99

    data class Part(val path: String, val bytes: Long, val sha256: String, val source: String)

    /**
     * One selected game: [folder] is where its files sit below the destination (the game folder
     * it has on the device, such as `psx`; empty = the destination itself), [parts] its files
     * relative to that folder.
     */
    data class Source(val key: String, val title: String, val consoleId: String, val folder: String, val parts: List<Part>)

    /** What the destination already has at a path: a folder, or a file of [bytes]. */
    data class Existing(val isDirectory: Boolean, val bytes: Long)

    enum class FileState {
        /** Nothing there yet: it is copied. */
        NEW,
        /** A file of the same name and size is there: it is compared byte for byte and kept as it is. */
        PRESENT
    }

    data class PlannedFile(
        /** Path below the destination. */
        val target: String,
        val source: String,
        val bytes: Long,
        val sha256: String,
        val state: FileState,
        /** Titles of the selected games that use this file (more than one = shared, copied once). */
        val games: List<String>,
        /** Their keys, in the same order. */
        val keys: List<String> = emptyList()
    ) {
        val shared: Boolean get() = games.size > 1
    }

    enum class Decision {
        /** Its files go where they are on the device. */
        COPY,
        /** It clashed and goes to [PlannedGame.root] instead. */
        RENAMED,
        /** It clashed and is left out (nothing of it is copied). */
        SKIPPED,
        /** It holds nothing but save files, so there is nothing to copy. */
        ONLY_SAVES
    }

    data class PlannedGame(
        val key: String,
        val title: String,
        val consoleId: String,
        val decision: Decision,
        /** Where its files go (below the destination); for a renamed game the new place. */
        val root: String,
        /** Destination paths that are in the way (shown with a skipped or renamed game). */
        val conflicts: List<String>,
        /** Its save and state files, which are not copied. */
        val saves: List<String>,
        /** Its files and their bytes as planned (also the ones already present or shared). */
        val files: Int,
        val bytes: Long
    )

    data class Plan(
        val games: List<PlannedGame>,
        val files: List<PlannedFile>,
        val choice: CopyConflictChoice,
        /** Free bytes at the destination; null when unknown. */
        val freeBytes: Long?
    ) {
        /** Bytes that will really be written (files not there yet). */
        val bytesToCopy: Long get() = SpaceMath.sum(files.filter { it.state == FileState.NEW }.map { it.bytes })
        val bytesPresent: Long get() = SpaceMath.sum(files.filter { it.state == FileState.PRESENT }.map { it.bytes })
        val saves: List<String> get() = games.flatMap { it.saves }
        val sharedFiles: Int get() = files.count { it.shared }
        /** Whether the destination has room for [bytesToCopy] (and [MARGIN_BYTES]); null when its free space is unknown. */
        val enoughSpace: Boolean? get() = freeBytes?.let { bytesToCopy <= SpaceMath.available(it, 0, MARGIN_BYTES) }
        val canStart: Boolean get() = files.isNotEmpty() && enoughSpace != false
    }

    private val saveExtensions = setOf(
        "srm", "sav", "dsv", "sra", "eep", "fla", "mcr", "mcd", "gci", "mem", "rtc", "sram", "mpk", "brm",
        "state", "sta", "auto", "psv", "vmp", "vms", "vmu", "nv", "nvram", "sgm", "frz"
    )
    /** Numbered states: RetroArch `.state1`, mGBA `.ss1`, others `.st1`, and `.state.auto`. */
    private val numberedState = Regex("^(state|ss|st|sa)\\d{1,3}$")
    private val saveFolders = setOf("saves", "save", "states", "savestates", "savedata", "save data", "memcards", "memory cards")

    /** A save file or save state (by extension, or because it sits in a saves / states folder). */
    fun isSaveOrState(path: String): Boolean {
        val segments = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull() ?: return false
        if (segments.dropLast(1).any { it.lowercase(Locale.ROOT) in saveFolders }) return true
        val lower = name.lowercase(Locale.ROOT)
        if (lower.endsWith(".state.auto")) return true
        val ext = lower.substringAfterLast('.', "")
        return ext.isNotEmpty() && (ext in saveExtensions || numberedState.matches(ext))
    }

    /** [folder] and [path] joined with `/`. */
    fun join(folder: String, path: String): String = listOf(folder.trim('/'), path.trim('/')).filter { it.isNotEmpty() }.joinToString("/")

    /**
     * The plan for [sources] (in this order: an earlier game wins a clash between two selected
     * games). [existing] tells what the destination holds at a path (null = nothing); it is asked
     * for the planned paths, their parent folders and, when renaming, the new names.
     */
    fun plan(
        sources: List<Source>,
        existing: (String) -> Existing?,
        choice: CopyConflictChoice = CopyConflictChoice.SKIP,
        freeBytes: Long? = null
    ): Plan {
        val cache = HashMap<String, Existing?>()
        fun at(path: String): Existing? = cache.getOrPut(ArchivePlan.portableKey(path)) { existing(path) }
        // Planned so far: portable key → (sha256, index in files).
        val planned = LinkedHashMap<String, Int>()
        val files = ArrayList<PlannedFile>()
        val games = ArrayList<PlannedGame>()

        /** Whether [target] is in the way of a file of [bytes]/[sha] (first), and the state the file would have there (second). */
        fun blocker(target: String, bytes: Long, sha: String): Pair<Boolean, FileState> {
            // A parent "folder" that is a file at the destination blocks the whole path.
            val segments = target.split('/')
            for (i in 1 until segments.size) {
                val parent = segments.subList(0, i).joinToString("/")
                val there = at(parent)
                if (there != null && !there.isDirectory) return true to FileState.NEW
                val plannedParent = planned[ArchivePlan.portableKey(parent)]
                if (plannedParent != null) return true to FileState.NEW
            }
            planned[ArchivePlan.portableKey(target)]?.let { index ->
                val other = files[index]
                return (other.sha256 != sha || other.bytes != bytes) to other.state
            }
            // A planned file must not become a folder for this one either.
            val prefix = ArchivePlan.portableKey(target) + "/"
            if (planned.keys.any { it.startsWith(prefix) }) return true to FileState.NEW
            val there = at(target) ?: return false to FileState.NEW
            return if (!there.isDirectory && there.bytes == bytes) false to FileState.PRESENT else true to FileState.NEW
        }

        for (source in sources) {
            val saves = source.parts.filter { isSaveOrState(it.path) }.map { join(source.folder, it.path) }
            val parts = source.parts.filterNot { isSaveOrState(it.path) }
            if (parts.isEmpty()) {
                games += PlannedGame(source.key, source.title, source.consoleId, Decision.ONLY_SAVES, source.folder, emptyList(), saves, 0, 0)
                continue
            }
            // Files of one game that only letter case (or a file-versus-folder name) tells apart
            // cannot all exist at a destination that ignores case: the game is left out whole.
            val ownKeys = parts.map { ArchivePlan.portableKey(join(source.folder, it.path)) }
            val clashing = parts.filterIndexed { i, _ ->
                ownKeys.indices.any { j -> j != i && (ownKeys[j] == ownKeys[i] || ownKeys[j].startsWith(ownKeys[i] + "/")) }
            }.map { join(source.folder, it.path) }
            if (clashing.isNotEmpty()) {
                games += PlannedGame(source.key, source.title, source.consoleId, Decision.SKIPPED, source.folder, clashing, saves,
                    parts.size, SpaceMath.sum(parts.map { it.bytes }))
                continue
            }
            fun attempt(paths: List<String>): List<Pair<String, FileState>>? {
                val out = ArrayList<Pair<String, FileState>>()
                for ((part, path) in parts.zip(paths)) {
                    val (blocked, state) = blocker(path, part.bytes, part.sha256)
                    if (blocked) return null
                    out += path to state
                }
                return out
            }
            val original = parts.map { join(source.folder, it.path) }
            var decision = Decision.COPY
            var root = source.folder
            var placed = attempt(original)
            val conflicts = if (placed != null) emptyList()
                else parts.zip(original).filter { (part, path) -> blocker(path, part.bytes, part.sha256).first }.map { it.second }
            if (placed == null && choice == CopyConflictChoice.RENAME) {
                for (n in 2..MAX_RENAME) {
                    val renamed = renamedPaths(source, parts.map { it.path }, n)
                    // The new folder (or file) itself must not be there at all.
                    val head = join(source.folder, renamed.first().substringBefore('/'))
                    if (at(head) != null || planned.keys.any { it == ArchivePlan.portableKey(head) || it.startsWith(ArchivePlan.portableKey(head) + "/") }) continue
                    val paths = renamed.map { join(source.folder, it) }
                    val ok = attempt(paths) ?: continue
                    if (ok.any { it.second == FileState.PRESENT }) continue
                    placed = ok
                    decision = Decision.RENAMED
                    root = if (renamed.first().contains('/')) head else source.folder
                    break
                }
            }
            if (placed == null) {
                games += PlannedGame(source.key, source.title, source.consoleId, Decision.SKIPPED, source.folder, conflicts, saves,
                    parts.size, SpaceMath.sum(parts.map { it.bytes }))
                continue
            }
            for ((part, entry) in parts.zip(placed)) {
                val (target, state) = entry
                val key = ArchivePlan.portableKey(target)
                val index = planned[key]
                if (index != null) {
                    val file = files[index]
                    if (source.key !in file.keys) files[index] = file.copy(games = file.games + source.title, keys = file.keys + source.key)
                } else {
                    planned[key] = files.size
                    files += PlannedFile(target, part.source, part.bytes, part.sha256, state, listOf(source.title), listOf(source.key))
                }
            }
            games += PlannedGame(source.key, source.title, source.consoleId, decision, root, conflicts, saves,
                parts.size, SpaceMath.sum(parts.map { it.bytes }))
        }
        return Plan(games, files, choice, freeBytes)
    }

    /**
     * The paths of a game under its [n]th name: its own top folder renamed when all its files are
     * in one; a single loose file renamed; loose files put in a new folder named after the game.
     */
    internal fun renamedPaths(source: Source, paths: List<String>, n: Int): List<String> {
        val tops = paths.map { it.substringBefore('/', "") }
        if (tops.none { it.isEmpty() } && tops.distinct().size == 1) {
            val top = tops.first()
            return paths.map { "$top ($n)/" + it.substringAfter('/') }
        }
        if (paths.size == 1) {
            val name = paths.single()
            val dot = name.lastIndexOf('.')
            return listOf(if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)")
        }
        val base = folderName(source.title)
        return paths.map { "$base ($n)/$it" }
    }

    /** A folder name made from a game's file name: no extension, nothing a memory card refuses. */
    internal fun folderName(title: String): String {
        val name = title.substringAfterLast('/').let { n ->
            val dot = n.lastIndexOf('.')
            if (dot > 0 && n.length - dot <= 5) n.substring(0, dot) else n
        }
        val clean = name.filterNot { it in "<>:\"|?*\\/" || it.isISOControl() }.trim().trimEnd('.').trim()
        return clean.ifEmpty { "Game" }.take(120)
    }
}
