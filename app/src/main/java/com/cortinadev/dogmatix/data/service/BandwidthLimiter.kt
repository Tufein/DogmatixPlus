package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.SpeedLimit
import com.cortinadev.dogmatix.util.TokenBucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The download speed limit of Settings, for all downloads together: web downloads share one
 * [TokenBucket], and the torrent session gets the same limit as its own rate limit. The limit is
 * read again when a setting changes and every minute (for "no limit at night").
 */
@Singleton
class BandwidthLimiter @Inject constructor(
    settingsRepository: SettingsRepository,
    appSettings: AppSettings,
    private val torrents: TorrentHandleRegistry
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bucket = TokenBucket()
    private val lock = Mutex()

    @Volatile var bytesPerSecond: Long = 0
        private set

    init {
        val minuteTicks = flow { while (true) { emit(minuteOfDay()); delay(60_000) } }
        scope.launch {
            combine(
                settingsRepository.limitSpeed, appSettings.speedLimitDayOnly,
                settingsRepository.downloadNightStart, settingsRepository.downloadNightEnd, minuteTicks
            ) { limit, dayOnly, start, end, minute -> SpeedLimit.effectiveBytesPerSecond(limit, dayOnly, minute, start, end) }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
    }

    private suspend fun apply(limit: Long) {
        bytesPerSecond = limit
        lock.withLock { bucket.bytesPerSecond = limit }
        runCatching { torrents.setDownloadRateLimit(limit) }.onFailure { Log.w("BandwidthLimiter", "Torrent rate limit not applied: ${it.message}") }
    }

    /** Waits as long as [bytes] more would take under the limit; returns at once without one. */
    suspend fun acquire(bytes: Int) {
        if (bytesPerSecond <= 0) return
        val wait = lock.withLock { bucket.take(bytes) }
        if (wait > 0) delay(wait)
    }

    private fun minuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
}
