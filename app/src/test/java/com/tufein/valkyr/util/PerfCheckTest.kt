package com.tufein.valkyr.util

import com.tufein.valkyr.util.PerfCheck.Metric
import com.tufein.valkyr.util.PerfCheck.Outcome
import com.tufein.valkyr.util.PerfCheck.Skip
import com.tufein.valkyr.util.PerfCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfCheckTest {
    private val device = PerfCheck.DeviceFacts(34, "Odin 2", 11_800_000_000L, 256, 512, false, 120f, 8)

    @Test fun `percentile uses the nearest rank and ignores the input order`() {
        val v = listOf(50.0, 10.0, 40.0, 20.0, 30.0)
        assertEquals(30.0, PerfCheck.percentile(v, 50), 0.0)
        assertEquals(50.0, PerfCheck.percentile(v, 90), 0.0)
        assertEquals(10.0, PerfCheck.percentile(v, 0), 0.0)
        assertEquals(50.0, PerfCheck.percentile(v, 100), 0.0)
        assertEquals(0.0, PerfCheck.percentile(emptyList(), 90), 0.0)
        assertEquals(7.0, PerfCheck.percentile(listOf(7.0), 99), 0.0)
    }

    @Test fun `median of an even list takes the lower middle`() {
        assertEquals(2.0, PerfCheck.median(listOf(1.0, 2.0, 3.0, 4.0)), 0.0)
    }

    @Test fun `frame summary counts frames over the budget`() {
        val budget = PerfCheck.frameBudgetMs(60f)
        val frames = List(90) { 8.0 } + List(8) { 20.0 } + listOf(40.0, 120.0)
        val s = PerfCheck.summarizeFrames(frames, budget)
        assertEquals(100, s.frames)
        assertEquals(10, s.overBudget)
        assertEquals(10.0, s.overBudgetPercent, 0.0001)
        assertEquals(8.0, s.p50, 0.0)
        assertEquals(8.0, s.p90, 0.0)
        assertEquals(20.0, s.p95, 0.0)
        assertEquals(40.0, s.p99, 0.0)
        assertEquals(120.0, s.worst, 0.0)
    }

    @Test fun `an empty sample does not divide by zero`() {
        val s = PerfCheck.summarizeFrames(emptyList(), 16.7)
        assertEquals(0, s.frames)
        assertEquals(0.0, s.overBudgetPercent, 0.0)
        assertEquals(0.0, s.worst, 0.0)
    }

    @Test fun `frame budget follows the refresh rate and survives a silly value`() {
        assertEquals(16.67, PerfCheck.frameBudgetMs(60f), 0.01)
        assertEquals(8.33, PerfCheck.frameBudgetMs(120f), 0.01)
        assertEquals(16.67, PerfCheck.frameBudgetMs(0f), 0.01)
        assertEquals(16.67, PerfCheck.frameBudgetMs(1000f), 0.01)
    }

    @Test fun `frame outcomes are skipped without frames and judged by the share over budget`() {
        assertTrue(PerfCheck.frameOutcomes(null).all { it is Outcome.Skipped })
        assertTrue(PerfCheck.frameOutcomes(PerfCheck.summarizeFrames(emptyList(), 16.7)).all { it is Outcome.Skipped })
        val rows = PerfCheck.frameOutcomes(PerfCheck.summarizeFrames(List(95) { 8.0 } + List(5) { 30.0 }, 16.7))
        assertEquals(listOf(Metric.FRAMES, Metric.JANK), rows.map { it.metric })
        assertEquals(5.0, (rows[1] as Outcome.Measured).value, 0.0001)
        assertEquals(Verdict.GOOD, PerfCheck.verdictOf(Metric.JANK, 5.0))
    }

    @Test fun `throughput is megabytes per second`() {
        assertEquals(16.0, PerfCheck.throughputMiBPerSecond(PerfCheck.TEST_FILE_BYTES, 1_000_000_000L), 0.0001)
        assertEquals(32.0, PerfCheck.throughputMiBPerSecond(PerfCheck.TEST_FILE_BYTES, 500_000_000L), 0.0001)
        assertEquals(0.0, PerfCheck.throughputMiBPerSecond(PerfCheck.TEST_FILE_BYTES, 0L), 0.0)
    }

    @Test fun `the test file name is hidden, unique per number and recognised again`() {
        val a = PerfCheck.testFileName(0xABCDEFL)
        assertEquals(".valkyr-speedtest-abcdef.tmp", a)
        assertTrue(PerfCheck.isTestFileName(a))
        assertTrue(PerfCheck.isTestFileName(PerfCheck.testFileName(-5L)))
        assertFalse(PerfCheck.isTestFileName("Super Mario World.sfc"))
        assertFalse(PerfCheck.isTestFileName(".valkyr-speedtest-xyz.tmp"))
        assertFalse(PerfCheck.isTestFileName("valkyr-speedtest-ab.tmp"))
        assertTrue(PerfCheck.testFileName(1L) != PerfCheck.testFileName(2L))
    }

    @Test fun `storage test needs room for the file and some headroom`() {
        assertTrue(PerfCheck.storageTestAllowed(null))
        assertFalse(PerfCheck.storageTestAllowed(PerfCheck.TEST_FILE_BYTES))
        assertFalse(PerfCheck.storageTestAllowed(PerfCheck.TEST_FILE_BYTES + PerfCheck.TEST_FILE_HEADROOM_BYTES - 1))
        assertTrue(PerfCheck.storageTestAllowed(PerfCheck.TEST_FILE_BYTES + PerfCheck.TEST_FILE_HEADROOM_BYTES))
    }

    @Test fun `cover sample size is a power of two that keeps the target`() {
        assertEquals(1, PerfCheck.sampleSize(300, 400))
        assertEquals(1, PerfCheck.sampleSize(799, 500))
        assertEquals(2, PerfCheck.sampleSize(800, 600))
        assertEquals(4, PerfCheck.sampleSize(1600, 1200))
        assertEquals(8, PerfCheck.sampleSize(3200, 2400))
        assertEquals(1, PerfCheck.sampleSize(0, 0))
    }

    @Test fun `verdicts follow the thresholds in both directions`() {
        assertEquals(Verdict.GOOD, PerfCheck.verdictOf(Metric.DB_SEARCH, 150.0))
        assertEquals(Verdict.OK, PerfCheck.verdictOf(Metric.DB_SEARCH, 150.1))
        assertEquals(Verdict.OK, PerfCheck.verdictOf(Metric.DB_SEARCH, 500.0))
        assertEquals(Verdict.SLOW, PerfCheck.verdictOf(Metric.DB_SEARCH, 501.0))
        assertEquals(Verdict.GOOD, PerfCheck.verdictOf(Metric.STORAGE_WRITE, 40.0))
        assertEquals(Verdict.OK, PerfCheck.verdictOf(Metric.STORAGE_WRITE, 20.0))
        assertEquals(Verdict.SLOW, PerfCheck.verdictOf(Metric.STORAGE_WRITE, 5.0))
        assertEquals(Verdict.SLOW, PerfCheck.verdictOf(Metric.STORAGE_READ, 29.9))
        assertEquals(Verdict.GOOD, PerfCheck.verdictOf(Metric.FREE_SPACE, 6.0 * 1024 * 1024 * 1024))
        assertEquals(Verdict.SLOW, PerfCheck.verdictOf(Metric.FREE_SPACE, 100.0 * 1024 * 1024))
        assertEquals(Verdict.GOOD, PerfCheck.verdictOf(Metric.JANK, 2.0))
        assertEquals(Verdict.SLOW, PerfCheck.verdictOf(Metric.JANK, 30.0))
        assertEquals(Verdict.INFO, PerfCheck.verdictOf(Metric.FRAMES, 99.0))
    }

    @Test fun `overall is the worst judged part and ignores skipped and info parts`() {
        assertEquals(Verdict.INFO, PerfCheck.overall(emptyList()))
        assertEquals(Verdict.INFO, PerfCheck.overall(listOf(Outcome.Skipped(Metric.DB_COUNT, Skip.NO_LIBRARY), Outcome.Measured(Metric.FRAMES, 40.0))))
        assertEquals(Verdict.GOOD, PerfCheck.overall(listOf(Outcome.Measured(Metric.DB_COUNT, 3.0), Outcome.Skipped(Metric.COVER_DECODE, Skip.NO_COVERS))))
        assertEquals(Verdict.OK, PerfCheck.overall(listOf(Outcome.Measured(Metric.DB_COUNT, 3.0), Outcome.Measured(Metric.DB_SEARCH, 300.0))))
        assertEquals(Verdict.SLOW, PerfCheck.overall(listOf(Outcome.Measured(Metric.DB_COUNT, 300.0), Outcome.Measured(Metric.DB_SEARCH, 3.0), Outcome.Measured(Metric.DB_SHELVES, 400.0))))
    }

    @Test fun `numbers read in plain units`() {
        assertEquals("4.2 ms", PerfCheck.formatMs(4.24))
        assertEquals("42 ms", PerfCheck.formatMs(42.4))
        assertEquals("1.5 s", PerfCheck.formatMs(1500.0))
        assertEquals("5.5 MB/s", PerfCheck.formatSpeed(5.46))
        assertEquals("120 MB/s", PerfCheck.formatSpeed(120.2))
        assertEquals("2.5 GB", PerfCheck.formatSize(2_684_354_560L))
        assertEquals("unknown", PerfCheck.formatSize(-1))
        assertEquals("12.5%", PerfCheck.formatPercent(12.5))
    }

    @Test fun `ram class rounds the kernel's share back up`() {
        assertEquals("unknown", PerfCheck.ramClass(0))
        assertEquals("12 GB", PerfCheck.ramClass(11_500_000_000L))
        assertEquals("8 GB", PerfCheck.ramClass(7_600_000_000L))
        assertEquals("4 GB", PerfCheck.ramClass(3_300_000_000L))
        assertEquals("16 GB", PerfCheck.ramClass(15_000_000_000L))
    }

    @Test fun `report holds numbers and fixed labels only`() {
        val outcomes = listOf(
            Outcome.Measured(Metric.DB_SEARCH, 80.0, extra = 210.0, count = 3),
            Outcome.Measured(Metric.STORAGE_WRITE, 12.0),
            Outcome.Skipped(Metric.COVER_DECODE, Skip.NO_COVERS),
            Outcome.Measured(Metric.FRAMES, 9.5)
        )
        val frames = PerfCheck.summarizeFrames(listOf(8.0, 9.0, 30.0), 8.33)
        val text = PerfCheck.report("4.7.0 (60) release", device, outcomes, frames, "Performance\n  startup: no marks yet\n", libraryGames = 1234)
        assertTrue(text.startsWith("Valkyr performance check"))
        assertTrue(text.contains("RAM class 12 GB"))
        assertTrue(text.contains("Library: 1234 indexed games"))
        assertTrue(text.contains("search for a common word: 80 ms (fast); first run 210 ms; 3 runs"))
        assertTrue(text.contains("write 16 MB to the download folder: 12 MB/s (slow)"))
        assertTrue(text.contains("decode a cached cover: skipped: no cover in the cache yet"))
        assertTrue(text.contains("2 over the 8.3 ms budget"))
        assertTrue(text.contains("Performance\n  startup: no marks yet"))
        assertTrue(text.contains("Overall: slow"))
        assertFalse(text.contains("content://"))
        assertFalse(text.contains("/storage/"))
    }

    @Test fun `report without frames or library still reads`() {
        val text = PerfCheck.report("4.7.0", device, emptyList(), null, "Performance\n", libraryGames = null)
        assertFalse(text.contains("Library:"))
        assertFalse(text.contains("frames:"))
        assertTrue(text.contains("Overall: info"))
    }
}
