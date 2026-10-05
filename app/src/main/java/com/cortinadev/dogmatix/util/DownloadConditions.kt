package com.cortinadev.dogmatix.util

import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** What a single download waits for before it takes a slot (6.0 "Download when..."). */
enum class ConditionKind {
    /** On an unmetered network. */
    WIFI,
    /** While the device is charging. */
    CHARGING,
    /** Both of the above at once. */
    WIFI_AND_CHARGING,
    /** Inside the fixed night window [DownloadConditions.TONIGHT_START] to [DownloadConditions.TONIGHT_END]. */
    TONIGHT,
    /** From a moment the user picked ([DownloadCondition.atMillis], the next occurrence of a time of day). */
    AT_TIME
}

/**
 * One download's own condition. [minuteOfDay] is only kept for [ConditionKind.AT_TIME] (what the user
 * picked, for the dialog); [atMillis] is the absolute moment resolved when the condition was set, so
 * a restart or a time-zone change never moves it.
 */
data class DownloadCondition(
    val kind: ConditionKind,
    val minuteOfDay: Int = 0,
    val atMillis: Long = 0L
)

/** Why a condition is not met yet; the pill of the row shows it. */
enum class ItemWait { WIFI, CHARGER, NIGHT, TIME }

/** A condition that is not met yet, with what is still missing (published for the row's pill). */
data class WaitInfo(val condition: DownloadCondition, val reasons: List<ItemWait>, val nextCheckAt: Long?)

/**
 * Decisions for per-download conditions. Pure JVM: the clock and the zone are parameters, all
 * calendar maths goes through java.time so a daylight-saving change cannot shift a time by an hour.
 */
object DownloadConditions {
    const val TONIGHT_START = 23 * 60
    const val TONIGHT_END = 7 * 60

    /** What the device offers right now. */
    data class State(val onUnmeteredNetwork: Boolean, val charging: Boolean)

    sealed interface Verdict {
        /** The download may take a slot. */
        data object Ready : Verdict
        /**
         * Still waiting for [reasons]. [nextCheckAt] (epoch ms) is the next moment the clock alone can
         * change the answer (start of the night, the picked time); null when only the device state can.
         */
        data class Waiting(val reasons: List<ItemWait>, val nextCheckAt: Long?) : Verdict
    }

    /** [condition] null means no condition: always [Verdict.Ready]. */
    fun evaluate(condition: DownloadCondition?, state: State, now: Long, zone: ZoneId = ZoneId.systemDefault()): Verdict {
        if (condition == null) return Verdict.Ready
        return when (condition.kind) {
            ConditionKind.WIFI -> if (state.onUnmeteredNetwork) Verdict.Ready else Verdict.Waiting(listOf(ItemWait.WIFI), null)
            ConditionKind.CHARGING -> if (state.charging) Verdict.Ready else Verdict.Waiting(listOf(ItemWait.CHARGER), null)
            ConditionKind.WIFI_AND_CHARGING -> {
                val missing = buildList {
                    if (!state.onUnmeteredNetwork) add(ItemWait.WIFI)
                    if (!state.charging) add(ItemWait.CHARGER)
                }
                if (missing.isEmpty()) Verdict.Ready else Verdict.Waiting(missing, null)
            }
            ConditionKind.TONIGHT ->
                if (DownloadPolicy.inWindow(minuteOfDay(now, zone), TONIGHT_START, TONIGHT_END)) Verdict.Ready
                else Verdict.Waiting(listOf(ItemWait.NIGHT), nextOccurrence(TONIGHT_START, now, zone))
            ConditionKind.AT_TIME ->
                if (now >= condition.atMillis) Verdict.Ready else Verdict.Waiting(listOf(ItemWait.TIME), condition.atMillis)
        }
    }

    /** Minutes after local midnight at [now] in [zone]. */
    fun minuteOfDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).let { it.hour * 60 + it.minute }

    /**
     * The first moment strictly after [now] at which the local clock reads [minuteOfDay]: today when
     * that time is still ahead, otherwise tomorrow. A time inside a spring-forward gap lands on the
     * first instant after the gap (java.time's rule), never on the wrong day.
     */
    fun nextOccurrence(minuteOfDay: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val minute = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate()
        val first = today.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
        if (first > now) return first
        return today.plusDays(1).atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
    }

    /** "At a time I pick" resolved against [now]. */
    fun atTime(minuteOfDay: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): DownloadCondition =
        DownloadCondition(ConditionKind.AT_TIME, minuteOfDay.coerceIn(0, 24 * 60 - 1), nextOccurrence(minuteOfDay, now, zone))

    /** Steps a time of day by [delta] quarter hours, wrapping around midnight (the picker's buttons). */
    fun shiftQuarter(minuteOfDay: Int, delta: Int): Int = ((minuteOfDay + delta * 15) % 1440 + 1440) % 1440

    /** The earliest [Verdict.Waiting.nextCheckAt] of [verdicts] after [now], or null when no clock event is pending. */
    fun earliestCheck(verdicts: Collection<Verdict>, now: Long): Long? =
        verdicts.mapNotNull { (it as? Verdict.Waiting)?.nextCheckAt }.filter { it > now }.minOrNull()

    // ---- persistence (one line per download; pure text so it is testable on the JVM) ----

    /** Encodes the conditions of the queue as lines `kind|minute|atMillis|urlencoded name`. */
    fun encode(map: Map<String, DownloadCondition>): String = map.entries.joinToString("\n") { (name, c) ->
        "${c.kind.name}|${c.minuteOfDay}|${c.atMillis}|${URLEncoder.encode(name, "UTF-8")}"
    }

    /** Reads [encode]'s output; lines that do not parse are skipped, so a damaged file never breaks the queue. */
    fun decode(text: String): Map<String, DownloadCondition> {
        val out = LinkedHashMap<String, DownloadCondition>()
        for (line in text.lineSequence()) {
            val parts = line.split('|', limit = 4)
            if (parts.size != 4) continue
            val kind = ConditionKind.entries.firstOrNull { it.name == parts[0] } ?: continue
            val minute = parts[1].toIntOrNull() ?: continue
            val at = parts[2].toLongOrNull() ?: continue
            val name = runCatching { URLDecoder.decode(parts[3], "UTF-8") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            out[name] = DownloadCondition(kind, minute, at)
        }
        return out
    }
}
