package com.cortinadev.dogmatix.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Why the last download from a source failed, roughly; enough to tell "busy" from "gone". */
enum class FailureClass {
    /** The connection dropped, timed out or was refused. */
    NETWORK,
    /** A 5xx, a timeout or rate limit answered by the server. */
    SERVER,
    /** 404 / 410: the file is not (or no longer) there. */
    NOT_FOUND,
    /** 401 / 403: the server refused it. */
    FORBIDDEN,
    OTHER;

    companion object {
        /** [httpCode] when the server answered with an error, [network] for an I/O error without one. */
        fun of(httpCode: Int?, network: Boolean): FailureClass = when {
            httpCode == 404 || httpCode == 410 -> NOT_FOUND
            httpCode == 401 || httpCode == 403 -> FORBIDDEN
            httpCode != null && AutoRetry.temporaryStatus(httpCode) -> SERVER
            httpCode != null -> OTHER
            network -> NETWORK
            else -> OTHER
        }
    }
}

/**
 * How downloads from one source (the address as configured in Sources) went lately. Kept small:
 * a speed average and two counts that are halved now and then, so old history fades.
 */
data class SourceRecord(
    /** Exponentially weighted average speed of finished transfers, bytes/s; 0 = not known yet. */
    val bytesPerSec: Double = 0.0,
    val successes: Int = 0,
    val failures: Int = 0,
    val lastSuccessAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    /** Failures in a row since the last success. */
    val failureStreak: Int = 0,
    val lastFailure: FailureClass? = null
) {
    val total: Int get() = successes + failures
    /** Share of downloads that worked (0..100), or null before the first one. */
    val okPercent: Int? get() = if (total == 0) null else (successes * 100.0 / total).roundToInt()
}

/**
 * Picks the best source for a game several sources list (7.5 "Pick the best source"). Pure JVM
 * for the tests: reliability first, then speed, then the order of the sources in Sources; a source
 * that failed several times in a row lately goes last for a while. Only ever between copies of the
 * same game: same console and file name, or the same cleaned title with the same tags.
 */
object SourceRanking {

    /** Weight of a new speed sample in the average. */
    const val SPEED_WEIGHT = 0.3
    /** Transfers shorter than this tell little about a server's speed (handshakes, tiny files). */
    const val MIN_SAMPLE_BYTES = 1L * 1024 * 1024
    const val MIN_SAMPLE_MS = 1_000L
    /** Failures in a row that put a source last... */
    const val DEMOTE_STREAK = 2
    /** ...for this long after its last failure. */
    const val DEMOTE_MS = 6 * 60 * 60 * 1000L
    /** Above this many downloads both counts are halved, so the record follows recent behaviour. */
    const val MAX_COUNT = 100
    /** Sizes of two copies may differ this much (listings round them) and still be the same file. */
    const val SIZE_TOLERANCE = 0.05

    fun withSuccess(r: SourceRecord, bytes: Long, millis: Long, now: Long): SourceRecord {
        val speed = if (bytes >= MIN_SAMPLE_BYTES && millis >= MIN_SAMPLE_MS) bytes * 1000.0 / millis else null
        val avg = when {
            speed == null -> r.bytesPerSec
            r.bytesPerSec <= 0.0 -> speed
            else -> r.bytesPerSec + SPEED_WEIGHT * (speed - r.bytesPerSec)
        }
        return aged(r.copy(bytesPerSec = avg, successes = r.successes + 1, lastSuccessAt = now, failureStreak = 0))
    }

    fun withFailure(r: SourceRecord, cls: FailureClass, now: Long): SourceRecord =
        aged(r.copy(failures = r.failures + 1, lastFailureAt = now, failureStreak = r.failureStreak + 1, lastFailure = cls))

    private fun aged(r: SourceRecord): SourceRecord =
        if (r.total <= MAX_COUNT) r else r.copy(successes = (r.successes + 1) / 2, failures = (r.failures + 1) / 2)

    /** Failed [DEMOTE_STREAK] times or more in a row, the last time less than [DEMOTE_MS] ago. */
    fun isDemoted(r: SourceRecord?, now: Long): Boolean =
        r != null && r.failureStreak >= DEMOTE_STREAK && now - r.lastFailureAt < DEMOTE_MS

    /**
     * Success rate with one imagined success and failure added, so an unknown source counts as
     * 0.5, one good download as 0.67 and one bad one as 0.33.
     */
    fun reliability(r: SourceRecord?): Double = if (r == null) 0.5 else (r.successes + 1.0) / (r.total + 2.0)

    /** Reliability in steps of 10 %: within a step the faster source wins. */
    private fun reliabilityStep(r: SourceRecord?): Int = floor(reliability(r) * 10).toInt()

    /** One indexed copy of a game, as the ranking sees it. */
    data class Copy(
        val consoleId: String,
        val fileName: String,
        /** Cleaned title (tags stripped), any case. */
        val title: String,
        val tags: Collection<String>,
        val size: Long
    )

    private val whitespace = Regex("\\s+")
    fun titleKey(title: String): String = title.lowercase().replace(whitespace, " ").trim()

    /**
     * Whether [a] and [b] are the same file of the same game: same console, close sizes (when both
     * are known) and the same file name, or the same cleaned title with the same region/version
     * tags and the same file type. Never a different game, version or region.
     */
    fun sameGame(a: Copy, b: Copy): Boolean {
        if (a.consoleId != b.consoleId) return false
        if (a.size > 0 && b.size > 0 && abs(a.size - b.size) > maxOf(a.size, b.size) * SIZE_TOLERANCE) return false
        if (a.fileName.equals(b.fileName, ignoreCase = true)) return true
        val ext = { n: String -> n.substringAfterLast('.', "").lowercase() }
        return ext(a.fileName) == ext(b.fileName) && titleKey(a.title) == titleKey(b.title) && titleKey(a.title).isNotEmpty() &&
            a.tags.map { it.lowercase() }.toSet() == b.tags.map { it.lowercase() }.toSet()
    }

    /**
     * [rows] best first. [sourceOf] gives a row's source (as configured), [sourceOrder] the sources
     * in the order of Sources (unknown sources after them); rows that tie keep their order.
     */
    fun <T> rank(
        rows: List<T>,
        sourceOf: (T) -> String,
        records: Map<String, SourceRecord>,
        sourceOrder: List<String>,
        now: Long
    ): List<T> {
        fun orderOf(s: String) = sourceOrder.indexOf(s).let { if (it < 0) Int.MAX_VALUE else it }
        return rows.sortedWith(
            compareBy<T> { isDemoted(records[sourceOf(it)], now) }
                .thenByDescending { reliabilityStep(records[sourceOf(it)]) }
                .thenByDescending { records[sourceOf(it)]?.bytesPerSec ?: 0.0 }
                .thenBy { orderOf(sourceOf(it)) }
        )
    }

    fun <T> best(rows: List<T>, sourceOf: (T) -> String, records: Map<String, SourceRecord>, sourceOrder: List<String>, now: Long): T? =
        rank(rows, sourceOf, records, sourceOrder, now).firstOrNull()

    /**
     * A short name for a source in a status line: the host of a web address, "RomM" for a RomM
     * platform, the name of a torrent, or the last part of a path.
     */
    fun label(sourceUrl: String): String {
        val u = sourceUrl.trim()
        return when (SourceKind.of(u)) {
            SourceKind.ROMM -> "RomM"
            SourceKind.WEB -> runCatching { URI(u).host }.getOrNull()?.removePrefix("www.") ?: u
            SourceKind.TORRENT -> if (u.startsWith("magnet:", ignoreCase = true)) {
                Regex("[?&]dn=([^&]+)").find(u)?.groupValues?.get(1)
                    ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) } ?: "magnet"
            } else u.substringBefore('?').trimEnd('/').substringAfterLast('/')
            SourceKind.OTHER -> u
        }.ifEmpty { u }
    }

    /** "12 MB/s" style speed, null when unknown. */
    fun speedText(bytesPerSec: Double): String? = when {
        bytesPerSec <= 0.0 -> null
        bytesPerSec >= 1024.0 * 1024 -> String.format(java.util.Locale.ROOT, if (bytesPerSec >= 10.0 * 1024 * 1024) "%.0f MB/s" else "%.1f MB/s", bytesPerSec / (1024 * 1024))
        else -> String.format(java.util.Locale.ROOT, "%.0f KB/s", maxOf(1.0, bytesPerSec / 1024))
    }

    // Stored as one small JSON object per source in files/source_track.json.

    fun toJson(records: Map<String, SourceRecord>): String {
        val o = JsonObject()
        records.forEach { (url, r) ->
            o.add(url, JsonObject().apply {
                addProperty("speed", r.bytesPerSec)
                addProperty("ok", r.successes)
                addProperty("failed", r.failures)
                addProperty("okAt", r.lastSuccessAt)
                addProperty("failedAt", r.lastFailureAt)
                addProperty("streak", r.failureStreak)
                r.lastFailure?.let { addProperty("why", it.name) }
            })
        }
        return o.toString()
    }

    fun fromJson(json: String): Map<String, SourceRecord> = runCatching {
        JsonParser.parseString(json).asJsonObject.entrySet().associate { (url, v) ->
            val r = v.asJsonObject
            fun long(k: String) = r.get(k)?.takeUnless { it.isJsonNull }?.asLong ?: 0L
            fun int(k: String) = r.get(k)?.takeUnless { it.isJsonNull }?.asInt ?: 0
            url to SourceRecord(
                bytesPerSec = r.get("speed")?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0,
                successes = int("ok"), failures = int("failed"),
                lastSuccessAt = long("okAt"), lastFailureAt = long("failedAt"),
                failureStreak = int("streak"),
                lastFailure = r.get("why")?.takeUnless { it.isJsonNull }?.asString?.let { n -> FailureClass.entries.firstOrNull { it.name == n } }
            )
        }
    }.getOrDefault(emptyMap())
}
