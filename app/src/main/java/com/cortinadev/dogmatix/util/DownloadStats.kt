package com.cortinadev.dogmatix.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One finished download as the statistics log keeps it. */
data class DownloadLogEntry(val at: Long, val consoleId: String, val bytes: Long)

/** What the statistics screen shows. */
data class DownloadStatsSummary(
    val total: Int,
    val totalBytes: Long,
    /** `yyyy-MM` → (downloads, bytes), last [months] months, oldest first (months without downloads included). */
    val perMonth: List<Triple<String, Int, Long>>,
    /** consoleId → (downloads, bytes), most bytes first. */
    val perConsole: List<Triple<String, Int, Long>>
)

/**
 * The statistics log: one line `epochMillis;consoleId;bytes` per finished download, appended
 * when it finishes (so it outlives the downloads list, which the user can clear).
 */
object DownloadStats {

    fun line(entry: DownloadLogEntry): String = "${entry.at};${entry.consoleId.replace(";", "_")};${entry.bytes}"

    fun parse(text: String): List<DownloadLogEntry> = text.lineSequence().mapNotNull { l ->
        val p = l.trim().split(';')
        if (p.size != 3) null else {
            val at = p[0].toLongOrNull(); val bytes = p[2].toLongOrNull()
            if (at == null || bytes == null) null else DownloadLogEntry(at, p[1], bytes)
        }
    }.toList()

    private val monthFormat = DateTimeFormatter.ofPattern("yyyy-MM")

    fun summarize(entries: List<DownloadLogEntry>, now: Long, months: Int = 12, zone: ZoneId = ZoneId.systemDefault()): DownloadStatsSummary {
        val current = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1)
        val keys = (months - 1 downTo 0).map { current.minusMonths(it.toLong()).format(monthFormat) }
        val byMonth = entries.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate().format(monthFormat) }
        return DownloadStatsSummary(
            total = entries.size,
            totalBytes = entries.sumOf { it.bytes },
            perMonth = keys.map { k -> byMonth[k].orEmpty().let { Triple(k, it.size, it.sumOf { e -> e.bytes }) } },
            perConsole = entries.groupBy { it.consoleId }.map { (c, l) -> Triple(c, l.size, l.sumOf { it.bytes }) }.sortedByDescending { it.third }
        )
    }

    /** Games a rescan found per week, the last [weeks] weeks, oldest first. */
    fun newPerWeek(firstSeen: List<Long>, now: Long, weeks: Int = 8): List<Int> {
        val week = 7L * 24 * 3_600_000
        return (weeks - 1 downTo 0).map { w ->
            val end = now - w * week
            firstSeen.count { it in (end - week + 1)..end }
        }
    }
}
