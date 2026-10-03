package com.cortinadev.dogmatix.util

import java.text.Normalizer

/**
 * A file found on disk by the library scan.
 * [scope] says which console it belongs to: the console id when the folder could be matched,
 * otherwise `folder:<name>` for an unknown top-level folder or `""` for loose files in the root.
 */
data class DiskFile(
    val scope: String,
    val consoleId: String?,
    /** Readable path of the folder holding the file, relative to where the scan started (".../ROMs/gba/USA"). */
    val folder: String,
    val name: String,
    val size: Long,
    /** SAF document URI, kept as a string so this model stays free of Android types. */
    val uri: String
)

/**
 * One game on disk: the files in the same folder that share a base name ("Game.cue" + "Game.bin")
 * plus the "(Track N)" files that belong to it, so a CD image counts once.
 */
data class GameEntry(
    val scope: String,
    val consoleId: String?,
    val folder: String,
    /** Base name without extension or track suffix, as shown to the user. */
    val baseName: String,
    val files: List<DiskFile>
) {
    val size: Long get() = files.sumOf { it.size }
}

data class DuplicateGroup(
    val scope: String,
    val consoleId: String?,
    /** Cleaned title shared by the entries ("Chrono Trigger"). */
    val title: String,
    val kind: Kind,
    /** Largest first, so the copy most likely worth keeping leads the list. */
    val entries: List<GameEntry>
) {
    enum class Kind {
        /** The same file name exists in more than one folder of the same console. */
        IDENTICAL,
        /** Different releases of the same game: regions, revisions, translations. */
        VARIANT
    }

    /** Space freed by keeping only the largest entry. */
    val reclaimable: Long get() = entries.sumOf { it.size } - (entries.maxOfOrNull { it.size } ?: 0L)
}

/**
 * Finds games that are on disk more than once. Pure logic over [DiskFile]s so it can be unit
 * tested; the disk walk lives in `LibraryScanService`.
 *
 * Two entries are duplicates when, within the same console, their titles match after
 * [GameTitleCleaner] drops tags, regions and versions. Disc numbers are part of the key, so
 * "Disc 1" and "Disc 2" of one game are never reported against each other.
 */
object DuplicateFinder {

    /** Files that are never games: shortcuts, artwork, saves, notes, our own temp files. */
    private val ignoredExtensions = setOf(
        "dgmtx", "tmp", "part", "txt", "nfo", "md", "xml", "json", "html", "url", "lnk", "ini", "cfg", "db",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "mp4", "mkv", "pdf",
        "sav", "srm", "state", "sta", "mcr", "mcd"
    )

    private val trackSuffix = Regex("(?i)\\s*\\(track\\s*\\d+\\)\\s*$")
    private val discTag = Regex("(?i)\\b(disc|disk|cd|side)\\s*([0-9]+|[a-z])\\b")

    fun isGameFile(name: String): Boolean {
        if (name.startsWith(".")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext !in ignoredExtensions
    }

    /** Groups [files] into games (see [GameEntry]); files that are not games are dropped. */
    fun entries(files: List<DiskFile>): List<GameEntry> =
        files.filter { isGameFile(it.name) }
            .groupBy { Triple(it.scope, it.folder, entryKey(it.name)) }
            .map { (key, group) ->
                val first = group.first()
                GameEntry(
                    scope = key.first,
                    consoleId = first.consoleId,
                    folder = key.second,
                    baseName = baseName(group.minBy { it.name.length }.name).replace(trackSuffix, ""),
                    files = group.sortedBy { it.name.lowercase() }
                )
            }

    fun find(files: List<DiskFile>): List<DuplicateGroup> =
        entries(files)
            .groupBy { it.comparisonScope() to titleKey(it.baseName) }
            .filter { (key, group) -> key.second.isNotEmpty() && group.size > 1 }
            .map { (key, group) ->
                val sameName = group.groupBy { it.baseName.lowercase() }.any { it.value.size > 1 }
                DuplicateGroup(
                    scope = key.first,
                    consoleId = group.first().consoleId,
                    title = GameTitleCleaner.clean(group.first().baseName).ifBlank { group.first().baseName },
                    kind = if (sameName) DuplicateGroup.Kind.IDENTICAL else DuplicateGroup.Kind.VARIANT,
                    entries = group.sortedByDescending { it.size }
                )
            }
            .sortedWith(compareByDescending<DuplicateGroup> { it.reclaimable }.thenBy { it.title.lowercase() })

    /** Comparison key: cleaned, accent-free, alphanumeric title plus the disc number if any. */
    fun titleKey(baseName: String): String {
        val disc = discTag.find(baseName)?.groupValues?.get(2)?.lowercase()?.trimStart('0')
        val title = normalize(GameTitleCleaner.clean("$baseName.x"))
        if (title.isEmpty()) return ""
        return if (disc.isNullOrEmpty()) title else "$title#d$disc"
    }

    /**
     * Files outside any console folder (loose in the root, unknown folders) may belong to any
     * system: there the extension stands in for the console, so "Tetris.gb" and "Tetris.nes"
     * are not reported against each other.
     */
    private fun GameEntry.comparisonScope(): String =
        if (consoleId != null) scope
        else scope + "|" + files.maxBy { it.size }.name.substringAfterLast('.', "").lowercase()

    private fun entryKey(fileName: String): String = baseName(fileName).replace(trackSuffix, "").lowercase()

    private fun baseName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^a-z0-9]"), "")
}
