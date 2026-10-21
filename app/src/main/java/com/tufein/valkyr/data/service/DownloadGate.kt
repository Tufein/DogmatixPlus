package com.tufein.valkyr.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.DeviceConditions
import com.tufein.valkyr.util.DownloadRules
import com.tufein.valkyr.util.DownloadPolicy
import com.tufein.valkyr.util.StorageHelper
import com.tufein.valkyr.util.WaitReason
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds downloads back until the conditions the user set are met: only on Wi-Fi (an unmetered
 * network), only while charging, only in the night window. Downloads that are already running
 * are not interrupted; those that have not started wait here. "Start now" lets everything that is
 * waiting at that moment go, once. The 7.5 power rules (low battery, too hot) wait here too; the
 * downloads already running when they kick in are parked and queued again by [PowerHoldService].
 */
@Singleton
class DownloadGate @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    appSettings: AppSettings,
    powerMonitor: PowerMonitor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val conditions: StateFlow<DownloadRules> = combine(
        combine(settingsRepository.downloadWifiOnly, settingsRepository.downloadChargingOnly, settingsRepository.downloadNightOnly) { w, c, n -> Triple(w, c, n) },
        settingsRepository.downloadNightStart, settingsRepository.downloadNightEnd, appSettings.minFreeGb
    ) { (wifi, charging, night), start, end, minGb -> DownloadRules(wifi, charging, night, start, end, minGb * 1_073_741_824L) }
        .stateIn(scope, SharingStarted.Eagerly, DownloadRules())

    private val device = MutableStateFlow(DeviceConditions(onUnmeteredNetwork = true, charging = true, minuteOfDay = minuteOfDay()))
    /** What the device offers now (read-only), for per-download conditions ([ItemConditionGate]). */
    val deviceState: StateFlow<DeviceConditions> = device.asStateFlow()
    private val releases = MutableStateFlow(0)

    /** The user's hold on the queue (*Downloads → Hold the queue*); kept across restarts. */
    val held: StateFlow<Boolean> = appSettings.queueHeld.stateIn(scope, SharingStarted.Eagerly, false)
    private val settings = appSettings

    /** What a download that starts now would have to wait for; empty = go. */
    val waiting: StateFlow<List<WaitReason>> = combine(conditions, device, held, powerMonitor.hold) { c, d, h, p ->
        // 7.5: low battery / too hot come from [PowerMonitor] (with hysteresis), next to the schedule.
        DownloadPolicy.waitingFor(c, d) + p.reasons + if (h) listOf(WaitReason.HELD) else emptyList()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Holds the queue (running downloads finish, nothing new starts) or lets it go again. */
    fun setHeld(on: Boolean) { scope.launch { settings.setQueueHeld(on) } }

    init {
        watchNetwork()
        watchPower()
        scope.launch {
            // The clock and the free space matter only to the night window and the minimum free space;
            // with neither set the idle app does not wake up every 30 s to read them.
            conditions.map { it.needsPolling }.distinctUntilChanged().collectLatest { needed ->
                if (!needed) return@collectLatest
                while (true) {
                    device.update { it.copy(minuteOfDay = minuteOfDay(), freeBytes = freeBytesNow()) }
                    delay(30_000)
                }
            }
        }
    }

    /** True while free space is below the limit of Settings (a running download stops then). */
    suspend fun lowOnSpace(): Boolean {
        val free = freeBytesNow()
        device.update { it.copy(freeBytes = free) }
        return DownloadPolicy.lowOnSpace(conditions.value.minFreeBytes, free)
    }

    private suspend fun freeBytesNow(): Long? = runCatching {
        val dir = settingsRepository.downloadDirectory.first()
        if (dir.isBlank()) null else StorageHelper.getFreeBytes(context, dir)
    }.getOrNull()

    /** Suspends until the conditions allow a download to start (or [startNow] was pressed meanwhile). */
    suspend fun awaitGo() {
        val seen = releases.value
        combine(waiting, releases) { w, r -> w.isEmpty() || r != seen }.first { it }
    }

    /** Everything waiting right now starts anyway. */
    fun startNow() { releases.update { it + 1 } }

    private fun watchNetwork() {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    device.update { it.copy(onUnmeteredNetwork = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) }
                }
                override fun onLost(network: Network) {
                    device.update { it.copy(onUnmeteredNetwork = false) }
                }
            })
        }
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        device.update { it.copy(onUnmeteredNetwork = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) }
    }

    private fun watchPower() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { update(intent) }
        }
        // Only the plug and unplug events: ACTION_BATTERY_CHANGED fires at every battery percent and
        // would wake the process for nothing. The one-off sticky read gives the state at start-up.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.let { device.update { d -> d.copy(charging = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) } }
    }

    private fun update(intent: Intent) {
        // The broadcast carries no plug state of its own: the action says it.
        device.update { it.copy(charging = intent.action == Intent.ACTION_POWER_CONNECTED) }
    }

    private fun minuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
}
