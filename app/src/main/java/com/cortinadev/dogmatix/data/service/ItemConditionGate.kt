package com.cortinadev.dogmatix.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.cortinadev.dogmatix.util.DownloadCondition
import com.cortinadev.dogmatix.util.DownloadConditions
import com.cortinadev.dogmatix.util.WaitInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-download conditions ("Download when..."): Wi-Fi, charging, tonight, at a chosen time.
 *
 * A download with an unmet condition waits in [awaitReady], BEFORE it takes a download slot, so it
 * never blocks the rest of the queue (the global rules of [DownloadGate] wait inside a slot).
 * One watcher coroutine re-checks every condition when the network or the charger changes, when a
 * clock event is due, or when a condition is set or removed; waiters are completed from there, so
 * hundreds of waiting downloads cost one evaluation pass per event, not a wake-up each.
 *
 * Clock events: an in-process delay plus one inexact `setAndAllowWhileIdle` alarm (no exact-alarm
 * permission) that wakes the watcher through a receiver registered at runtime. While downloads wait
 * the foreground service keeps the process alive; if the process was killed, the conditions (kept in
 * a small text file keyed by download name, see [DownloadConditions.encode]) are applied again when
 * the queue is restored.
 */
@Singleton
class ItemConditionGate @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val gate: DownloadGate
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = File(context.filesDir, "download_conditions.txt")
    private val loaded = CompletableDeferred<Unit>()

    private val _conditions = MutableStateFlow<Map<String, DownloadCondition>>(emptyMap())
    /** Every condition currently set, by download file name. */
    val conditions: StateFlow<Map<String, DownloadCondition>> = _conditions.asStateFlow()

    private val _unmet = MutableStateFlow<Map<String, WaitInfo>>(emptyMap())
    /** The conditions that are not met yet and what is missing; drives the pill on a row. */
    val unmet: StateFlow<Map<String, WaitInfo>> = _unmet.asStateFlow()

    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val ticks = MutableStateFlow(0)

    init {
        scope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching { if (file.exists()) DownloadConditions.decode(file.readText()) else emptyMap() }.getOrDefault(emptyMap())
            }
            // Conditions set before the file was read are newer and win.
            _conditions.update { stored + it }
            loaded.complete(Unit)
        }
        scope.launch {
            loaded.await()
            _conditions.collectLatest { map ->
                delay(300)
                withContext(Dispatchers.IO) { writeFile(map) }
            }
        }
        scope.launch {
            combine(
                _conditions,
                gate.deviceState.map { DownloadConditions.State(it.onUnmeteredNetwork, it.charging) }.distinctUntilChanged(),
                ticks
            ) { _, _, _ -> }.collectLatest { evaluateAll() }
        }
        registerAlarmReceiver()
    }

    /** Re-checks every condition; completes the waiters whose condition is met or gone and arms the next clock event. */
    private suspend fun evaluateAll() {
        val conditions = _conditions.value
        val now = System.currentTimeMillis()
        val state = deviceNow()
        val verdicts = conditions.mapValues { (_, c) -> DownloadConditions.evaluate(c, state, now) }
        val unmet = HashMap<String, WaitInfo>()
        for ((name, v) in verdicts) {
            if (v is DownloadConditions.Verdict.Waiting) unmet[name] = WaitInfo(conditions.getValue(name), v.reasons, v.nextCheckAt)
        }
        _unmet.value = unmet
        for ((name, waiter) in waiters) {
            if (name !in unmet) waiter.complete(Unit)
        }
        val next = DownloadConditions.earliestCheck(verdicts.values, now)
        if (next == null) {
            cancelAlarm()
        } else {
            setAlarm(next)
            delay(next - now + 50L)
            ticks.update { it + 1 }
        }
    }

    private fun deviceNow(): DownloadConditions.State =
        gate.deviceState.value.let { DownloadConditions.State(it.onUnmeteredNetwork, it.charging) }

    /**
     * Returns when [fileName] has no condition or its condition is met. Call it before taking a slot.
     * Cancelling the caller cancels the wait.
     */
    suspend fun awaitReady(fileName: String) {
        loaded.await()
        if (_conditions.value[fileName] == null) return
        val waiter = CompletableDeferred<Unit>()
        waiters.put(fileName, waiter)?.complete(Unit)
        ticks.update { it + 1 }
        try {
            waiter.await()
        } finally {
            waiters.remove(fileName, waiter)
        }
    }

    /** True while [fileName] has a condition that is not met right now (checked again after a slot was granted). */
    fun isBlocked(fileName: String): Boolean {
        val c = _conditions.value[fileName] ?: return false
        return DownloadConditions.evaluate(c, deviceNow(), System.currentTimeMillis()) !is DownloadConditions.Verdict.Ready
    }

    /** Sets (or with null removes) the condition of one download. */
    fun set(fileName: String, condition: DownloadCondition?) = setAll(listOf(fileName), condition)

    /** Sets (or with null removes) the same condition for a whole batch, as one update. */
    fun setAll(fileNames: Collection<String>, condition: DownloadCondition?) {
        if (fileNames.isEmpty()) return
        _conditions.update { old ->
            if (condition == null) old - fileNames.toSet() else old + fileNames.associateWith { condition }
        }
    }

    /** The condition is done with (the download starts, stops or is removed). */
    fun clear(fileName: String) {
        if (_conditions.value.containsKey(fileName)) _conditions.update { it - fileName }
    }

    /** Drops conditions of downloads that no longer exist (after the queue was restored). */
    fun prune(keep: (String) -> Boolean) {
        scope.launch {
            loaded.await()
            _conditions.update { map -> map.filterKeys(keep) }
        }
    }

    private fun writeFile(map: Map<String, DownloadCondition>) {
        runCatching {
            if (map.isEmpty()) {
                file.delete()
            } else {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(DownloadConditions.encode(map))
                if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
            }
        }.onFailure { Log.w(TAG, "Could not save download conditions: ${it.message}") }
    }

    // ---- inexact clock alarm ----

    private val alarmIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context, 6001, Intent(ACTION_WAKE).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun registerAlarmReceiver() {
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { ticks.update { it + 1 } }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_WAKE), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.w(TAG, "No alarm receiver: ${it.message}") }
    }

    private fun setAlarm(at: Long) {
        runCatching {
            context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent)
        }
    }

    private fun cancelAlarm() {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent) }
    }

    private companion object {
        const val TAG = "ItemConditionGate"
        const val ACTION_WAKE = "com.cortinadev.dogmatix.action.DOWNLOAD_CONDITION_WAKE"
    }
}
