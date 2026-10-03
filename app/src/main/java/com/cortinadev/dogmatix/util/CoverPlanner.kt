package com.cortinadev.dogmatix.util

/** A cover image the front end (ES-DE) is missing for a game that is on the device. */
data class CoverJob(
    val system: String,
    /** File name without extension the front end looks for (the game's file name stem). */
    val stem: String,
    /** Path below the server's resources, e.g. `roms/3/15/cover/big.png`. */
    val coverPath: String
) {
    val extension: String get() = coverPath.substringBefore('?').substringAfterLast('.', "png").lowercase().takeIf { it.length in 2..4 } ?: "png"
    val fileName: String get() = "$stem.$extension"
}

/** One game the server lists, with its cover. */
data class CoverSource(val romStem: String, val coverPath: String)

object CoverPlanner {

    /** The console folder name a file sits in (ES-DE's system name): the first folder that names this console. */
    fun systemOf(file: DiskFile): String? {
        val consoleId = file.consoleId ?: return null
        return file.folder.replace('\\', '/').split('/').map { it.trim() }
            .firstOrNull { it.isNotEmpty() && ConsoleFolderAliases.matches(consoleId, it) }
    }

    /**
     * Covers to fetch: games on the device ([deviceGames]: system → file stems) that the server has
     * a cover for and that [existing] (system → stems with a cover already) lacks. Games are paired
     * by the lower-cased stem of their file name.
     */
    fun plan(
        deviceGames: Map<String, Collection<String>>,
        server: Map<String, List<CoverSource>>,
        existing: Map<String, Set<String>>
    ): List<CoverJob> = deviceGames.flatMap { (system, stems) ->
        val covers = server[system].orEmpty().filter { it.coverPath.isNotBlank() }.associateBy { it.romStem.lowercase() }
        val have = existing[system].orEmpty()
        stems.distinct().mapNotNull { stem ->
            val key = stem.lowercase()
            if (key in have) null else covers[key]?.let { CoverJob(system, stem, it.coverPath) }
        }
    }

    /** Where the server keeps a cover file. */
    fun coverUrl(baseUrl: String, coverPath: String): String =
        baseUrl.trimEnd('/') + "/assets/romm/resources/" + coverPath.trimStart('/').replace(" ", "%20")
}
