package com.cortinadev.dogmatix.util

/** What a disc sheet (`.cue`, `.gdi`, `.m3u`) refers to, read from its text. */
object SheetParser {
    private val cueFile = Regex("""(?im)^\s*FILE\s+(?:"([^"]+)"|(\S+))""")
    private val quoted = Regex(""""([^"]+)"""")

    /** Files a cue sheet names: `FILE "Game (Track 1).bin" BINARY`. */
    fun cueFiles(text: String): List<String> =
        cueFile.findAll(text).map { (it.groupValues[1].ifEmpty { it.groupValues[2] }).trim() }.filter { it.isNotEmpty() }.distinct().toList()

    /** Files a Dreamcast gdi names: first line = track count, then `1 0 4 2352 track01.bin 0`. */
    fun gdiFiles(text: String): List<String> =
        text.lineSequence().drop(1).map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            quoted.find(line)?.groupValues?.get(1)
                ?: line.split(Regex("""\s+""")).getOrNull(4)
        }.filter { it.isNotEmpty() }.distinct().toList()

    /** Entries of a playlist: every non-comment line (paths may point into sub-folders). */
    fun m3uFiles(text: String): List<String> =
        text.lineSequence().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct().toList()

    fun isSheet(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in setOf("cue", "gdi", "m3u")

    /** Last path segment, for entries like `Disc 1/Game.cue` or `..\Game.cue`. */
    fun leafName(reference: String): String = reference.replace('\\', '/').substringAfterLast('/')
}

/** Something wrong with a game made of several files. */
data class SetProblem(
    val kind: Kind,
    /** The sheet file that lists the parts. */
    val sheet: DiskFile,
    /** Names the sheet lists that are not next to it (empty for [Kind.EMPTY_SHEET]). */
    val missing: List<String>
) {
    enum class Kind {
        /** A cue / gdi names tracks that are not in the folder. */
        MISSING_TRACKS,
        /** A playlist names discs that are not in the folder. */
        MISSING_DISCS,
        /** A sheet that names no files at all (or an unreadable one). */
        EMPTY_SHEET
    }
}

/**
 * Looks for disc images that cannot run: a `.cue` whose `.bin` is gone, a `.gdi` with a missing
 * track, a `.m3u` that names a disc that was deleted. Pure over the scanned files; the sheets'
 * texts come from [read] (null = could not be read, which is reported as a broken sheet).
 */
object SetChecker {
    /** Sheets bigger than this are not sheets. */
    const val MAX_SHEET_BYTES = 256L * 1024

    fun check(files: List<DiskFile>, read: (DiskFile) -> String?): List<SetProblem> {
        val byFolder = files.groupBy { it.dirId }
        val problems = mutableListOf<SetProblem>()
        for (file in files) {
            val ext = file.name.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("cue", "gdi", "m3u") || file.size > MAX_SHEET_BYTES) continue
            val text = read(file)
            val neighbours = byFolder[file.dirId].orEmpty().map { it.name.lowercase() }.toSet()
            val referenced = when {
                text == null -> { problems += SetProblem(SetProblem.Kind.EMPTY_SHEET, file, emptyList()); continue }
                ext == "cue" -> SheetParser.cueFiles(text)
                ext == "gdi" -> SheetParser.gdiFiles(text)
                else -> SheetParser.m3uFiles(text)
            }
            if (referenced.isEmpty()) { problems += SetProblem(SetProblem.Kind.EMPTY_SHEET, file, emptyList()); continue }
            // Entries pointing into other folders cannot be checked from this folder alone.
            val local = referenced.filter { !it.replace('\\', '/').contains('/') }
            val missing = local.filter { it.lowercase() !in neighbours }
            if (missing.isNotEmpty()) {
                problems += SetProblem(if (ext == "m3u") SetProblem.Kind.MISSING_DISCS else SetProblem.Kind.MISSING_TRACKS, file, missing)
            }
        }
        return problems.sortedWith(compareBy({ it.sheet.folder.lowercase() }, { it.sheet.name.lowercase() }))
    }
}
