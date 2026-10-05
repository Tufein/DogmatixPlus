package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class PlayHistoryTest {
    private val zone: ZoneId = ZoneOffset.UTC
    // Wednesday 2026-10-07 12:00 UTC.
    private val now = LocalDate.of(2026, 10, 7).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun at(date: String, hour: Int = 10) = LocalDate.parse(date).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun event(kind: HistoryKind, time: Long, title: String = "Game", console: String = "gba", file: String = "$title.gba") =
        HistoryEvent(kind, time, console, file, title)

    @Test
    fun `merge sorts newest first and drops future and timeless events`() {
        val merged = PlayHistory.merge(
            downloads = listOf(event(HistoryKind.DOWNLOADED, at("2026-10-05"), "A"), event(HistoryKind.DOWNLOADED, 0L, "Z")),
            plays = listOf(event(HistoryKind.PLAYED, at("2026-10-06"), "B"), event(HistoryKind.PLAYED, at("2026-12-01"), "Future")),
            saves = emptyList(), now = now
        )
        assertEquals(listOf("B", "A"), merged.map { it.title })
    }

    @Test
    fun `a save at the time of a play of the same game is dropped`() {
        val t = at("2026-10-06")
        val merged = PlayHistory.merge(
            emptyList(),
            listOf(event(HistoryKind.PLAYED, t, "Same")),
            listOf(event(HistoryKind.SAVED, t, "Same"), event(HistoryKind.SAVED, t + 1000, "Other")),
            now
        )
        assertEquals(listOf("Other", "Same"), merged.map { it.title })
    }

    @Test
    fun `filters`() {
        val all = listOf(event(HistoryKind.DOWNLOADED, 1), event(HistoryKind.PLAYED, 2), event(HistoryKind.SAVED, 3))
        assertEquals(3, PlayHistory.filter(all, HistoryFilter.ALL).size)
        assertEquals(listOf(HistoryKind.DOWNLOADED), PlayHistory.filter(all, HistoryFilter.DOWNLOADS).map { it.kind })
        assertEquals(listOf(HistoryKind.PLAYED, HistoryKind.SAVED), PlayHistory.filter(all, HistoryFilter.PLAYED).map { it.kind })
    }

    @Test
    fun `day labels`() {
        val today = LocalDate.of(2026, 10, 7)
        assertEquals(DayLabel.Today, PlayHistory.labelFor(today, today))
        assertEquals(DayLabel.Yesterday, PlayHistory.labelFor(today.minusDays(1), today))
        assertEquals(DayLabel.Weekday(today.minusDays(3)), PlayHistory.labelFor(today.minusDays(3), today))
        assertEquals(DayLabel.Weekday(today.minusDays(6)), PlayHistory.labelFor(today.minusDays(6), today))
        assertEquals(DayLabel.Date(today.minusDays(7)), PlayHistory.labelFor(today.minusDays(7), today))
    }

    @Test
    fun `groups by day in order`() {
        val events = listOf(
            event(HistoryKind.PLAYED, at("2026-10-07", 11), "A"),
            event(HistoryKind.DOWNLOADED, at("2026-10-07", 9), "B"),
            event(HistoryKind.PLAYED, at("2026-10-06", 23), "C"),
            event(HistoryKind.PLAYED, at("2026-09-01"), "D")
        )
        val groups = PlayHistory.groupByDay(events, now, zone)
        assertEquals(listOf(DayLabel.Today, DayLabel.Yesterday, DayLabel.Date(LocalDate.of(2026, 9, 1))), groups.map { it.label })
        assertEquals(listOf(2, 1, 1), groups.map { it.events.size })
        assertTrue(PlayHistory.groupByDay(emptyList(), now, zone).isEmpty())
    }

    @Test
    fun `daily counts end today`() {
        val events = listOf(
            event(HistoryKind.PLAYED, at("2026-10-07")), event(HistoryKind.PLAYED, at("2026-10-07", 8)),
            event(HistoryKind.PLAYED, at("2026-10-05")), event(HistoryKind.PLAYED, at("2026-09-20"))
        )
        assertEquals(listOf(0, 0, 0, 0, 1, 0, 2), PlayHistory.dailyCounts(events, now, zone))
        assertEquals(LocalDate.of(2026, 10, 7), PlayHistory.dailyDates(now, zone).last())
    }

    @Test
    fun `weekly counts run monday to sunday and end with this week`() {
        val events = listOf(
            event(HistoryKind.PLAYED, at("2026-10-05")), // this Monday
            event(HistoryKind.PLAYED, at("2026-10-04")), // last Sunday
            event(HistoryKind.PLAYED, at("2026-09-28")), // Monday a week earlier
            event(HistoryKind.PLAYED, at("2026-01-01"))  // too old
        )
        val weeks = PlayHistory.weeklyCounts(events, now, zone, weeks = 4)
        assertEquals(listOf(0, 0, 2, 1), weeks)
    }

    @Test
    fun `paging stops at the total`() {
        assertEquals(50, PlayHistory.pageEnd(120, 0))
        assertEquals(100, PlayHistory.pageEnd(120, 50))
        assertEquals(120, PlayHistory.pageEnd(120, 100))
        assertEquals(30, PlayHistory.pageEnd(30, 0))
    }
}
