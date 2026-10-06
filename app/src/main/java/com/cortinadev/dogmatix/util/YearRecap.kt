package com.cortinadev.dogmatix.util

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which stretch of time a recap covers. */
sealed interface RecapPeriod {
    /** A calendar year, January to December (the current year runs up to today). */
    data class Year(val year: Int) : RecapPeriod
    /** The current month and the 11 before it. */
    data object Last12Months : RecapPeriod
}

/** A finished download from the Downloads history (it knows the game's name). */
data class RecapDownload(val at: Long, val consoleId: String, val fileName: String, val title: String, val bytes: Long)

/** A game ES-DE says was played: when it was last played and how often in all. */
data class RecapPlay(val at: Long, val consoleId: String, val fileName: String, val title: String, val count: Int)

/**
 * What the recap is built from (see RecapService).
 *
 * @property downloads the Downloads history: names, but the user can clear it.
 * @property log the statistics log: one line per finished download, never cleared, without names.
 * @property plays what ES-DE recorded, one entry per game (it only keeps the LAST time a game was
 *   played); null when ES-DE is not set up, so "played" is unknown rather than zero.
 */
data class RecapInput(
    val downloads: List<RecapDownload>,
    val log: List<DownloadLogEntry>,
    val plays: List<RecapPlay>?
)

data class RecapConsole(val consoleId: String, val downloads: Int, val played: Int, val bytes: Long) {
    val total: Int get() = downloads + played
}

data class RecapGame(val consoleId: String, val fileName: String, val title: String, val plays: Int, val lastAt: Long)
data class RecapMonth(val month: YearMonth, val count: Int)
data class RecapDay(val day: LocalDate, val count: Int)
data class RecapStreak(val days: Int, val from: LocalDate, val to: LocalDate)

/** The numbers of "Your year in games". */
data class YearRecap(
    val period: RecapPeriod,
    /** First and last day the recap covers (the current year ends today). */
    val from: LocalDate,
    val to: LocalDate,
    val downloaded: Int,
    /** Games ES-DE says were played in the period; null when ES-DE is not set up. */
    val played: Int?,
    val bytes: Long,
    /** Games downloaded in the period that nothing in the history knew before. */
    val newToYou: Int,
    /** Days with at least one download or play. */
    val activeDays: Int,
    val topConsoles: List<RecapConsole>,
    val mostPlayed: RecapGame?,
    val busiestMonth: RecapMonth?,
    val busiestDay: RecapDay?,
    val longestStreak: RecapStreak?,
    /** The 12 months of the chart (a year: January to December; else the last 12 months) and what happened in each. */
    val months: List<RecapMonth>
) {
    val isEmpty: Boolean get() = downloaded == 0 && (played ?: 0) == 0
}

/**
 * Builds the recap of a year or of the last 12 months from the play history and the download
 * data. Pure JVM; time and zone are passed in for the tests.
 *
 * Downloads come from the statistics log, which is never cleared, unless the Downloads history
 * saw more of them in the period. Plays are ES-DE's "last played" dates, so a game counts in the
 * period it was last played in.
 */
object Recap {
    /** Most years offered next to "Last 12 months". */
    const val MAX_YEARS = 8
    const val MOST_CONSOLES = 5

    /**
     * `console|name`, lower case and without the extension: the same game seen by the Downloads
     * history (a zip) and by ES-DE (the unpacked file).
     */
    fun gameKey(consoleId: String, fileName: String): String =
        consoleId.lowercase() + "|" + LibraryKeys.baseName(FileParsingUtils.decodeUrlEncodedFileName(fileName).lowercase())

    /** First and last day of [period], seen from [now]. */
    fun days(period: RecapPeriod, now: Long, zone: ZoneId): Pair<LocalDate, LocalDate> {
        val today = PlayHistory.dayOf(now, zone)
        return when (period) {
            is RecapPeriod.Year -> LocalDate.of(period.year, 1, 1) to minOf(LocalDate.of(period.year, 12, 31), today)
            RecapPeriod.Last12Months -> today.withDayOfMonth(1).minusMonths(11) to today
        }
    }

    /** The years with something to look back on, newest first, always including the current year. */
    fun years(input: RecapInput, now: Long, zone: ZoneId): List<Int> {
        val current = PlayHistory.dayOf(now, zone).year
        val seen = HashSet<Int>()
        input.downloads.forEach { seen += PlayHistory.dayOf(it.at, zone).year }
        input.log.forEach { seen += PlayHistory.dayOf(it.at, zone).year }
        input.plays?.forEach { seen += PlayHistory.dayOf(it.at, zone).year }
        seen += current
        return seen.filter { it in 1970..current }.sortedDescending().take(MAX_YEARS)
    }

    /** "March", or "March 2026" with [withYear], in the stand-alone form of [locale]. */
    fun monthName(month: YearMonth, withYear: Boolean, locale: Locale): String =
        DateTimeFormatter.ofPattern(if (withYear) "LLLL yyyy" else "LLLL", locale).format(month).replaceFirstChar { it.titlecase(locale) }

    fun fileName(period: RecapPeriod): String = when (period) {
        is RecapPeriod.Year -> "dogmatix-recap-${period.year}.png"
        RecapPeriod.Last12Months -> "dogmatix-recap-last-12-months.png"
    }

    private class Download(val at: Long, val consoleId: String, val bytes: Long)

    fun build(input: RecapInput, period: RecapPeriod, now: Long, zone: ZoneId = ZoneId.systemDefault()): YearRecap {
        val (from, to) = days(period, now, zone)
        fun inPeriod(at: Long) = at > 0L && PlayHistory.dayOf(at, zone).let { !it.isBefore(from) && !it.isAfter(to) }

        val logged = input.log.filter { inPeriod(it.at) }
        val named = input.downloads.filter { inPeriod(it.at) }
        // The log never forgets; the history knows names. Whichever saw more downloads is the count.
        val downloads = if (logged.size >= named.size) logged.map { Download(it.at, it.consoleId, it.bytes) }
        else named.map { Download(it.at, it.consoleId, it.bytes) }

        val plays = input.plays?.filter { inPeriod(it.at) }?.distinctBy { gameKey(it.consoleId, it.fileName) }

        // Everything that happened, by day and by month.
        val moments = downloads.map { it.at } + plays.orEmpty().map { it.at }
        val perDay = moments.groupingBy { PlayHistory.dayOf(it, zone) }.eachCount()
        val perMonth = perDay.entries.groupingBy { YearMonth.from(it.key) }.fold(0) { sum, e -> sum + e.value }
        val months = chartMonths(period, to).map { RecapMonth(it, perMonth[it] ?: 0) }

        val consoles = (downloads.map { it.consoleId } + plays.orEmpty().map { it.consoleId }).distinct().map { id ->
            RecapConsole(
                id,
                downloads.count { it.consoleId == id },
                plays.orEmpty().count { it.consoleId == id },
                downloads.filter { it.consoleId == id }.sumOf { it.bytes.coerceAtLeast(0L) }
            )
        }.sortedWith(compareByDescending<RecapConsole> { it.total }.thenByDescending { it.bytes }.thenBy { it.consoleId })

        return YearRecap(
            period = period, from = from, to = to,
            downloaded = downloads.size,
            played = plays?.size,
            bytes = downloads.sumOf { it.bytes.coerceAtLeast(0L) },
            newToYou = newToYou(input, named, from, zone),
            activeDays = perDay.size,
            topConsoles = consoles.take(MOST_CONSOLES),
            mostPlayed = plays.orEmpty().maxWithOrNull(compareBy<RecapPlay> { it.count }.thenBy { it.at })
                ?.let { RecapGame(it.consoleId, it.fileName, it.title, it.count, it.at) },
            busiestMonth = months.filter { it.count > 0 }.maxByOrNull { it.count },
            busiestDay = perDay.entries.sortedBy { it.key }.maxByOrNull { it.value }?.let { RecapDay(it.key, it.value) },
            longestStreak = longestStreak(perDay.keys),
            months = months
        )
    }

    /** The 12 columns of the chart. */
    private fun chartMonths(period: RecapPeriod, to: LocalDate): List<YearMonth> = when (period) {
        is RecapPeriod.Year -> (1..12).map { YearMonth.of(period.year, it) }
        RecapPeriod.Last12Months -> (11 downTo 0).map { YearMonth.from(to).minusMonths(it.toLong()) }
    }

    /** Games downloaded in the period ([named]) that no earlier download or play in the history knows. */
    private fun newToYou(input: RecapInput, named: List<RecapDownload>, from: LocalDate, zone: ZoneId): Int {
        fun before(at: Long) = at > 0L && PlayHistory.dayOf(at, zone).isBefore(from)
        val known = HashSet<String>()
        input.downloads.filter { before(it.at) }.forEach { known += gameKey(it.consoleId, it.fileName) }
        input.plays?.filter { before(it.at) }?.forEach { known += gameKey(it.consoleId, it.fileName) }
        return named.map { gameKey(it.consoleId, it.fileName) }.distinct().count { it !in known }
    }

    /** The longest run of consecutive days; the earliest one when two are as long. Null without any day. */
    fun longestStreak(days: Set<LocalDate>): RecapStreak? {
        if (days.isEmpty()) return null
        var best: RecapStreak? = null
        var start: LocalDate? = null
        var previous: LocalDate? = null
        fun close() {
            val s = start ?: return
            val p = previous ?: return
            val length = (p.toEpochDay() - s.toEpochDay() + 1).toInt()
            if (best == null || length > best!!.days) best = RecapStreak(length, s, p)
        }
        for (day in days.sorted()) {
            if (previous == null || day.toEpochDay() != previous.toEpochDay() + 1) { close(); start = day }
            previous = day
        }
        close()
        return best
    }
}
