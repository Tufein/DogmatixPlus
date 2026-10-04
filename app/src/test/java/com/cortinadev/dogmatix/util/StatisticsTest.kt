package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class StatisticsTest {
    @Test fun `statistics group by month and console`() {
        val zone = ZoneOffset.UTC
        fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()
        val log = listOf(DownloadLogEntry(at(2026, 9, 5), "snes", 100), DownloadLogEntry(at(2026, 10, 1), "snes", 50), DownloadLogEntry(at(2026, 10, 2), "psx", 700))
        assertEquals(log, DownloadStats.parse(log.joinToString("\n") { DownloadStats.line(it) } + "\nbroken line"))
        val s = DownloadStats.summarize(log, at(2026, 10, 3), months = 3, zone = zone)
        assertEquals(listOf("2026-08", "2026-09", "2026-10"), s.perMonth.map { it.first })
        assertEquals(listOf(0, 1, 2), s.perMonth.map { it.second })
        assertEquals("psx", s.perConsole.first().first)
        assertEquals(850L, s.totalBytes)
        val day = 24L * 3_600_000
        assertEquals(listOf(1, 2), DownloadStats.newPerWeek(listOf(at(2026, 9, 25), at(2026, 10, 2), at(2026, 10, 3)), at(2026, 10, 3) + 1, weeks = 2))
        assertTrue(day > 0)
    }
}
