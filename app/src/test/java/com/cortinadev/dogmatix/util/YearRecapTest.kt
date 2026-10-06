package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

class YearRecapTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val now = at(2026, 10, 5, 12)
    private val gb = 1_073_741_824L

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0, zone: ZoneId = utc): Long =
        LocalDate.of(y, m, d).atTime(h, min).atZone(zone).toInstant().toEpochMilli()

    private fun dl(at: Long, console: String = "nintendo_snes", name: String = "Game $at", bytes: Long = gb) =
        RecapDownload(at, console, "$name.zip", name, bytes)

    private fun log(at: Long, console: String = "nintendo_snes", bytes: Long = gb) = DownloadLogEntry(at, console, bytes)

    private fun play(at: Long, console: String = "nintendo_snes", name: String, count: Int = 1) =
        RecapPlay(at, console, "$name.zip", name, count)

    private fun input(
        downloads: List<RecapDownload> = emptyList(),
        log: List<DownloadLogEntry> = emptyList(),
        plays: List<RecapPlay>? = emptyList()
    ) = RecapInput(downloads, log, plays)

    private val year = RecapPeriod.Year(2026)

    @Test fun `a year runs from January to today, the last 12 months from the first of the month 11 months back`() {
        assertEquals(LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 10, 5), Recap.days(year, now, utc))
        assertEquals(LocalDate.of(2025, 1, 1) to LocalDate.of(2025, 12, 31), Recap.days(RecapPeriod.Year(2025), now, utc))
        assertEquals(LocalDate.of(2025, 11, 1) to LocalDate.of(2026, 10, 5), Recap.days(RecapPeriod.Last12Months, now, utc))
    }

    @Test fun `nothing at all is an empty recap`() {
        val r = Recap.build(input(), year, now, utc)
        assertTrue(r.isEmpty)
        assertEquals(0, r.downloaded)
        assertEquals(0, r.played)
        assertEquals(0L, r.bytes)
        assertNull(r.mostPlayed); assertNull(r.busiestMonth); assertNull(r.busiestDay); assertNull(r.longestStreak)
        assertTrue(r.topConsoles.isEmpty())
        assertEquals(12, r.months.size)
    }

    @Test fun `downloads count from the log inside the period only`() {
        val r = Recap.build(
            input(log = listOf(
                log(at(2025, 12, 31, 23)), log(at(2026, 1, 1, 0), bytes = 2 * gb), log(at(2026, 6, 15), bytes = 3 * gb),
                log(at(2026, 10, 5, 8)), log(at(2026, 10, 6)) // tomorrow: clock skew, left out
            )),
            year, now, utc
        )
        assertEquals(3, r.downloaded)
        assertEquals(6 * gb, r.bytes)   // 2 + 3 + 1 GiB
        assertFalse(r.isEmpty)
    }

    @Test fun `the history wins when it saw more downloads than the log`() {
        val a = at(2026, 3, 1); val b = at(2026, 3, 2); val c = at(2026, 3, 3)
        val r = Recap.build(
            input(downloads = listOf(dl(a, bytes = 5), dl(b, bytes = 6), dl(c, bytes = 7)), log = listOf(log(a, bytes = 100))),
            year, now, utc
        )
        assertEquals(3, r.downloaded)
        assertEquals(18L, r.bytes)
    }

    @Test fun `the log wins when the downloads list was cleared`() {
        val r = Recap.build(
            input(downloads = listOf(dl(at(2026, 3, 1), bytes = 5)), log = listOf(log(at(2026, 3, 1), bytes = 9), log(at(2026, 3, 2), bytes = 9))),
            year, now, utc
        )
        assertEquals(2, r.downloaded)
        assertEquals(18L, r.bytes)
    }

    @Test fun `played counts each game once and is unknown without ES-DE`() {
        val plays = listOf(
            play(at(2026, 2, 1), name = "A"), play(at(2026, 2, 3), name = "B"),
            play(at(2025, 12, 30), name = "Old"), play(at(2026, 2, 5), name = "A") // same game twice: once
        )
        assertEquals(2, Recap.build(input(plays = plays), year, now, utc).played)
        assertNull(Recap.build(input(plays = null), year, now, utc).played)
        assertTrue(Recap.build(input(plays = null), year, now, utc).isEmpty)
    }

    @Test fun `the most played game is the one with the highest play count, the newer one on a tie`() {
        val r = Recap.build(
            input(plays = listOf(
                play(at(2026, 1, 10), name = "Few", count = 3),
                play(at(2026, 2, 10), name = "Many", count = 40),
                play(at(2026, 3, 10), name = "Also many", count = 40),
                play(at(2025, 3, 10), name = "Last year", count = 999)
            )),
            year, now, utc
        )
        assertEquals("Also many", r.mostPlayed?.title)
        assertEquals(40, r.mostPlayed?.plays)
        assertEquals(at(2026, 3, 10), r.mostPlayed?.lastAt)
    }

    @Test fun `top consoles add downloads and plays, biggest first, at most five`() {
        val downloads = listOf(
            dl(at(2026, 1, 1), "snes"), dl(at(2026, 1, 2), "snes"), dl(at(2026, 1, 3), "gba", bytes = 7 * gb),
            dl(at(2026, 1, 4), "psx"), dl(at(2026, 1, 5), "n64"), dl(at(2026, 1, 6), "nes"), dl(at(2026, 1, 7), "gb")
        )
        val plays = listOf(play(at(2026, 2, 1), "snes", "A"), play(at(2026, 2, 2), "gba", "B"), play(at(2026, 2, 3), "gba", "C"))
        val r = Recap.build(input(downloads = downloads, plays = plays), year, now, utc)
        assertEquals(Recap.MOST_CONSOLES, r.topConsoles.size)
        assertEquals(listOf("gba", "snes"), r.topConsoles.take(2).map { it.consoleId })
        assertEquals(RecapConsole("gba", downloads = 1, played = 2, bytes = 7 * gb), r.topConsoles[0])
        assertEquals(3, r.topConsoles[0].total)
        assertEquals(3, r.topConsoles[1].total)   // snes: 2 downloads + 1 play; gba wins on size
    }

    @Test fun `the busiest month and day are found, the earlier one on a tie`() {
        val r = Recap.build(
            input(log = listOf(
                log(at(2026, 3, 9)), log(at(2026, 3, 9, 15)), log(at(2026, 3, 20)),
                log(at(2026, 5, 1)), log(at(2026, 5, 2)), log(at(2026, 5, 3))
            )),
            year, now, utc
        )
        assertEquals(RecapMonth(YearMonth.of(2026, 3), 3), r.busiestMonth)   // May ties it; March is earlier
        assertEquals(RecapDay(LocalDate.of(2026, 3, 9), 2), r.busiestDay)
        assertEquals(listOf(0, 0, 3, 0, 3, 0, 0, 0, 0, 0, 0, 0), r.months.map { it.count })
        assertEquals(YearMonth.of(2026, 1), r.months.first().month)
        assertEquals(YearMonth.of(2026, 12), r.months.last().month)
    }

    @Test fun `downloads and plays both make a day active, and the longest streak is found across a month end`() {
        val r = Recap.build(
            input(
                log = listOf(log(at(2026, 1, 30)), log(at(2026, 2, 1)), log(at(2026, 2, 3)), log(at(2026, 2, 4)), log(at(2026, 5, 5))),
                plays = listOf(play(at(2026, 1, 31), name = "A"))
            ),
            year, now, utc
        )
        // 30 Jan, 31 Jan (play), 1 Feb is a run of three; 3 and 4 Feb a run of two.
        assertEquals(RecapStreak(3, LocalDate.of(2026, 1, 30), LocalDate.of(2026, 2, 1)), r.longestStreak)
        assertEquals(6, r.activeDays)
    }

    @Test fun `the streak helper handles one day, gaps and ties`() {
        assertNull(Recap.longestStreak(emptySet()))
        val one = LocalDate.of(2026, 4, 2)
        assertEquals(RecapStreak(1, one, one), Recap.longestStreak(setOf(one)))
        val days = setOf(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 11))
        assertEquals(RecapStreak(2, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 2)), Recap.longestStreak(days))
    }

    @Test fun `a game is new to you when nothing earlier in the history knows it`() {
        val r = Recap.build(
            input(
                downloads = listOf(
                    dl(at(2026, 2, 1), name = "Fresh"), dl(at(2026, 2, 2), name = "Seen as download"), dl(at(2026, 2, 3), name = "Seen as play"),
                    dl(at(2025, 6, 1), name = "Seen as download")
                ),
                plays = listOf(play(at(2025, 7, 1), name = "Seen as play"))
            ),
            year, now, utc
        )
        // "Seen as download" shares its key with the 2025 row (a history keeps one row per file name), so it is not new.
        assertEquals(1, r.newToYou)
    }

    @Test fun `the last 12 months cover whole months back to the same month last year plus one`() {
        val r = Recap.build(
            input(log = listOf(log(at(2025, 10, 31)), log(at(2025, 11, 1)), log(at(2026, 10, 5)))),
            RecapPeriod.Last12Months, now, utc
        )
        assertEquals(2, r.downloaded)
        assertEquals(12, r.months.size)
        assertEquals(YearMonth.of(2025, 11), r.months.first().month)
        assertEquals(YearMonth.of(2026, 10), r.months.last().month)
        assertEquals(1, r.months.first().count)
        assertEquals(1, r.months.last().count)
    }

    @Test fun `the day of an event follows the time zone`() {
        val plus2 = ZoneOffset.ofHours(2)
        val late = at(2026, 3, 31, 23, 30, utc)   // 01:30 on 1 April at +02:00
        val r = Recap.build(input(log = listOf(log(late))), year, now, plus2)
        assertEquals(YearMonth.of(2026, 4), r.busiestMonth?.month)
        assertEquals(LocalDate.of(2026, 4, 1), r.busiestDay?.day)
    }

    @Test fun `the years offered are the ones with something in them, newest first, with the current one always there`() {
        val i = input(
            downloads = listOf(dl(at(2023, 5, 5))), log = listOf(log(at(2024, 1, 1))),
            plays = listOf(play(at(2023, 1, 1), name = "A"), play(at(2031, 1, 1), name = "Future"))
        )
        assertEquals(listOf(2026, 2024, 2023), Recap.years(i, now, utc))
        assertEquals(listOf(2026), Recap.years(input(plays = null), now, utc))
    }

    @Test fun `no more than the most recent years are offered`() {
        val log = (2010..2025).map { log(at(it, 6, 1)) }
        val years = Recap.years(input(log = log), now, utc)
        assertEquals(Recap.MAX_YEARS, years.size)
        assertEquals(2026, years.first())
        assertEquals(2026 - Recap.MAX_YEARS + 1, years.last())
    }

    @Test fun `file names say what the card covers`() {
        assertEquals("dogmatix-recap-2026.png", Recap.fileName(RecapPeriod.Year(2026)))
        assertEquals("dogmatix-recap-last-12-months.png", Recap.fileName(RecapPeriod.Last12Months))
    }

    @Test fun `month names are written out and capitalised`() {
        assertEquals("March", Recap.monthName(YearMonth.of(2026, 3), withYear = false, locale = java.util.Locale.ENGLISH))
        assertEquals("March 2026", Recap.monthName(YearMonth.of(2026, 3), withYear = true, locale = java.util.Locale.ENGLISH))
    }

    @Test fun `a game key ignores case, the extension and URL encoding, but not the console`() {
        assertEquals(Recap.gameKey("SNES", "Zelda.ZIP"), Recap.gameKey("snes", "zelda.sfc"))
        assertEquals(Recap.gameKey("snes", "Super%20Game%20(USA).zip"), Recap.gameKey("snes", "Super Game (USA).smc"))
        assertFalse(Recap.gameKey("snes", "Zelda.zip") == Recap.gameKey("gb", "Zelda.zip"))
    }
}
