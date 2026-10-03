package com.cortinadev.dogmatix.util

/** An `.m3u` that would let an emulator treat the discs of one game as one game. */
data class PlaylistPlan(
    val consoleId: String?,
    /** Readable folder, for the list. */
    val folder: String,
    /** SAF document URI of the folder to create the playlist in. */
    val dirUri: String,
    val dirId: String,
    /** `Final Fantasy VII (USA).m3u` */
    val fileName: String,
    /** The disc files in order, one per line in the playlist. */
    val discs: List<String>
) {
    val id: String get() = "$dirId|$fileName"
    val content: String get() = discs.joinToString("\n", postfix = "\n")
}

/**
 * Finds games with several discs in one folder that have no playlist yet (`Game (Disc 1).cue`,
 * `Game (Disc 2).cue` …). A disc counts through its sheet file (`.cue`, `.gdi`, …) or its single
 * image (`.chd`, `.iso`, `.pbp`…); the tracks behind a sheet are never listed. Games that already
 * have a playlist of that name, and games with only one disc, are left alone.
 */
object PlaylistPlanner {

    private val discTag = Regex("""(?i)\s*[(\[]\s*(?:disc|disk|cd)\s*(\d+|[ivx]+)(?:\s*of\s*\d+)?\s*[)\]]""")
    private val trailingDisc = Regex("""(?i)[\s_-]+(?:disc|disk|cd)\s*(\d+)$""")
    /** Which file represents a disc, best first: a sheet describes the whole disc, an image is the disc. */
    private val representatives = listOf("cue", "gdi", "ccd", "mds", "toc", "chd", "iso", "pbp", "cso", "rvz", "wbfs", "img")

    fun plan(files: List<DiskFile>): List<PlaylistPlan> {
        val plans = mutableListOf<PlaylistPlan>()
        for ((dirId, inFolder) in files.groupBy { it.dirId }) {
            val existing = inFolder.filter { it.name.endsWith(".m3u", ignoreCase = true) }.map { it.name.lowercase() }.toSet()
            val byGame = LinkedHashMap<String, MutableMap<Int, MutableList<DiskFile>>>()
            for (file in inFolder) {
                val ext = file.name.substringAfterLast('.', "").lowercase()
                if (ext !in representatives) continue
                val stem = file.name.substringBeforeLast('.')
                val (game, disc) = split(stem) ?: continue
                byGame.getOrPut(game) { sortedMapOf() }.getOrPut(disc) { mutableListOf() }.add(file)
            }
            for ((game, discs) in byGame) {
                if (discs.size < 2) continue
                val name = "$game.m3u"
                if (name.lowercase() in existing) continue
                val listed = discs.toSortedMap().values.map { options ->
                    options.minBy { representatives.indexOf(it.name.substringAfterLast('.').lowercase()) }.name
                }
                val first = inFolder.first()
                plans += PlaylistPlan(first.consoleId, first.folder, first.dirUri, dirId, name, listed)
            }
        }
        return plans.sortedWith(compareBy({ it.folder.lowercase() }, { it.fileName.lowercase() }))
    }

    /** `Game (USA) (Disc 2)` → ("Game (USA)", 2); null when the name has no disc number. */
    fun split(stem: String): Pair<String, Int>? {
        discTag.find(stem)?.let { m ->
            val number = discNumber(m.groupValues[1]) ?: return null
            return stem.replaceRange(m.range, "").trim().ifEmpty { return null } to number
        }
        trailingDisc.find(stem)?.let { m ->
            val number = m.groupValues[1].toIntOrNull() ?: return null
            return stem.substring(0, m.range.first).trim().ifEmpty { return null } to number
        }
        return null
    }

    private fun discNumber(value: String): Int? =
        value.toIntOrNull() ?: listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x").indexOf(value.lowercase()).takeIf { it >= 0 }?.plus(1)
}
