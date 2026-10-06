package com.cortinadev.dogmatix.util

/**
 * 8.0 smart storage: keeps the consoles that are being played (or hold a favourite) on the fast
 * internal library and moves cold ones to a second library folder on the SD card.
 *
 * It works per CONSOLE, not per game: Dogmatix+ gives each console exactly one folder (the folder
 * in the download folder, or a folder of its own), and so do the frontends (ES-DE has one ROM
 * folder per system, Pegasus and Daijishō one per platform). Splitting one console over two
 * folders would hide half of its games from them. So a cold console moves as a whole folder and
 * becomes a console with a folder of its own on the SD card; when it is played again it comes back.
 *
 * The decisions are here so they can be tested: what is hot and what is cold (with a gap between
 * the two and a rest period after a move, so a console never bounces), whether both sides have
 * room, what waits because a download or a frontend run touches the console, and when a copy
 * counts as verified. Pure JVM; time is passed in.
 */
object SmartStorage {
    const val DAY_MS = 24L * 60 * 60 * 1000
    private const val GB = 1024L * 1024 * 1024

    const val DEFAULT_RECENT_DAYS = 30
    const val MIN_RECENT_DAYS = 7
    const val MAX_RECENT_DAYS = 180
    const val RECENT_DAYS_STEP = 7

    /** A console goes out only when its last play is this much older than "recent": the gap between hot and cold. */
    const val COLD_MARGIN_DAYS = 14

    /** After a move a console stays where it is for this long, whatever happens. */
    const val REST_DAYS = 14

    /** Room left free on the side that receives a console, besides the console itself. */
    const val RESERVE_BYTES = 1L * GB

    /** The most an automatic (weekly) run moves; the rest waits for the next week. */
    const val AUTO_RUN_BUDGET_BYTES = 8L * GB

    fun clampDays(days: Int): Int = days.coerceIn(MIN_RECENT_DAYS, MAX_RECENT_DAYS)

    enum class Place { INTERNAL, SD }

    /** One console with a folder that smart storage may move. */
    data class Console(
        val id: String,
        /** Its folder's name (the same on both sides). */
        val folder: String,
        val place: Place,
        val bytes: Long,
        val files: Int,
        /** Last time one of its games was played (epoch millis), null when never. */
        val lastPlayed: Long? = null,
        /** One of its games on the device is a favourite. */
        val favourite: Boolean = false,
        /** A download or a frontend run is working on it right now. */
        val busy: Boolean = false,
        /** When smart storage last moved it, null when never. */
        val lastMovedAt: Long? = null
    )

    enum class Reason { FAVOURITE, PLAYED, COLD }

    enum class Wait { BUSY, RESTING, NO_ROOM, LATER }

    data class Move(val console: Console, val to: Place, val reason: Reason)

    /** A move that would be made but waits, and why. */
    data class Waiting(val move: Move, val wait: Wait)

    data class Plan(val moves: List<Move>, val waiting: List<Waiting>) {
        val toSd: List<Move> get() = moves.filter { it.to == Place.SD }
        val toInternal: List<Move> get() = moves.filter { it.to == Place.INTERNAL }
        val bytes: Long get() = moves.sumOf { it.console.bytes }
        /** Space the plan gives back on internal storage (negative when it takes more than it gives). */
        val freesInternal: Long get() = toSd.sumOf { it.console.bytes } - toInternal.sumOf { it.console.bytes }
        val isEmpty: Boolean get() = moves.isEmpty() && waiting.isEmpty()
    }

    fun isHot(c: Console, now: Long, recentDays: Int): Boolean =
        c.favourite || (c.lastPlayed != null && now - c.lastPlayed <= recentDays * DAY_MS)

    fun isCold(c: Console, now: Long, recentDays: Int): Boolean =
        !c.favourite && (c.lastPlayed == null || now - c.lastPlayed > (recentDays + COLD_MARGIN_DAYS) * DAY_MS)

    fun isResting(c: Console, now: Long): Boolean = c.lastMovedAt != null && now - c.lastMovedAt < REST_DAYS * DAY_MS

    /** True/false when the free space is known; unknown space (an older Android on the SD card) refuses nothing. */
    fun fits(bytes: Long, free: Long?): Boolean = free == null || free - RESERVE_BYTES - LibraryMove.RESERVE_BYTES >= bytes

    /**
     * Where each console should be. Cold consoles go to the SD card first, coldest first (that gives
     * internal room back), then hot ones come home, most recently played first. Free space is
     * counted as the plan goes on. [budgetBytes] limits one run (the first move always fits it, so a
     * big console is not stuck forever); null = no limit.
     */
    fun plan(
        consoles: List<Console>,
        now: Long,
        recentDays: Int,
        internalFree: Long?,
        sdFree: Long?,
        budgetBytes: Long? = null
    ): Plan {
        val days = clampDays(recentDays)
        val out = consoles.filter { it.place == Place.INTERNAL && it.files > 0 && isCold(it, now, days) }
            .sortedWith(compareBy<Console> { it.lastPlayed ?: 0L }.thenByDescending { it.bytes }.thenBy { it.id })
            .map { Move(it, Place.SD, Reason.COLD) }
        val home = consoles.filter { it.place == Place.SD && it.files > 0 && isHot(it, now, days) }
            .sortedWith(compareByDescending<Console> { it.lastPlayed ?: Long.MIN_VALUE }.thenBy { it.id })
            .map { Move(it, Place.INTERNAL, if (it.lastPlayed != null && now - it.lastPlayed <= days * DAY_MS) Reason.PLAYED else Reason.FAVOURITE) }

        var internal = internalFree
        var sd = sdFree
        var spent = 0L
        val moves = ArrayList<Move>()
        val waiting = ArrayList<Waiting>()
        for (move in out + home) {
            val c = move.console
            val target = if (move.to == Place.SD) sd else internal
            val wait = when {
                c.busy -> Wait.BUSY
                isResting(c, now) -> Wait.RESTING
                !fits(c.bytes, target) -> Wait.NO_ROOM
                budgetBytes != null && moves.isNotEmpty() && spent + c.bytes > budgetBytes -> Wait.LATER
                else -> null
            }
            if (wait != null) { waiting += Waiting(move, wait); continue }
            moves += move
            spent += c.bytes
            if (move.to == Place.SD) { sd = sd?.minus(c.bytes); internal = internal?.plus(c.bytes) }
            else { internal = internal?.minus(c.bytes); sd = sd?.plus(c.bytes) }
        }
        return Plan(moves, waiting)
    }

    /** The newest play among [plays] (system name, last played) that belongs to a console, by [belongs]. */
    fun lastPlayed(plays: List<Pair<String, Long?>>, belongs: (String) -> Boolean): Long? =
        plays.filter { belongs(it.first) }.mapNotNull { it.second }.maxOrNull()

    /** Whether one of [favourites] (file names) is among [onDisk] (file names), compared without extension and case. */
    fun hasFavouriteOnDisk(favourites: Collection<String>, onDisk: Collection<String>): Boolean {
        if (favourites.isEmpty() || onDisk.isEmpty()) return false
        val disk = onDisk.mapTo(HashSet()) { baseOf(it) }
        return favourites.any { baseOf(it) in disk }
    }

    private fun baseOf(name: String): String = name.substringAfterLast('/').substringBeforeLast('.').trim().lowercase()

    /**
     * A console's copy is verified when the target holds every file of [expected] (path below the
     * console folder → size) with exactly that size: same count, same sizes. Only then is anything
     * of the source removed.
     */
    fun verified(expected: Map<String, Long>, found: Map<String, Long>): Boolean =
        expected.isNotEmpty() && expected.all { (path, size) -> found[path] == size }

    /** Two library folders on the same storage gain nothing from a move. Volume of a SAF document id ("1234-ABCD:Games"). */
    fun volumeOf(documentId: String): String = documentId.substringBefore(':', "").lowercase()

    // ---- What smart storage moved (kept in settings) --------------------------------------------

    /** A console smart storage has moved: where it is now and when it got there. [uri] = its folder on the SD card. */
    data class Record(val consoleId: String, val place: Place, val movedAt: Long, val folder: String, val uri: String) {
        fun encode(): String = listOf(consoleId, place.name, movedAt.toString(), folder, uri).joinToString(SEP)

        companion object {
            private const val SEP = "\u001F"
            fun decode(s: String): Record? {
                val p = s.split(SEP)
                if (p.size != 5) return null
                val place = runCatching { Place.valueOf(p[1]) }.getOrNull() ?: return null
                val at = p[2].toLongOrNull() ?: return null
                if (p[0].isBlank() || p[3].isBlank()) return null
                return Record(p[0], place, at, p[3], p[4])
            }
        }
    }

    /** How a run went, shown under the switch. */
    data class RunInfo(val at: Long, val moved: Int, val failed: Int, val bytes: Long) {
        fun encode(): String = "$at|$moved|$failed|$bytes"

        companion object {
            fun decode(s: String?): RunInfo? {
                val p = s?.split('|') ?: return null
                if (p.size != 4) return null
                return RunInfo(p[0].toLongOrNull() ?: return null, p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null, p[3].toLongOrNull() ?: return null)
            }
        }
    }

    // ---- ES-DE ----------------------------------------------------------------------------------

    /** ES-DE's own path of a system that lives in its ROM folder. */
    fun esdeRomPath(folder: String): String = "%ROMPATH%/$folder"

    /**
     * ES-DE's `custom_systems/es_systems.xml` with the `<path>` of [folder]'s system set to [path]
     * (an absolute path on the SD card, or [esdeRomPath] when it comes back). The block is the
     * custom one when there is one, otherwise the one of [bundled] (ES-DE's own definitions) is
     * copied in. Null when nothing changes or the system is unknown.
     */
    fun esdeSystemsWithPath(existing: String?, bundled: String?, folder: String, path: String): String? {
        var content = existing
            ?: ("<?xml version=\"1.0\"?>\n" +
                "<!-- Systems changed by Dogmatix+; your edits are kept. -->\n" +
                "<systemList>\n</systemList>\n")
        val custom = EsdeXml.systemBlock(content, folder)
        if (custom == null) {
            val fromBundled = bundled?.let { EsdeXml.systemBlock(it, folder) } ?: return null
            val end = content.lastIndexOf("</systemList>")
            if (end < 0) return null
            content = content.substring(0, end) + "    " + fromBundled + "\n" + content.substring(end)
        }
        val block = EsdeXml.systemBlock(content, folder) ?: return null
        val start = block.indexOf("<path>")
        val stop = block.indexOf("</path>", start + 1)
        if (start < 0 || stop < 0) return null
        if (block.substring(start + 6, stop).trim() == path) return if (custom == null) content else null
        val patched = block.substring(0, start + 6) + path + block.substring(stop)
        return content.replace(block, patched)
    }
}
