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
        val BIOS_DIR = androidx.datastore.preferences.core.stringPreferencesKey("bios_dir")
        val MIN_FREE_GB = intPreferencesKey("min_free_gb")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val AUTO_BACKUP_DIR = androidx.datastore.preferences.core.stringPreferencesKey("auto_backup_dir")
        val AUTO_BACKUP_LAST = longPreferencesKey("auto_backup_last")
        val LIBRARY_VIEWS = androidx.datastore.preferences.core.stringPreferencesKey("library_views")
        val SECOND_SCREEN = booleanPreferencesKey("second_screen")
        // 3.0
        val RESUME_DOWNLOADS = booleanPreferencesKey("resume_downloads")
        val REQUEUE_AFTER_RESTART = booleanPreferencesKey("requeue_after_restart")
        val PER_SERVER_LIMIT = intPreferencesKey("per_server_limit")
        val ESDE_ARTWORK = booleanPreferencesKey("esde_artwork")
        val WISHLIST_AUTO_DOWNLOAD = booleanPreferencesKey("wishlist_auto_download")
        val RA_USER = androidx.datastore.preferences.core.stringPreferencesKey("ra_user")
        val RA_KEY = androidx.datastore.preferences.core.stringPreferencesKey("ra_api_key")
        val AUTO_M3U = booleanPreferencesKey("auto_m3u")
        val QUEUE_SUMMARY = booleanPreferencesKey("queue_summary")
        val SAVE_SYNC_EMULATOR_FOLDERS = androidx.datastore.preferences.core.stringPreferencesKey("save_sync_emulator_folders")
        val PROFILES = androidx.datastore.preferences.core.stringPreferencesKey("profiles")
        val ACTIVE_PROFILE = androidx.datastore.preferences.core.stringPreferencesKey("active_profile")
        val PROFILE_PIN = androidx.datastore.preferences.core.stringPreferencesKey("profile_pin_hash")
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

    /** SAF tree of the emulator's BIOS / system folder (RetroArch: `system`). */
    val biosDir: Flow<String> = context.dataStore.data.map { it[Keys.BIOS_DIR] ?: "" }
    /** Downloads wait (and running ones stop) below this much free space, in GB; 0 = off. */
    val minFreeGb: Flow<Int> = context.dataStore.data.map { it[Keys.MIN_FREE_GB] ?: 0 }
    val autoBackup: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_BACKUP] ?: false }
    val autoBackupDir: Flow<String> = context.dataStore.data.map { it[Keys.AUTO_BACKUP_DIR] ?: "" }
    val autoBackupLast: Flow<Long> = context.dataStore.data.map { it[Keys.AUTO_BACKUP_LAST] ?: 0L }
    /** Saved library filters as JSON (see [com.cortinadev.dogmatix.util.LibraryViews]). */
    val libraryViews: Flow<String> = context.dataStore.data.map { it[Keys.LIBRARY_VIEWS] ?: "[]" }
    /** Show game details / downloads on a second display when one is connected. */
    val secondScreen: Flow<Boolean> = context.dataStore.data.map { it[Keys.SECOND_SCREEN] ?: true }

    // ---- 3.0 ----------------------------------------------------------------------------------
    /** Continue an interrupted web download from where it stopped (when the server allows it). */
    val resumeDownloads: Flow<Boolean> = context.dataStore.data.map { it[Keys.RESUME_DOWNLOADS] ?: true }
    /** Downloads that were queued or running when the app closed start again by themselves. */
    val requeueAfterRestart: Flow<Boolean> = context.dataStore.data.map { it[Keys.REQUEUE_AFTER_RESTART] ?: true }
    /** At most this many downloads at once from one server; 0 = no limit. */
    val perServerLimit: Flow<Int> = context.dataStore.data.map { it[Keys.PER_SERVER_LIMIT] ?: 0 }
    /** After a download, save cover and description where ES-DE (and Cocoon's ES-DE link) read them. */
    val esdeArtwork: Flow<Boolean> = context.dataStore.data.map { it[Keys.ESDE_ARTWORK] ?: false }
    /** A wanted game that turns up in a source is downloaded straight away (best version). */
    val wishlistAutoDownload: Flow<Boolean> = context.dataStore.data.map { it[Keys.WISHLIST_AUTO_DOWNLOAD] ?: false }
    val raUser: Flow<String> = context.dataStore.data.map { it[Keys.RA_USER] ?: "" }
    /** RetroAchievements web API key (the user's own, from their RA control panel). */
    val raKey: Flow<String> = context.dataStore.data.map { it[Keys.RA_KEY] ?: "" }
    /** Write a .m3u playlist when a finished download completes a multi-disc game. */
    val autoM3u: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_M3U] ?: true }
    /** One notification when a run of downloads is done (see [com.cortinadev.dogmatix.data.service.QueueSummaryService]). */
    val queueSummary: Flow<Boolean> = context.dataStore.data.map { it[Keys.QUEUE_SUMMARY] ?: true }
    /** Saves folders of standalone emulators synced with RomM (see [com.cortinadev.dogmatix.util.EmulatorSaveFolder]). */
    val saveSyncEmulatorFolders: Flow<List<com.cortinadev.dogmatix.util.EmulatorSaveFolder>> =
        context.dataStore.data.map { com.cortinadev.dogmatix.util.EmulatorSaveFolders.fromJson(it[Keys.SAVE_SYNC_EMULATOR_FOLDERS]) }
    /** Profiles as JSON (see [com.cortinadev.dogmatix.util.Profiles]). */
    val profiles: Flow<String> = context.dataStore.data.map { it[Keys.PROFILES] ?: "[]" }
    /** Id of the active profile; empty = everything visible. */
    val activeProfile: Flow<String> = context.dataStore.data.map { it[Keys.ACTIVE_PROFILE] ?: "" }
    /** SHA-256 of the PIN that unlocks leaving a restricted profile; empty = no PIN. */
    val profilePinHash: Flow<String> = context.dataStore.data.map { it[Keys.PROFILE_PIN] ?: "" }

    suspend fun setResumeDownloads(on: Boolean) = context.dataStore.edit { it[Keys.RESUME_DOWNLOADS] = on }
    suspend fun setRequeueAfterRestart(on: Boolean) = context.dataStore.edit { it[Keys.REQUEUE_AFTER_RESTART] = on }
    suspend fun setPerServerLimit(n: Int) = context.dataStore.edit { it[Keys.PER_SERVER_LIMIT] = n.coerceIn(0, 10) }
    suspend fun setEsdeArtwork(on: Boolean) = context.dataStore.edit { it[Keys.ESDE_ARTWORK] = on }
    suspend fun setWishlistAutoDownload(on: Boolean) = context.dataStore.edit { it[Keys.WISHLIST_AUTO_DOWNLOAD] = on }
    suspend fun setRetroAchievements(user: String, key: String) = context.dataStore.edit { it[Keys.RA_USER] = user.trim(); it[Keys.RA_KEY] = key.trim() }
    suspend fun setAutoM3u(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_M3U] = on }
    suspend fun setQueueSummary(on: Boolean) = context.dataStore.edit { it[Keys.QUEUE_SUMMARY] = on }
    suspend fun setSaveSyncEmulatorFolders(folders: List<com.cortinadev.dogmatix.util.EmulatorSaveFolder>) =
        context.dataStore.edit { it[Keys.SAVE_SYNC_EMULATOR_FOLDERS] = com.cortinadev.dogmatix.util.EmulatorSaveFolders.toJson(folders) }
    suspend fun setProfiles(json: String) = context.dataStore.edit { it[Keys.PROFILES] = json }
    suspend fun setActiveProfile(id: String) = context.dataStore.edit { it[Keys.ACTIVE_PROFILE] = id }
    suspend fun setProfilePinHash(hash: String) = context.dataStore.edit { it[Keys.PROFILE_PIN] = hash }

    suspend fun setBiosDir(uri: String) = context.dataStore.edit { it[Keys.BIOS_DIR] = uri }
    suspend fun setMinFreeGb(gb: Int) = context.dataStore.edit { it[Keys.MIN_FREE_GB] = gb.coerceAtLeast(0) }
    suspend fun setAutoBackup(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_BACKUP] = on }
    suspend fun setAutoBackupDir(uri: String) = context.dataStore.edit { it[Keys.AUTO_BACKUP_DIR] = uri }
    suspend fun setAutoBackupLast(at: Long) = context.dataStore.edit { it[Keys.AUTO_BACKUP_LAST] = at }
    suspend fun setLibraryViews(json: String) = context.dataStore.edit { it[Keys.LIBRARY_VIEWS] = json }
    suspend fun setSecondScreen(on: Boolean) = context.dataStore.edit { it[Keys.SECOND_SCREEN] = on }

    suspend fun setAutoScan(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCAN] = on }
    suspend fun setAutoScanHours(hours: Int) = context.dataStore.edit { it[Keys.AUTO_SCAN_HOURS] = hours }
    suspend fun setAutoScanWifiOnly(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCAN_WIFI] = on }
    suspend fun setAutoScanCharging(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCAN_CHARGING] = on }
    suspend fun setAutoScanNightOnly(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCAN_NIGHT] = on }
    suspend fun setAutoScanLast(at: Long) = context.dataStore.edit { it[Keys.AUTO_SCAN_LAST] = at }
    suspend fun setSpeedLimitDayOnly(on: Boolean) = context.dataStore.edit { it[Keys.SPEED_LIMIT_DAY_ONLY] = on }
    suspend fun setBoldFocus(on: Boolean) = context.dataStore.edit { it[Keys.BOLD_FOCUS] = on }
}
