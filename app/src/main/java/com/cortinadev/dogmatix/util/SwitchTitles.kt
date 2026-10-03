package com.cortinadev.dogmatix.util

/**
 * Nintendo Switch title IDs in file names (`Game [0100ABCD12340000][v0].nsp`): which game a base
 * game, update or DLC belongs to, and which update version a file holds.
 *
 * A title ID is 16 hex digits. The base game's ID has its lowest 13 bits clear; its update is the
 * base ID + 0x800, its DLC are the base ID + 0x1000 + n (n ≥ 1). So the base of any ID is the ID
 * with the lowest 13 bits cleared. Update versions are counted in steps of 65536 (`v65536` is the
 * first update, `v131072` the second, …).
 */
object SwitchTitles {

    enum class Kind { BASE, UPDATE, DLC }

    data class Title(val id: String, val baseId: String, val kind: Kind, val version: Long?) {
        /** The update's number (1 for v65536); 0 for the base game's own version. */
        val release: Long? get() = version?.let { it / 65_536 }
    }

    /** Container formats of Switch games. */
    val EXTENSIONS = setOf("nsp", "nsz", "xci", "xcz")

    private val idPattern = Regex("""[\[(]\s*(0100[0-9A-Fa-f]{12})\s*[\])]""")
    private val versionPattern = Regex("""[\[(]\s*v(\d{1,10})\s*[\])]""", RegexOption.IGNORE_CASE)

    fun parse(fileName: String): Title? {
        val name = FileParsingUtils.decodeUrlEncodedFileName(fileName)
        val id = idPattern.find(name)?.groupValues?.get(1)?.uppercase() ?: return null
        val value = id.toULongOrNull(16) ?: return null
        val low = (value and 0x1FFFuL).toInt()
        val kind = when {
            low == 0 -> Kind.BASE
            low == 0x800 -> Kind.UPDATE
            low > 0x1000 -> Kind.DLC
            else -> return null
        }
        val base = (value and 0x1FFFuL.inv()).toString(16).uppercase().padStart(16, '0')
        return Title(id, base, kind, versionPattern.find(name)?.groupValues?.get(1)?.toLongOrNull())
    }

    /** True for a file in a Switch container format. */
    fun isSwitchFile(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** Updates and DLC of one game, as far as the library lists them and the disk holds them. */
    data class GameStatus<T>(
        val baseId: String,
        /** The base game's library row, if a source lists it. */
        val base: T?,
        val baseOwned: Boolean,
        /** Highest update version on disk; null = none. */
        val ownedUpdate: Long?,
        /** The newest update a source lists; null = none. */
        val newestUpdate: Pair<T, Long>?,
        /** DLC a source lists that are not on disk. */
        val missingDlc: List<T>,
        val dlcInLibrary: Int,
        val dlcOwned: Int
    ) {
        /** A newer update than the one on disk is in the library. */
        val updateAvailable: Boolean get() = newestUpdate != null && newestUpdate.second > (ownedUpdate ?: -1)
        val hasWork: Boolean get() = updateAvailable || missingDlc.isNotEmpty()
    }

    /**
     * Per game that has a title ID: what the library offers ([rows]) against what is on disk
     * ([ownedNames], plain file names). [onlyOwned] keeps the games whose base game is on disk.
     */
    fun <T> analyse(rows: List<T>, nameOf: (T) -> String, ownedNames: Collection<String>, onlyOwned: Boolean = true): List<GameStatus<T>> {
        val owned = ownedNames.mapNotNull { parse(it) }
        val ownedIds = owned.map { it.id }.toSet()
        val ownedBases = owned.filter { it.kind == Kind.BASE }.map { it.baseId }.toSet()
        val ownedUpdates = owned.filter { it.kind == Kind.UPDATE }.groupBy { it.baseId }.mapValues { (_, l) -> l.maxOf { it.version ?: 0L } }
        val parsed = rows.mapNotNull { row -> parse(nameOf(row))?.let { row to it } }
        return parsed.groupBy { it.second.baseId }.mapNotNull { (baseId, list) ->
            val baseOwned = baseId in ownedBases
            if (onlyOwned && !baseOwned) return@mapNotNull null
            val base = list.firstOrNull { it.second.kind == Kind.BASE }?.first
            val newest = list.filter { it.second.kind == Kind.UPDATE }.maxByOrNull { it.second.version ?: 0L }?.let { it.first to (it.second.version ?: 0L) }
            val dlc = list.filter { it.second.kind == Kind.DLC }.distinctBy { it.second.id }
            GameStatus(
                baseId = baseId, base = base, baseOwned = baseOwned, ownedUpdate = ownedUpdates[baseId], newestUpdate = newest,
                missingDlc = dlc.filter { it.second.id !in ownedIds }.map { it.first },
                dlcInLibrary = dlc.size, dlcOwned = dlc.count { it.second.id in ownedIds }
            )
        }
    }
}
