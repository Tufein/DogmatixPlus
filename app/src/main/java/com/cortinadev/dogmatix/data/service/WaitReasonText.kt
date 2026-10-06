package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.util.WaitReason

/** The long wording of a reason, as on the Downloads screen ("Waiting for Wi-Fi", "Waits for: low battery"). */
fun WaitReason.longText(context: Context): String = context.getString(
    when (this) {
        WaitReason.WIFI -> R.string.wait_wifi
        WaitReason.CHARGER -> R.string.wait_charger
        WaitReason.NIGHT -> R.string.wait_night
        WaitReason.STORAGE -> R.string.wait_storage
        WaitReason.HELD -> R.string.wait_held
        WaitReason.LOW_BATTERY -> R.string.power75_wait_battery
        WaitReason.HOT -> R.string.power75_wait_hot
    }
)

/** The line of the ongoing download notification while the queue waits; null when nothing waits. */
fun waitingNotificationText(context: Context, reasons: List<WaitReason>): String? =
    reasons.takeIf { it.isNotEmpty() }
        ?.joinToString(" · ") { it.longText(context) }
        ?.replaceFirstChar { it.uppercase() }
