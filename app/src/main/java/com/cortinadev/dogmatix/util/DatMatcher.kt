package com.cortinadev.dogmatix.util

/** A file on disk as the DAT check sees it: its own hashes, or (a ZIP) the CRC and size of each file inside. */
data class ScannedFile(
    val name: String,
    val size: Long,
    val crc: String? = null,
    val sha1: String? = null,
    val zipEntries: List<ZipEntryInfo>? = null
)

enum class DatStatus {
    /** Matches the DAT and has the DAT's name. */
    VERIFIED,
    /** Matches the DAT under another name; [DatCheck.canonicalName] is the right one. */
    MISNAMED,
    /** Not in the DAT: a bad or modified dump, or a game the DAT does not list. */
    UNKNOWN,
    /** Not checked: a format DATs do not describe (CHD, RVZ, 7z…). */
    SKIPPED
}

data class DatCheck(val fileName: String, val status: DatStatus, val gameName: String? = null, val canonicalName: String? = null)

/** One DAT entry for matching: [game] is the game's name, [rom] the file's. */
data class DatEntry(val game: String, val rom: String, val size: Long, val crc: String?, val sha1: String?)

/**
 * Matches files against a DAT by hash: SHA-1 when both sides have one, else CRC-32 with the size.
 * A ZIP counts when every file inside belongs to the same game; its right name is the game's
 * name + `.zip`. A loose file's right name is the DAT's file name.
 */
class DatMatcher(entries: List<DatEntry>) {
    private val bySha1 = entries.filter { it.sha1 != null }.groupBy { it.sha1!! }
    private val byCrc = entries.filter { it.crc != null }.groupBy { crcKey(it.crc!!, it.size) }
    private val games = entries.groupBy { it.game }

    /** Games of the DAT. */
    val gameCount: Int get() = games.size

    fun check(file: ScannedFile): DatCheck {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        file.zipEntries?.let { return checkZip(file, it) }
        if (ext in NOT_IN_DATS) return DatCheck(file.name, DatStatus.SKIPPED)
        val hit = file.sha1?.let { bySha1[it.lowercase()] } ?: file.crc?.let { byCrc[crcKey(it, file.size)] }
        if (hit.isNullOrEmpty()) return DatCheck(file.name, DatStatus.UNKNOWN)
        val same = hit.firstOrNull { it.rom.equals(file.name, ignoreCase = true) }
        val entry = same ?: hit.first()
        return if (same != null) DatCheck(file.name, DatStatus.VERIFIED, entry.game, entry.rom)
        else DatCheck(file.name, DatStatus.MISNAMED, entry.game, entry.rom)
    }

    private fun checkZip(file: ScannedFile, inside: List<ZipEntryInfo>): DatCheck {
        if (inside.isEmpty()) return DatCheck(file.name, DatStatus.UNKNOWN)
        val candidates = inside.map { e -> byCrc[crcKey(e.crc, e.size)].orEmpty().map { it.game }.toSet() }
        if (candidates.any { it.isEmpty() }) return DatCheck(file.name, DatStatus.UNKNOWN)
        val common = candidates.reduce { a, b -> a intersect b }
        if (common.isEmpty()) return DatCheck(file.name, DatStatus.UNKNOWN)
        val base = file.name.substringBeforeLast('.')
        val game = common.firstOrNull { it.equals(base, ignoreCase = true) } ?: common.first()
        val canonical = "$game.zip"
        return DatCheck(file.name, if (game.equals(base, ignoreCase = true)) DatStatus.VERIFIED else DatStatus.MISNAMED, game, canonical)
    }

    /** Games of the DAT that none of [checks] matched. */
    fun missing(checks: List<DatCheck>): List<String> {
        val found = checks.mapNotNull { it.gameName }.toSet()
        return games.keys.filter { it !in found }.sorted()
    }

    companion object {
        /** What DATs do not describe: compressed images, other archives, saves and side files. */
        val NOT_IN_DATS = setOf(
            "chd", "rvz", "wia", "gcz", "cso", "zso", "pbp", "7z", "rar", "nsp", "nsz", "xci", "xcz",
            "sav", "srm", "sa1", "eep", "fla", "rtc", "state", "bak", "m3u", "txt", "nfo", "cfg", "ini", "jpg", "png", "xml", "dat"
        )

        /** Formats whose own hash is worth computing (everything else that is not a ZIP or skipped). */
        fun needsHash(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext != "zip" && ext !in NOT_IN_DATS
        }

        private fun crcKey(crc: String, size: Long) = crc.lowercase().padStart(8, '0') + "|" + size

        /** A file name a DAT gives, made safe to use on Android storage (no path separators or reserved characters). */
        fun safeFileName(name: String): String = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(250)
    }
}
