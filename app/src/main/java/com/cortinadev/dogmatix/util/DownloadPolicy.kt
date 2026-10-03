package com.cortinadev.dogmatix.util

/** Conditions the user can put on starting downloads (Settings → Downloads). */
data class DownloadConditions(
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val nightOnly: Boolean = false,
    /** Night window as minutes after midnight; it may run past midnight (23:00 → 07:00). */
    val nightStart: Int = 23 * 60,
    val nightEnd: Int = 7 * 60
) {
    val any: Boolean get() = wifiOnly || chargingOnly || nightOnly
}

data class DeviceConditions(val onUnmeteredNetwork: Boolean, val charging: Boolean, val minuteOfDay: Int)

enum class WaitReason { WIFI, CHARGER, NIGHT }

object DownloadPolicy {

    /** What a download still has to wait for; empty means it may start. */
    fun waitingFor(conditions: DownloadConditions, device: DeviceConditions): List<WaitReason> = buildList {
        if (conditions.wifiOnly && !device.onUnmeteredNetwork) add(WaitReason.WIFI)
        if (conditions.chargingOnly && !device.charging) add(WaitReason.CHARGER)
        if (conditions.nightOnly && !inWindow(device.minuteOfDay, conditions.nightStart, conditions.nightEnd)) add(WaitReason.NIGHT)
    }

    /** [minute] lies in [start, end); a window whose end is not after its start wraps past midnight. */
    fun inWindow(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> true
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    /** "23:00" for display. */
    fun formatMinutes(minutes: Int): String = "%02d:%02d".format((minutes / 60).coerceIn(0, 23), (minutes % 60).coerceIn(0, 59))

    /** Moves a window edge by [delta] steps of 30 minutes, wrapping around midnight. */
    fun shift(minutes: Int, delta: Int): Int = ((minutes + delta * 30) % 1440 + 1440) % 1440
}
