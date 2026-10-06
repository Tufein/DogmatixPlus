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
        val lastMovedAt: Long? = null,
        /** Newest change of one of its files (a download, a save next to the games), null when unknown. */
        val lastChanged: Long? = null,
        /** Its biggest file. */
        val largestFile: Long = 0
    ) {
        /** The last sign of use: a play, or a file that was written (a download lands as a new file). */
        val lastActivity: Long? get() = listOfNotNull(lastPlayed, lastChanged?.takeIf { it > 0 }).maxOrNull()
    }

    enum class Reason { FAVOURITE, PLAYED, COLD }

    enum class Wait { BUSY, RESTING, NO_ROOM, LATER, BIG_FILE }

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

    /**
     * Cold only on evidence: the last play or file change is known and long ago. A console about
     * which nothing is known stays where it is.
     */
    fun isCold(c: Console, now: Long, recentDays: Int): Boolean {
        val last = c.lastActivity ?: return false
        return !c.favourite && now - last > (recentDays + COLD_MARGIN_DAYS) * DAY_MS
    }

    fun isResting(c: Console, now: Long): Boolean = c.lastMovedAt != null && now - c.lastMovedAt < REST_DAYS * DAY_MS

    /** True/false when the free space is known; unknown space (an older Android on the SD card) refuses nothing. */
    fun fits(bytes: Long, free: Long?): Boolean = free == null || free - RESERVE_BYTES - LibraryMove.RESERVE_BYTES >= bytes

    /**
     * Where each console should be. Cold consoles go to the SD card first, coldest first (that gives
     * internal room back), then hot ones come home, most recently played first. Free space is
     * counted as the plan goes on. [budgetBytes] limits one run (the first move always fits it, so a
     * big console is not stuck forever); null = no limit. With [maxFileBytes] (automatic runs) a
     * console holding a bigger file waits for a run started by hand.
     */
    fun plan(
        consoles: List<Console>,
        now: Long,
        recentDays: Int,
        internalFree: Long?,
        sdFree: Long?,
        budgetBytes: Long? = null,
        maxFileBytes: Long? = null
    ): Plan {
        val days = clampDays(recentDays)
        val out = consoles.filter { it.place == Place.INTERNAL && it.files > 0 && isCold(it, now, days) }
            .sortedWith(compareBy<Console> { it.lastActivity ?: 0L }.thenByDescending { it.bytes }.thenBy { it.id })
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
                maxFileBytes != null && c.largestFile > maxFileBytes -> Wait.BIG_FILE
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

    /** An automatic run leaves consoles with a file bigger than this to a run started by hand. */
    const val AUTO_MAX_FILE_BYTES = 2L * GB

    /**
     * The confirmed plan, checked again: only moves the user confirmed (console and direction) are
     * kept, a confirmed move that is no longer valid is dropped, nothing new is added.
     */
    fun restrictTo(plan: Plan, confirmed: Map<String, Place>): Plan =
        Plan(plan.moves.filter { confirmed[it.console.id] == it.to }, plan.waiting.filter { confirmed[it.move.console.id] == it.move.to })

    /** Size and last change of a file, as listed. */
    data class Stamp(val size: Long, val modified: Long)

    /** The original may go only when it is exactly as it was when it was copied. */
    fun unchanged(copied: Stamp, now: Stamp?): Boolean = now != null && now.size == copied.size && now.modified == copied.modified

    /**
     * Files this feature copied for one console move, so an interrupted run carries on without
     * trusting a same-name file it did not write: path → the original's stamp at copy time.
     */
    object Manifest {
        fun encode(path: String, stamp: Stamp): String = "${stamp.size}\t${stamp.modified}\t$path"

        fun decode(text: String): Map<String, Stamp> = text.lineSequence().mapNotNull { line ->
            val p = line.split('\t', limit = 3)
            if (p.size != 3 || p[2].isEmpty()) return@mapNotNull null
            val size = p[0].toLongOrNull() ?: return@mapNotNull null
            val modified = p[1].toLongOrNull() ?: return@mapNotNull null
            p[2] to Stamp(size, modified)
        }.toMap()

        /** A file at the target counts as copied only when this feature wrote it from this very original. */
        fun trusted(entry: Stamp?, original: Stamp, targetSize: Long?): Boolean =
            entry != null && entry == original && targetSize == original.size && original.size >= 0
    }

    /**
     * The tree a `content://…/tree/<id>/document/<child>` URI was granted through
     * (`content://…/tree/<id>`); the URI itself for a plain tree, null when it is no tree URI.
     */
    fun treeOf(uri: String): String? {
        val i = uri.indexOf("/tree/")
        if (i < 0) return null
        val idStart = i + "/tree/".length
        val end = uri.indexOf('/', idStart).let { if (it < 0) uri.length else it }
        if (end == idStart) return null
        return uri.substring(0, end)
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

    /**
     * A console smart storage has moved: where it is now and when it got there. [uri] = its folder
     * on the SD card. When the ES-DE system path was changed for it: [esdeWrote] is the path written,
     * [esdePrevious] the one it replaced, [esdeInserted] whether the whole system block was added.
     */
    data class Record(
        val consoleId: String,
        val place: Place,
        val movedAt: Long,
        val folder: String,
        val uri: String,
        val esdeWrote: String = "",
        val esdePrevious: String = "",
        val esdeInserted: Boolean = false
    ) {
        fun encode(): String = listOf(consoleId, place.name, movedAt.toString(), folder, uri, esdeWrote, esdePrevious, esdeInserted.toString()).joinToString(SEP)

        companion object {
            private const val SEP = "\u001F"
            fun decode(s: String): Record? {
                val p = s.split(SEP)
                if (p.size != 5 && p.size != 8) return null
                val place = runCatching { Place.valueOf(p[1]) }.getOrNull() ?: return null
                val at = p[2].toLongOrNull() ?: return null
                if (p[0].isBlank() || p[3].isBlank()) return null
                return if (p.size == 5) Record(p[0], place, at, p[3], p[4])
                else Record(p[0], place, at, p[3], p[4], p[5], p[6], p[7] == "true")
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

    /** `ROMDirectory` of ES-DE's `settings/es_settings.xml`; null when not set or not a plain path. */
    fun esdeRomDirectory(settingsXml: String): String? {
        val m = Regex("""<string\s+name="ROMDirectory"\s+value="([^"]*)"""").find(settingsXml) ?: return null
        val v = unescapeXml(m.groupValues[1]).trim()
        return v.takeIf { it.startsWith("/") && '%' !in it && '~' !in it }
    }

    /** Two absolute paths name the same folder (case and trailing slashes aside). */
    fun samePath(a: String?, b: String?): Boolean =
        a != null && b != null && a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)

    /** A patch of `custom_systems/es_systems.xml`: the new content, the path it replaced, and whether the block was added. */
    data class EsdePatch(val content: String, val previous: String, val inserted: Boolean)

    /**
     * ES-DE's `custom_systems/es_systems.xml` ([existing], null = the file does not exist) with the
     * `<path>` of [folder]'s system set to [path]. Only when the system's current path is ES-DE's
     * plain ROM folder path ([esdeRomPath]) or [ours] (what smart storage wrote before): a path the
     * user chose is never touched. The block is the custom one when there is one, otherwise ES-DE's
     * own ([bundled]) is copied in. Null when unsure or nothing changes.
     */
    fun esdePatch(existing: String?, bundled: String?, folder: String, path: String, ours: String = ""): EsdePatch? {
        val custom = existing?.let { EsdeXml.systemBlock(it, folder) }
        val source = custom ?: bundled?.let { EsdeXml.systemBlock(it, folder) } ?: return null
        val current = pathOf(source) ?: return null
        val plain = current.equals(esdeRomPath(folder), ignoreCase = true) || current.equals(esdeRomPath(folder) + "/", ignoreCase = true)
        if (!plain && (ours.isEmpty() || (current != ours && current != escapeXml(ours)))) return null
        val escaped = escapeXml(path)
        if (current == escaped) return null
        val patched = withPath(source, escaped) ?: return null
        val content = if (custom != null && existing != null) {
            existing.replace(custom, patched)
        } else {
            val base = existing ?: ("<?xml version=\"1.0\"?>\n" +
                "<!-- Systems changed by Dogmatix+; your edits are kept. -->\n" +
                "<systemList>\n</systemList>\n")
            val end = base.lastIndexOf("</systemList>")
            if (end < 0) return null
            base.substring(0, end) + "    " + patched + "\n" + base.substring(end)
        }
        return EsdePatch(content, current, inserted = custom == null)
    }

    /**
     * Undoes [esdePatch] when the console comes back: the block it added is removed again, a block
     * it changed gets [previous] back. Only while the path is still the one written ([wrote]);
     * null when the user changed it since or nothing is to be done.
     */
    fun esdeRestore(existing: String?, folder: String, wrote: String, previous: String, inserted: Boolean): String? {
        if (existing == null || wrote.isEmpty()) return null
        val block = EsdeXml.systemBlock(existing, folder) ?: return null
        if (pathOf(block) != escapeXml(wrote) && pathOf(block) != wrote) return null
        if (inserted) {
            val i = existing.indexOf(block)
            var start = i
            while (start > 0 && (existing[start - 1] == ' ' || existing[start - 1] == '\t')) start--
            var end = i + block.length
            if (end < existing.length && existing[end] == '\n') end++
            return existing.substring(0, start) + existing.substring(end)
        }
        if (previous.isEmpty()) return null
        return existing.replace(block, withPath(block, previous) ?: return null)
    }

    private fun pathOf(block: String): String? {
        val start = block.indexOf("<path>")
        val stop = block.indexOf("</path>", start + 1)
        if (start < 0 || stop < 0) return null
        return block.substring(start + 6, stop).trim()
    }

    private fun withPath(block: String, path: String): String? {
        val start = block.indexOf("<path>")
        val stop = block.indexOf("</path>", start + 1)
        if (start < 0 || stop < 0) return null
        return block.substring(0, start + 6) + path + block.substring(stop)
    }

    fun escapeXml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun unescapeXml(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
