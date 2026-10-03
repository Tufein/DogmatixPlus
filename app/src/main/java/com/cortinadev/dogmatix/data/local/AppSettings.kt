package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The settings added in 2.0 (same preferences file as [SettingsDataStore]). */
@Singleton
class AppSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val AUTO_SCAN = booleanPreferencesKey("auto_scan")
        val AUTO_SCAN_HOURS = intPreferencesKey("auto_scan_hours")
        val AUTO_SCAN_WIFI = booleanPreferencesKey("auto_scan_wifi")
        val AUTO_SCAN_CHARGING = booleanPreferencesKey("auto_scan_charging")
        val AUTO_SCAN_NIGHT = booleanPreferencesKey("auto_scan_night")
        val AUTO_SCAN_LAST = longPreferencesKey("auto_scan_last")
        val SPEED_LIMIT_DAY_ONLY = booleanPreferencesKey("speed_limit_day_only")
        val BOLD_FOCUS = booleanPreferencesKey("bold_focus")
    }

    /** Scan the sources by itself now and then (see [com.cortinadev.dogmatix.data.service.AutoScanScheduler]). */
    val autoScan: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_SCAN] ?: false }
    val autoScanHours: Flow<Int> = context.dataStore.data.map { it[Keys.AUTO_SCAN_HOURS] ?: 24 }
    val autoScanWifiOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_SCAN_WIFI] ?: true }
    val autoScanCharging: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_SCAN_CHARGING] ?: true }
    /** Only inside the night window of Settings → Downloads. */
    val autoScanNightOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_SCAN_NIGHT] ?: true }
    val autoScanLast: Flow<Long> = context.dataStore.data.map { it[Keys.AUTO_SCAN_LAST] ?: 0L }
    /** The speed limit does not apply inside the night window. */
    val speedLimitDayOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.SPEED_LIMIT_DAY_ONLY] ?: false }
    /** A thicker, high-contrast focus ring for TV / handheld use. */
    val boldFocus: Flow<Boolean> = context.dataStore.data.map { it[Keys.BOLD_FOCUS] ?: false }

    suspend fun setAutoScan(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCAN] = on }
    suspend fun setAutoScanHours(hours: Int) = context.dataStore.edit { it[Keys.AUTO_SCAN_HOURS] = hours }
    suspend fun setAutoScanWifiOnly(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCAN_WIFI] = on }
    suspend fun setAutoScanCharging(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCAN_CHARGING] = on }
    suspend fun setAutoScanNightOnly(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCAN_NIGHT] = on }
    suspend fun setAutoScanLast(at: Long) = context.dataStore.edit { it[Keys.AUTO_SCAN_LAST] = at }
    suspend fun setSpeedLimitDayOnly(on: Boolean) = context.dataStore.edit { it[Keys.SPEED_LIMIT_DAY_ONLY] = on }
    suspend fun setBoldFocus(on: Boolean) = context.dataStore.edit { it[Keys.BOLD_FOCUS] = on }
}
