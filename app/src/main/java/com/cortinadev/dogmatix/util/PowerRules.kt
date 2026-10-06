package com.cortinadev.dogmatix.util

/** 7.5 power rules of Settings → Download schedule; both are off out of the box. */
data class PowerSettings(
    val lowBatteryOn: Boolean = false,
    /** Downloads pause below this level (percent) while the device is not charging. */
    val batteryPercent: Int = PowerRules.DEFAULT_PERCENT,
    val heatOn: Boolean = false
) {
    val any: Boolean get() = lowBatteryOn || heatOn
}

/** One look at the battery (the sticky ACTION_BATTERY_CHANGED) and the system's thermal status. */
data class PowerReading(
    /** 0..100, or null when the battery does not say. */
    val levelPercent: Int?,
    val charging: Boolean,
    /** Battery temperature in tenths of a degree Celsius (EXTRA_TEMPERATURE), or null when unknown. */
    val tenthsCelsius: Int?,
    /** The system reports THERMAL_STATUS_SEVERE or worse, held until it is cool again (see [ThermalLatch]). */
    val thermalSevere: Boolean = false
)

/** What the power rules hold downloads for right now. */
data class PowerHold(val lowBattery: Boolean = false, val hot: Boolean = false) {
    val any: Boolean get() = lowBattery || hot
    val reasons: List<WaitReason> get() = buildList {
        if (lowBattery) add(WaitReason.LOW_BATTERY)
        if (hot) add(WaitReason.HOT)
    }
}

/**
 * The battery and heat rules, with hysteresis so a download does not flap on and off around the
 * edge: below N % it pauses and it only goes on again above N + 5 % (or on the charger); from 45 °C
 * it pauses and it goes on again below 40 °C.
 */
object PowerRules {
    const val DEFAULT_PERCENT = 20
    const val MIN_PERCENT = 5
    const val MAX_PERCENT = 50
    const val STEP_PERCENT = 5
    /** Downloads go on again once the level is this much above the limit. */
    const val RESUME_MARGIN = 5
    /** Tenths of °C: pause at 45.0 °C, go on below 40.0 °C. */
    const val HOT_AT = 450
    const val COOL_BELOW = 400

    /** The next hold, from the previous one, the settings and a fresh reading. */
    fun next(previous: PowerHold, settings: PowerSettings, reading: PowerReading): PowerHold =
        PowerHold(lowBattery = lowBattery(previous.lowBattery, settings, reading), hot = hot(previous.hot, settings, reading))

    fun lowBattery(wasLow: Boolean, settings: PowerSettings, reading: PowerReading): Boolean {
        if (!settings.lowBatteryOn || reading.charging) return false
        val level = reading.levelPercent ?: return false
        val limit = clampPercent(settings.batteryPercent)
        return if (wasLow) level <= limit + RESUME_MARGIN else level < limit
    }

    fun hot(wasHot: Boolean, settings: PowerSettings, reading: PowerReading): Boolean {
        if (!settings.heatOn) return false
        if (reading.thermalSevere) return true
        val temp = reading.tenthsCelsius ?: return false
        return if (wasHot) temp >= COOL_BELOW else temp >= HOT_AT
    }

    /** Level from EXTRA_LEVEL / EXTRA_SCALE; null when either is missing. */
    fun levelPercent(level: Int, scale: Int): Int? =
        if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)

    fun clampPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** Moves the limit by [delta] steps of 5 %, within 5..50. */
    fun shift(percent: Int, delta: Int): Int = clampPercent(clampPercent(percent) + delta * STEP_PERCENT)
}

/**
 * Hysteresis for the system's thermal status (PowerManager.THERMAL_STATUS_*): from SEVERE on the
 * device counts as hot, and it stays hot until the status has been below MODERATE for [coolMs]
 * without a break, so a status that bounces around SEVERE does not start and park downloads over
 * and over. The battery temperature keeps its own rule (see [PowerRules.hot]). Pure JVM for the tests.
 */
class ThermalLatch(private val coolMs: Long = COOL_MS) {
    private var latched = false
    private var coolSince: Long? = null

    /** Feeds the current [status] at [nowMs]; returns whether the thermal status counts as hot. */
    fun update(status: Int, nowMs: Long): Boolean {
        if (status >= SEVERE) {
            latched = true
            coolSince = null
            return true
        }
        if (!latched) return false
        if (status >= MODERATE) {
            coolSince = null
            return true
        }
        val since = coolSince ?: nowMs.also { coolSince = it }
        if (nowMs - since >= coolMs) {
            latched = false
            coolSince = null
        }
        return latched
    }

    /** While hot but cooling: the moment [update] should be called again to let go; null otherwise. */
    fun releaseAt(): Long? = if (latched) coolSince?.plus(coolMs) else null

    fun reset() {
        latched = false
        coolSince = null
    }

    companion object {
        /** PowerManager.THERMAL_STATUS_MODERATE and THERMAL_STATUS_SEVERE. */
        const val MODERATE = 2
        const val SEVERE = 3
        const val COOL_MS = 60_000L
    }
}
