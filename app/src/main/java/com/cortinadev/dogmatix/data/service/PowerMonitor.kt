package com.cortinadev.dogmatix.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.cortinadev.dogmatix.data.local.PowerRuleSettings
import com.cortinadev.dogmatix.util.PowerHold
import com.cortinadev.dogmatix.util.PowerReading
import com.cortinadev.dogmatix.util.PowerRules
import com.cortinadev.dogmatix.util.PowerSettings
import com.cortinadev.dogmatix.util.ThermalLatch
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the battery level, the charger and the device temperature for the 7.5 power rules
 * (*Pause below N % battery*, *Pause when the device is hot*) and says what they hold downloads
 * for ([hold]). It only listens while a rule is on and downloads are queued or running; otherwise
 * nothing is registered and [hold] is empty. [DownloadGate] adds the reasons to its own.
 */
@Singleton
class PowerMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val ruleSettings: PowerRuleSettings,
    private val tracker: DownloadProgressTracker
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _hold = MutableStateFlow(PowerHold())
    /** What the power rules hold downloads for right now; empty while no rule is on or nothing waits. */
    val hold: StateFlow<PowerHold> = _hold.asStateFlow()

    private val reading = MutableStateFlow<PowerReading?>(null)

    init {
        val busy = tracker.downloads.map { list -> list.any { !it.isFinished } }.distinctUntilChanged()
        scope.launch {
            combine(busy, ruleSettings.settings.map { it.any }) { b, on -> b && on }
                .distinctUntilChanged()
                .collectLatest { needed ->
                    if (!needed) {
                        _hold.value = PowerHold()
                        return@collectLatest
                    }
                    watch()
                }
        }
    }

    /** Listens until cancelled (the queue emptied or both rules were switched off). */
    private suspend fun watch() {
        val power = context.getSystemService(PowerManager::class.java)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { onBattery(intent, power) }
        }
        val thermal = PowerManager.OnThermalStatusChangedListener { status -> onThermal(status) }
        val sticky = runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.w(TAG, "No battery updates: ${it.message}") }.getOrNull()
        sticky?.let { onBattery(it, power) }
        val direct = Executor { it.run() }
        runCatching { power?.addThermalStatusListener(direct, thermal) }
        try {
            combine(reading.filterNotNull(), ruleSettings.settings) { r, s -> r to s }.collect { (r, s) -> apply(s, r) }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { power?.removeThermalStatusListener(thermal) }
            releaseJob?.cancel()
            synchronized(latch) { latch.reset(); thermalStatus = 0 }
            reading.value = null
        }
    }

    private fun apply(settings: PowerSettings, r: PowerReading) {
        val before = _hold.value
        val after = PowerRules.next(before, settings, r)
        if (after != before) {
            Log.i(TAG, "Power hold: battery=${r.levelPercent}% charging=${r.charging} temp=${r.tenthsCelsius} severe=${r.thermalSevere} -> ${after.reasons}")
            _hold.value = after
        }
    }

    private fun onBattery(intent: Intent, power: PowerManager?) {
        val level = PowerRules.levelPercent(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val status = runCatching { power?.currentThermalStatus ?: 0 }.getOrDefault(0)
        val severe = synchronized(latch) { thermalStatus = status; latch.update(status, SystemClock.elapsedRealtime()) }
        reading.value = PowerReading(level, plugged, temp, severe)
        scheduleRelease()
    }

    /** Thermal status as last reported, fed through [latch] (hot from SEVERE, cool after a minute below MODERATE). */
    private var thermalStatus = 0
    private val latch = ThermalLatch()
    @Volatile private var releaseJob: Job? = null

    private fun onThermal(status: Int) {
        val hot = synchronized(latch) { thermalStatus = status; latch.update(status, SystemClock.elapsedRealtime()) }
        reading.value = reading.value?.copy(thermalSevere = hot)
        scheduleRelease()
    }

    /** No new status arrives when the device simply stays cool: look again once the minute is over. */
    private fun scheduleRelease() {
        releaseJob?.cancel()
        val at = synchronized(latch) { latch.releaseAt() } ?: return
        releaseJob = scope.launch {
            delay((at - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 50L)
            val hot = synchronized(latch) { latch.update(thermalStatus, SystemClock.elapsedRealtime()) }
            reading.value = reading.value?.copy(thermalSevere = hot)
        }
    }

    private companion object { const val TAG = "PowerMonitor" }
}
