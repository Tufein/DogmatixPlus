package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.cortinadev.dogmatix.util.SmartStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 8.0 smart storage: whether it is on (off by default), the library folder on the SD card, how
 * many days count as "recently played", the weekly run, what it moved (so it only ever brings back
 * what it took, and lets a console rest after a move) and how the last run went. In the shared
 * preferences file, so backups carry it.
 */
@Singleton
class SmartStorageSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("smart_storage_enabled")
        val SD_URI = stringPreferencesKey("smart_storage_sd_uri")
        val DAYS = intPreferencesKey("smart_storage_recent_days")
        val WEEKLY = booleanPreferencesKey("smart_storage_weekly")
        val RECORDS = stringSetPreferencesKey("smart_storage_records")
        val LAST = stringPreferencesKey("smart_storage_last")
    }

    val enabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.ENABLED] ?: false }
    val sdUri: Flow<String> = context.dataStore.data.map { it[Keys.SD_URI].orEmpty() }
    val recentDays: Flow<Int> = context.dataStore.data.map { SmartStorage.clampDays(it[Keys.DAYS] ?: SmartStorage.DEFAULT_RECENT_DAYS) }
    /** The weekly run while charging; it only runs while smart storage is on. */
    val weekly: Flow<Boolean> = context.dataStore.data.map { it[Keys.WEEKLY] ?: true }
    val lastRun: Flow<SmartStorage.RunInfo?> = context.dataStore.data.map { SmartStorage.RunInfo.decode(it[Keys.LAST]) }

    /** consoleId → what smart storage did with it last. */
    val records: Flow<Map<String, SmartStorage.Record>> = context.dataStore.data.map { prefs ->
        prefs[Keys.RECORDS].orEmpty().mapNotNull { SmartStorage.Record.decode(it) }.associateBy { it.consoleId }
    }

    suspend fun setEnabled(on: Boolean) { context.dataStore.edit { it[Keys.ENABLED] = on } }
    suspend fun setSdUri(uri: String) { context.dataStore.edit { it[Keys.SD_URI] = uri } }
    suspend fun setRecentDays(days: Int) { context.dataStore.edit { it[Keys.DAYS] = SmartStorage.clampDays(days) } }
    suspend fun setWeekly(on: Boolean) { context.dataStore.edit { it[Keys.WEEKLY] = on } }
    suspend fun setLastRun(info: SmartStorage.RunInfo) { context.dataStore.edit { it[Keys.LAST] = info.encode() } }

    /** Replaces the record of [record]'s console. */
    suspend fun putRecord(record: SmartStorage.Record) {
        context.dataStore.edit { prefs ->
            val others = prefs[Keys.RECORDS].orEmpty().filterNot { SmartStorage.Record.decode(it)?.consoleId == record.consoleId }
            prefs[Keys.RECORDS] = others.toSet() + record.encode()
        }
    }

    /**
     * Points [record]'s console at its new folder ([folderUri]; empty = back to its folder in the
     * download folder) and stores the record, in one edit: the two can never disagree.
     */
    suspend fun switchConsole(record: SmartStorage.Record, folderUri: String) {
        context.dataStore.edit { prefs ->
            val dirs = prefs[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES].orEmpty().filterNot { it.startsWith("${record.consoleId}:") }.toMutableSet()
            if (folderUri.isNotEmpty()) dirs += "${record.consoleId}:$folderUri"
            prefs[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES] = dirs
            val others = prefs[Keys.RECORDS].orEmpty().filterNot { SmartStorage.Record.decode(it)?.consoleId == record.consoleId }
            prefs[Keys.RECORDS] = others.toSet() + record.encode()
        }
    }

    /** Forgets consoles whose folder was changed by hand since (smart storage leaves them alone from then on). */
    suspend fun dropRecords(consoleIds: Collection<String>) {
        if (consoleIds.isEmpty()) return
        context.dataStore.edit { prefs ->
            prefs[Keys.RECORDS] = prefs[Keys.RECORDS].orEmpty().filterNot { SmartStorage.Record.decode(it)?.consoleId in consoleIds }.toSet()
        }
    }

    suspend fun recordsNow(): Map<String, SmartStorage.Record> = records.first()
}
