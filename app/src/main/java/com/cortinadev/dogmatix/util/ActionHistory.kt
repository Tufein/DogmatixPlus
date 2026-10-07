package com.cortinadev.dogmatix.util

import java.time.LocalDate
import java.time.ZoneId
import java.util.EnumMap

/** Which entries the chips of the history screen show. */
enum class ActionFilter(val kinds: Set<ActionKind>?) {
    ALL(null),
    DOWNLOADS(setOf(ActionKind.DOWNLOADED, ActionKind.DOWNLOAD_FAILED)),
    TRASH(setOf(ActionKind.REMOVED, ActionKind.RESTORED, ActionKind.PURGED)),
    MOVES(setOf(ActionKind.MOVED, ActionKind.MOVE_FAILED)),
    SYNC(setOf(ActionKind.SYNCED, ActionKind.SYNC_FAILED, ActionKind.BACKED_UP, ActionKind.BACKUP_FAILED, ActionKind.BACKUP_RESTORED)),
    GAMES(setOf(ActionKind.PLAYED, ActionKind.PINNED, ActionKind.UNPINNED));

    fun accepts(kind: ActionKind): Boolean = kinds == null || kind in kinds

    /** The chip to the right / left (wrapping), for LB / RB on a gamepad. */
    fun step(delta: Int): ActionFilter = entries[((ordinal + delta) % entries.size + entries.size) % entries.size]
}

/**
 * A way back the history can offer for a line. [RESTORE] puts a removed game back from the trash
 * (through the recovery journal); [DOWNLOAD_AGAIN] queues a game whose trash was emptied, or whose
 * download failed, again from the library.
 */
enum class UndoAction { RESTORE, DOWNLOAD_AGAIN }

/** One day of the history, newest entry first. */
data class ActionDay(val label: DayLabel, val date: LocalDate, val entries: List<ActionEntry>)

/** Filtering, searching, day grouping and the ways back of the action history. Pure JVM; time and zone are passed in. */
object ActionHistory {

    /** [entries] (newest first) through the chip and the search words; every word must be in the title, the file name or the console. */
    fun filter(entries: List<ActionEntry>, filter: ActionFilter, query: String): List<ActionEntry> {
        val words = SearchMatch.words(query)
        return entries.filter { e -> filter.accepts(e.kind) && (words.isEmpty() || matches(e, words)) }
    }

    private fun matches(entry: ActionEntry, words: List<String>): Boolean {
        val haystack = SearchNormalizer.key(listOfNotNull(entry.title, entry.fileName, entry.consoleId).joinToString(" "))
        return words.all { it in haystack }
    }

    /** [entries] (newest first) in consecutive day groups. */
    fun groupByDay(entries: List<ActionEntry>, now: Long, zone: ZoneId): List<ActionDay> {
        val today = PlayHistory.dayOf(now, zone)
        val out = ArrayList<ActionDay>()
        var currentDate: LocalDate? = null
        var bucket = ArrayList<ActionEntry>()
        fun flush() {
            val d = currentDate ?: return
            out += ActionDay(PlayHistory.labelFor(d, today), d, bucket)
            bucket = ArrayList()
        }
        for (entry in entries) {
            val d = PlayHistory.dayOf(entry.at, zone)
            if (d != currentDate) { flush(); currentDate = d }
            bucket += entry
        }
        flush()
        return out
    }

    /**
     * The game page can open for a line that names a library game by its library file name (a
     * finished download, a version pin); other lines name files on disk, which the page cannot open by.
     */
    fun openable(entry: ActionEntry): Boolean =
        !entry.consoleId.isNullOrBlank() && !entry.fileName.isNullOrBlank() && !entry.isSummary &&
            entry.kind in setOf(ActionKind.DOWNLOADED, ActionKind.PINNED, ActionKind.UNPINNED)

    /**
     * The way back each line may offer, by [ActionEntry.id], from the log alone (newest first); the
     * caller still checks that it works right now (the operation is still in the trash, the game
     * is still in the library). A removal offers Restore until a later line restored or purged
     * that operation; a purge offers Download again; a failed download offers it too unless the
     * same file downloaded later. Each game is offered once: only its newest line gets it.
     */
    fun undoCandidates(newestFirst: List<ActionEntry>): Map<String, UndoAction> {
        // In the order of the lines, newest first: the caller checks only the newest few.
        val out = LinkedHashMap<String, UndoAction>()
        val closedOps = HashSet<String>()
        val laterFiles = HashSet<String>()
        for (e in newestFirst) {
            val file = e.fileName?.takeIf { it.isNotBlank() }
            when (e.kind) {
                ActionKind.REMOVED -> if (!e.undone && e.opId != null && e.opId !in closedOps) out[e.id] = UndoAction.RESTORE
                ActionKind.PURGED -> if (!e.undone && file != null && file !in laterFiles) out[e.id] = UndoAction.DOWNLOAD_AGAIN
                ActionKind.DOWNLOAD_FAILED -> if (!e.undone && file != null && !e.isSummary && file !in laterFiles) out[e.id] = UndoAction.DOWNLOAD_AGAIN
                else -> Unit
            }
            if (e.kind == ActionKind.RESTORED || e.kind == ActionKind.PURGED) e.opId?.let(closedOps::add)
            // Anything newer about this file (downloaded again, removed, offered already) ends older offers.
            if (file != null && e.kind != ActionKind.PLAYED && e.kind != ActionKind.PINNED && e.kind != ActionKind.UNPINNED) laterFiles += file
        }
        return out
    }
}

/**
 * Keeps downloads from flooding the history (the log holds [ActionLogFormat.MAX_ENTRIES] lines, a
 * queue of 500 games must not push real history out). Per kind (finished, failed), a run writes
 * its first [perRun] lines one by one and at most [perHour] in any hour; every further one is only
 * counted and comes out as one summary line ("37 more games downloaded", with their size) when the
 * run ends ([endRun]: the queue has nothing queued or running any more) or, for a long run, every
 * [flushEveryMs] ([tick]), so a slow queue of hundreds of games is a handful of lines and a crash
 * loses at most that much of the count. Not thread-safe; the caller synchronizes. Times are passed in.
 */
class DownloadRunTally(
    private val perRun: Int = PER_RUN,
    private val perHour: Int = PER_HOUR,
    private val flushEveryMs: Long = FLUSH_EVERY_MS
) {
    private class Tally { var written = 0; var folded = 0; var bytes = 0L; val recent = ArrayDeque<Long>() }

    private val tallies = EnumMap<ActionKind, Tally>(ActionKind::class.java)
    private var lastFlush = Long.MIN_VALUE

    /** True when [entry] is to be written now, false when it was counted into the next summary. */
    fun accept(entry: ActionEntry, now: Long): Boolean {
        if (lastFlush == Long.MIN_VALUE) lastFlush = now
        val t = tallies.getOrPut(entry.kind) { Tally() }
        while (t.recent.isNotEmpty() && t.recent.first() <= now - HOUR_MS) t.recent.removeFirst()
        if (t.written < perRun && t.recent.size < perHour) {
            t.written++
            t.recent.addLast(now)
            return true
        }
        t.folded++
        t.bytes += entry.bytes
        return false
    }

    /** The queue went idle: the summary lines of the run (title empty, count set); the next run starts fresh. */
    fun endRun(now: Long, newId: () -> String): List<ActionEntry> {
        val lines = summaries(now, newId)
        tallies.values.forEach { it.written = 0 }
        lastFlush = Long.MIN_VALUE
        return lines
    }

    /** Summary lines due during a long run (every [flushEveryMs]); the run itself goes on. */
    fun tick(now: Long, newId: () -> String): List<ActionEntry> {
        if (lastFlush == Long.MIN_VALUE || now - lastFlush < flushEveryMs) return emptyList()
        return summaries(now, newId)
    }

    /** Whether anything is waiting to be summed up. */
    val pending: Boolean get() = tallies.values.any { it.folded > 0 }

    private fun summaries(now: Long, newId: () -> String): List<ActionEntry> {
        lastFlush = now
        return tallies.entries.filter { it.value.folded > 0 }.map { (kind, t) ->
            val line = ActionEntry(newId(), now, kind, count = t.folded, bytes = t.bytes)
            t.folded = 0
            t.bytes = 0L
            line
        }
    }

    companion object {
        const val PER_RUN = 8
        const val PER_HOUR = 30
        const val FLUSH_EVERY_MS = 30 * 60 * 1000L
        private const val HOUR_MS = 60 * 60 * 1000L
    }
}
