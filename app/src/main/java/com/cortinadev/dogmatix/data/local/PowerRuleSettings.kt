package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.cortinadev.dogmatix.util.PowerRules
import com.cortinadev.dogmatix.util.PowerSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 7.5 battery and heat rules (Settings → Download schedule), in the shared preferences file so backups carry them. */
@Singleton
class PowerRuleSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val LOW_BATTERY = booleanPreferencesKey("power_low_battery")
        val PERCENT = intPreferencesKey("power_battery_percent")
        val HEAT = booleanPreferencesKey("power_heat")
    }

    /** Pause downloads off the charger below [batteryPercent]. */
    val lowBatteryOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.LOW_BATTERY] ?: false }
    /** The battery limit in percent, 5..50 in steps of 5. */
    val batteryPercent: Flow<Int> = context.dataStore.data.map { PowerRules.clampPercent(it[Keys.PERCENT] ?: PowerRules.DEFAULT_PERCENT) }
    /** Pause downloads while the device is hot. */
    val heatOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.HEAT] ?: false }

    /** All three at once, for [com.cortinadev.dogmatix.data.service.PowerMonitor]. */
    val settings: Flow<PowerSettings> = context.dataStore.data.map {
        PowerSettings(
            lowBatteryOn = it[Keys.LOW_BATTERY] ?: false,
            batteryPercent = PowerRules.clampPercent(it[Keys.PERCENT] ?: PowerRules.DEFAULT_PERCENT),
            heatOn = it[Keys.HEAT] ?: false
        )
    }.distinctUntilChanged()

    suspend fun setLowBatteryOn(on: Boolean) = context.dataStore.edit { it[Keys.LOW_BATTERY] = on }
    suspend fun setBatteryPercent(percent: Int) = context.dataStore.edit { it[Keys.PERCENT] = PowerRules.clampPercent(percent) }
    /** Moves the limit by [delta] steps of 5 %. */
    suspend fun shiftBatteryPercent(delta: Int) = context.dataStore.edit {
        it[Keys.PERCENT] = PowerRules.shift(it[Keys.PERCENT] ?: PowerRules.DEFAULT_PERCENT, delta)
    }
    suspend fun setHeatOn(on: Boolean) = context.dataStore.edit { it[Keys.HEAT] = on }
}
