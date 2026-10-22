package com.tufein.valkyr.util

import java.util.Locale

/**
 * 4.7.0: the pure part of the "Performance check" tool: what is measured, how a list of timings
 * becomes one number, how a number becomes "fast / fine / slow" and what the shared report says.
 * No Android classes, so it is covered by JVM tests; the measuring itself (database, covers,
 * storage, frames) is in `PerfCheckService` and `FrameSampler`.
 *
 * The limits behind [verdictOf] are rules of thumb for a handheld (what feels instant, what a
 * list needs to scroll smoothly, what a ROM copy needs), not values read from a standard; they
 * only turn a number into a word. The numbers themselves are always shown next to the word.
 */
object PerfCheck {

    /** Everything the check can measure; [key] is the fixed label used in the shared report. */
    enum class Metric(val key: String, val unit: Quantity) {
        STARTUP("startup_first_frame", Quantity.MS),
        DB_SEARCH("db_search", Quantity.MS),
        DB_FIRST_PAGE("db_first_page", Quantity.MS),
        DB_COUNT("db_count", Quantity.MS),
        DB_SHELVES("db_shelves", Quantity.MS),
        COVER_DECODE("cover_decode", Quantity.MS),
        STORAGE_WRITE("storage_write", Quantity.MB_PER_S),
        STORAGE_READ("storage_read", Quantity.MB_PER_S),
        FREE_SPACE("free_space", Quantity.BYTES),
        FRAMES("frame_time_p90", Quantity.MS),
        JANK("frames_over_budget", Quantity.PERCENT)
    }

    enum class Quantity { MS, MB_PER_S, BYTES, PERCENT }

    /** How a number reads: [INFO] is a fact without a good or bad (RAM class). */
    enum class Verdict { GOOD, OK, SLOW, INFO }

    /** Why a part was not measured (shown instead of a number, never as a failure of the app). */
    enum class Skip { NO_LIBRARY, NO_COVERS, NO_FOLDER, FOLDER_UNUSABLE, NOT_ENOUGH_SPACE, NO_FRAMES, FAILED, CANCELLED }

    /** One part of the result: a number, or the reason there is none. */
    sealed class Outcome {
        abstract val metric: Metric

        /** [value] is in the metric's unit; [extra] is a second number shown in words (e.g. the slowest run). */
        data class Measured(override val metric: Metric, val value: Double, val extra: Double? = null, val count: Int = 0) : Outcome()
        data class Skipped(override val metric: Metric, val reason: Skip) : Outcome()
    }

    // ---- Statistics ------------------------------------------------------------------------

    /** Nearest-rank percentile of [values] (0..100); 0.0 for an empty list. Does not need sorted input. */
    fun percentile(values: List<Double>, percent: Int): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val rank = Math.ceil(percent.coerceIn(0, 100) / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** The middle run of several; the first run is kept apart by the caller (it is the cold one). */
    fun median(values: List<Double>): Double = percentile(values, 50)

    /** What a list of frame durations says. [budgetMs] is one refresh interval (16.7 ms at 60 Hz). */
    data class FrameSummary(
        val frames: Int,
        val p50: Double,
        val p90: Double,
        val p95: Double,
        val p99: Double,
        val worst: Double,
        val overBudget: Int,
        val budgetMs: Double
    ) {
        val overBudgetPercent: Double get() = if (frames == 0) 0.0 else overBudget * 100.0 / frames
    }

    /** Summary of frame durations in ms; a frame counts as over budget when it took longer than [budgetMs]. */
    fun summarizeFrames(durationsMs: List<Double>, budgetMs: Double): FrameSummary = FrameSummary(
        frames = durationsMs.size,
        p50 = percentile(durationsMs, 50),
        p90 = percentile(durationsMs, 90),
        p95 = percentile(durationsMs, 95),
        p99 = percentile(durationsMs, 99),
        worst = durationsMs.maxOrNull() ?: 0.0,
        overBudget = durationsMs.count { it > budgetMs },
        budgetMs = budgetMs
    )

    /** The two result rows of a frame sample; skipped rows when no frame was reported. */
    fun frameOutcomes(summary: FrameSummary?): List<Outcome> =
        if (summary == null || summary.frames == 0) listOf(Outcome.Skipped(Metric.FRAMES, Skip.NO_FRAMES), Outcome.Skipped(Metric.JANK, Skip.NO_FRAMES))
        else listOf(
            Outcome.Measured(Metric.FRAMES, summary.p90, extra = summary.worst, count = summary.frames),
            Outcome.Measured(Metric.JANK, summary.overBudgetPercent, count = summary.frames)
        )

    /** One refresh interval in ms for a display refresh rate; falls back to 60 Hz for a missing or silly value. */
    fun frameBudgetMs(refreshRate: Float): Double = 1000.0 / (if (refreshRate in 20f..240f) refreshRate.toDouble() else 60.0)

    /** Frames to keep at most while sampling (a few minutes at 120 Hz would still fit; the sample is seconds). */
    const val MAX_FRAMES = 5_000

    // ---- Storage speed ---------------------------------------------------------------------

    /** The size of the temporary file of the storage test. */
    const val TEST_FILE_BYTES = 16L * 1024 * 1024

    /** The test needs this much room on top of the file itself (so a nearly full card is left alone). */
    const val TEST_FILE_HEADROOM_BYTES = 64L * 1024 * 1024

    /** Hidden file name of the test file: a dot prefix and a random part, so it can never be a game or an existing file. */
    fun testFileName(random: Long): String = ".valkyr-speedtest-" + java.lang.Long.toHexString(random and Long.MAX_VALUE) + ".tmp"

    /** True for the name [testFileName] makes (the leftover of an interrupted run can be told from user files). */
    fun isTestFileName(name: String): Boolean = Regex("^\\.valkyr-speedtest-[0-9a-f]{1,16}\\.tmp$").matches(name)

    /** Whether the storage test may run: [freeBytes] null means the free space could not be read (then it runs, the write itself decides). */
    fun storageTestAllowed(freeBytes: Long?): Boolean = freeBytes == null || freeBytes >= TEST_FILE_BYTES + TEST_FILE_HEADROOM_BYTES

    /** Megabytes (1 MiB = 1,048,576 bytes) per second; 0 when no time passed. */
    fun throughputMiBPerSecond(bytes: Long, nanos: Long): Double = if (nanos <= 0L) 0.0 else bytes / 1_048_576.0 / (nanos / 1_000_000_000.0)

    // ---- Covers ----------------------------------------------------------------------------

    /** Longest side, in pixels, a cover is decoded at for the timing (about what a list tile needs). */
    const val COVER_TARGET_PX = 400

    /** Most cached covers decoded for the timing. */
    const val COVER_SAMPLE = 24

    /** The power-of-two `inSampleSize` that keeps the longest side at or above [target]. */
    fun sampleSize(width: Int, height: Int, target: Int = COVER_TARGET_PX): Int {
        var size = 1
        val longest = maxOf(width, height)
        if (longest <= 0 || target <= 0) return 1
        while (longest / (size * 2) >= target) size *= 2
        return size
    }

    // ---- Words -----------------------------------------------------------------------------

    /** The word for a number: lower is better for times and over-budget shares, higher for speeds and room. */
    fun verdictOf(metric: Metric, value: Double): Verdict = when (metric) {
        Metric.STARTUP -> lowerIsBetter(value, 1_500.0, 3_000.0)
        Metric.DB_SEARCH -> lowerIsBetter(value, 150.0, 500.0)
        Metric.DB_FIRST_PAGE -> lowerIsBetter(value, 100.0, 300.0)
        Metric.DB_COUNT -> lowerIsBetter(value, 20.0, 100.0)
        Metric.DB_SHELVES -> lowerIsBetter(value, 300.0, 1_000.0)
        Metric.COVER_DECODE -> lowerIsBetter(value, 15.0, 40.0)
        Metric.STORAGE_WRITE -> higherIsBetter(value, 40.0, 15.0)
        Metric.STORAGE_READ -> higherIsBetter(value, 80.0, 30.0)
        Metric.FREE_SPACE -> higherIsBetter(value, 5.0 * GIB, 1.0 * GIB)
        Metric.FRAMES -> Verdict.INFO
        Metric.JANK -> lowerIsBetter(value, 5.0, 15.0)
    }

    /** The metrics whose [Outcome.Measured.extra] is the first (cold) run; for the others it is the slowest one. */
    val COLD_FIRST = setOf(Metric.DB_SEARCH, Metric.DB_FIRST_PAGE, Metric.DB_COUNT, Metric.DB_SHELVES)

    private const val GIB = 1024.0 * 1024.0 * 1024.0

    private fun lowerIsBetter(value: Double, good: Double, ok: Double) = when {
        value <= good -> Verdict.GOOD
        value <= ok -> Verdict.OK
        else -> Verdict.SLOW
    }

    private fun higherIsBetter(value: Double, good: Double, ok: Double) = when {
        value >= good -> Verdict.GOOD
        value >= ok -> Verdict.OK
        else -> Verdict.SLOW
    }

    /** The overall verdict is the worst of the judged parts; [Verdict.INFO] when there are none. */
    fun overall(outcomes: List<Outcome>): Verdict {
        val judged = outcomes.filterIsInstance<Outcome.Measured>().map { verdictOf(it.metric, it.value) }.filter { it != Verdict.INFO }
        return when {
            judged.isEmpty() -> Verdict.INFO
            Verdict.SLOW in judged -> Verdict.SLOW
            Verdict.OK in judged -> Verdict.OK
            else -> Verdict.GOOD
        }
    }

    /** "42 ms", "1.2 s": times, always with the unit. */
    fun formatMs(ms: Double): String = when {
        ms < 0 -> "-"
        ms < 10 -> String.format(Locale.US, "%.1f ms", ms)
        ms < 1_000 -> String.format(Locale.US, "%d ms", Math.round(ms))
        else -> String.format(Locale.US, "%.1f s", ms / 1_000)
    }

    fun formatSpeed(mibPerSecond: Double): String = String.format(Locale.US, if (mibPerSecond < 10) "%.1f MB/s" else "%.0f MB/s", mibPerSecond)

    fun formatPercent(percent: Double): String = String.format(Locale.US, "%.1f%%", percent)

    /** Bytes in the units of the rest of the app (KB/MB/GB, 1024 based). */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "unknown"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unit = 0
        while (size >= 1024 && unit < units.size - 1) { size /= 1024; unit++ }
        return if (unit == 0) "${size.toInt()} B" else String.format(Locale.US, "%.1f %s", size, units[unit])
    }

    /** A measured value in its unit, as a short text. */
    fun format(metric: Metric, value: Double): String = when (metric.unit) {
        Quantity.MS -> formatMs(value)
        Quantity.MB_PER_S -> formatSpeed(value)
        Quantity.BYTES -> formatSize(value.toLong())
        Quantity.PERCENT -> formatPercent(value)
    }

    // ---- The device ------------------------------------------------------------------------

    /** The memory facts of the device, from `ActivityManager` (the caller reads them). */
    data class DeviceFacts(
        val apiLevel: Int,
        val model: String,
        val totalRamBytes: Long,
        val appHeapClassMb: Int,
        val largeHeapClassMb: Int,
        val lowRamDevice: Boolean,
        val refreshRate: Float,
        val cores: Int
    )

    /** The RAM sizes devices are sold with, in GB. */
    private val RAM_SIZES = listOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64)

    /**
     * A rough size class for the RAM, so a report can be read without knowing the model: "4 GB",
     * "8 GB"... The system reports a little less than the marketed size (the kernel keeps some),
     * so the value is rounded up to the next size a device is sold with.
     */
    fun ramClass(totalRamBytes: Long): String {
        if (totalRamBytes <= 0) return "unknown"
        val gib = totalRamBytes / (1024.0 * 1024.0 * 1024.0)
        val size = RAM_SIZES.firstOrNull { it >= gib } ?: Math.ceil(gib).toInt()
        return "$size GB"
    }

    // ---- The shared report -----------------------------------------------------------------

    /** What the report says about a part that was not measured. */
    fun skipText(reason: Skip): String = when (reason) {
        Skip.NO_LIBRARY -> "skipped: the library is empty"
        Skip.NO_COVERS -> "skipped: no cover in the cache yet"
        Skip.NO_FOLDER -> "skipped: no download folder set"
        Skip.FOLDER_UNUSABLE -> "skipped: the download folder cannot be written"
        Skip.NOT_ENOUGH_SPACE -> "skipped: not enough free space for the test file"
        Skip.NO_FRAMES -> "skipped: no frames were reported"
        Skip.FAILED -> "failed"
        Skip.CANCELLED -> "cancelled"
    }

    private fun label(metric: Metric): String = when (metric) {
        Metric.STARTUP -> "start to first screen"
        Metric.DB_SEARCH -> "search for a common word"
        Metric.DB_FIRST_PAGE -> "first page of the list"
        Metric.DB_COUNT -> "count all games"
        Metric.DB_SHELVES -> "home shelves query"
        Metric.COVER_DECODE -> "decode a cached cover"
        Metric.STORAGE_WRITE -> "write 16 MB to the download folder"
        Metric.STORAGE_READ -> "read it back"
        Metric.FREE_SPACE -> "free space in the download folder"
        Metric.FRAMES -> "frame time while scrolling (90th percentile)"
        Metric.JANK -> "frames over the refresh interval"
    }

    private fun verdictWord(v: Verdict) = when (v) {
        Verdict.GOOD -> "fast"
        Verdict.OK -> "fine"
        Verdict.SLOW -> "slow"
        Verdict.INFO -> ""
    }

    /**
     * The text the "Share report" button hands to the share sheet: English, numbers and fixed
     * labels only (no path, no title, no key, no server). [startup] and [slowCalls] come from
     * PerfLog's own report block, passed as text so this stays free of Android.
     */
    fun report(
        appVersion: String,
        device: DeviceFacts,
        outcomes: List<Outcome>,
        frames: FrameSummary?,
        perfLogBlock: String,
        libraryGames: Int?
    ): String = buildString {
        appendLine("Valkyr performance check")
        appendLine("App: $appVersion")
        appendLine("Android: API ${device.apiLevel}, ${device.model}, ${device.cores} cores, RAM class ${ramClass(device.totalRamBytes)} (${formatSize(device.totalRamBytes)}), app heap ${device.appHeapClassMb} MB (large ${device.largeHeapClassMb} MB), low-RAM device: ${device.lowRamDevice}, display ${String.format(Locale.US, "%.0f", device.refreshRate)} Hz")
        if (libraryGames != null) appendLine("Library: $libraryGames indexed games")
        appendLine("Overall: ${overall(outcomes).name.lowercase()}")
        appendLine()
        outcomes.forEach { o ->
            when (o) {
                is Outcome.Measured -> {
                    val word = verdictWord(verdictOf(o.metric, o.value)).let { if (it.isEmpty()) "" else " ($it)" }
                    val extra = o.extra?.let { "; ${if (o.metric in COLD_FIRST) "first run" else "slowest"} ${format(o.metric, it)}" }.orEmpty()
                    val runs = if (o.count > 0) "; ${o.count} ${if (o.metric == Metric.COVER_DECODE) "covers" else "runs"}" else ""
                    appendLine("  ${label(o.metric)}: ${format(o.metric, o.value)}$word$extra$runs")
                }
                is Outcome.Skipped -> appendLine("  ${label(o.metric)}: ${skipText(o.reason)}")
            }
        }
        if (frames != null) {
            appendLine("  frames: ${frames.frames} sampled, p50 ${formatMs(frames.p50)}, p90 ${formatMs(frames.p90)}, p95 ${formatMs(frames.p95)}, p99 ${formatMs(frames.p99)}, worst ${formatMs(frames.worst)}, ${frames.overBudget} over the ${formatMs(frames.budgetMs)} budget")
            appendLine("  (the frames come from a synthetic list of simple rows, not from your library)")
        }
        appendLine("  (read speed right after writing can be flattered by the system's cache)")
        appendLine()
        append(perfLogBlock.trimEnd())
        appendLine()
    }
}
