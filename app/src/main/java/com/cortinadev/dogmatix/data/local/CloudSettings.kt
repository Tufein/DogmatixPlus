package com.cortinadev.dogmatix.data.local

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cortinadev.dogmatix.util.CertTrust
import com.cortinadev.dogmatix.util.CloudBackupNames
import com.cortinadev.dogmatix.util.CloudSettingKeys
import com.cortinadev.dogmatix.util.SharedWishlistEngine
import com.cortinadev.dogmatix.util.SharedWishlistJson
import com.cortinadev.dogmatix.util.WebDavPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What belongs to this handheld only and must survive restoring a backup made on another one:
 * its name and id, the backup passphrase and the last results. Its own file, so neither a backup
 * nor a restore (which clears `user_settings`) ever touches it.
 */
private val Context.davDeviceStore by preferencesDataStore(name = "dav_device")

/** The WebDAV connection as the client needs it. */
data class CloudConfig(
    val server: String = "",
    val user: String = "",
    val password: String = "",
    val folder: String = WebDavPaths.DEFAULT_FOLDER,
    /** SHA-256 of the server certificate the user confirmed; empty = only certificates Android trusts. */
    val trustFingerprint: String = ""
) {
    /** The app's folder on the server (ends in `/`); null while no usable address is set. */
    val rootUrl: String? get() = if (server.isBlank()) null else WebDavPaths.folderUrl(server, folder)
    val isConfigured: Boolean get() = rootUrl != null
}

/** The cloud settings that travel with a backup, as one value (see [CloudSettings.snapshot]). */
data class CloudSyncedSettings(
    val server: String,
    val user: String,
    val password: String,
    val folder: String,
    val autoBackup: Boolean,
    val keep: Int,
    val deviceSync: Boolean,
    val trustFingerprint: String = "",
    val sharedList: String = "",
    val sharedName: String = ""
)

/** The outcome of the last backup, connection test or device sync on this device. */
data class CloudRecords(
    val lastBackupAt: Long = 0L,
    val lastBackupName: String = "",
    val lastBackupBytes: Long = 0L,
    /** Message of the last failed backup, when it failed after the last success. */
    val lastBackupError: String = "",
    val lastBackupErrorAt: Long = 0L,
    val lastTestAt: Long = 0L,
    /** Empty when the last connection test succeeded. */
    val lastTestError: String = "",
    /** Fingerprint of the settings the last test was made with (see CloudConnection). */
    val lastTestKey: String = "",
    val lastSyncAt: Long = 0L,
    val lastSyncAdded: Int = 0,
    val lastSyncRemoved: Int = 0,
    val lastSyncSent: Boolean = false,
    val lastSyncError: String = "",
    val lastSyncErrorAt: Long = 0L,
    /** Removals held back by the last sync until the user confirms (0 = none). */
    val syncHeldBack: Int = 0,
    /** The last sync of the shared wishlist (6.0). */
    val lastSharedAt: Long = 0L,
    val lastSharedAdded: Int = 0,
    val lastSharedRemoved: Int = 0,
    val lastSharedSent: Boolean = false,
    val lastSharedError: String = "",
    val lastSharedErrorAt: Long = 0L
)

/**
 * 5.0 WebDAV cloud: backup to the user's own server and device sync between handhelds.
 *
 * The connection, folder and switches live in `user_settings` like every other setting, so a
 * backup carries them (the app password is treated like the RomM token: kept in the backup file,
 * redacted from diagnostics). The **backup passphrase**, the device's name and id and the last
 * results live in a separate file on this device only: the key to the encrypted backups must not
 * sit in plain text inside every backup, and a restore must not give this handheld another
 * device's name.
 */
@Singleton
class CloudSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val URL = stringPreferencesKey(CloudSettingKeys.URL)
        val USER = stringPreferencesKey(CloudSettingKeys.USER)
        val PASSWORD = stringPreferencesKey(CloudSettingKeys.PASSWORD)
        val FOLDER = stringPreferencesKey(CloudSettingKeys.FOLDER)
        val AUTO_BACKUP = booleanPreferencesKey(CloudSettingKeys.AUTO_BACKUP)
        val KEEP = intPreferencesKey(CloudSettingKeys.KEEP)
        val DEVICE_SYNC = booleanPreferencesKey(CloudSettingKeys.DEVICE_SYNC)
        val TRUST = stringPreferencesKey(CloudSettingKeys.TRUST_FINGERPRINT)
        val SHARED_LIST = stringPreferencesKey(CloudSettingKeys.SHARED_LIST)
        val SHARED_NAME = stringPreferencesKey(CloudSettingKeys.SHARED_NAME)
    }

    private object LocalKeys {
        val PASSPHRASE = stringPreferencesKey("dav_passphrase")
        val DEVICE_ID = stringPreferencesKey("dav_device_id")
        val DEVICE_NAME = stringPreferencesKey("dav_device_name")
        val LAST_BACKUP_AT = longPreferencesKey("dav_last_backup_at")
        val LAST_BACKUP_NAME = stringPreferencesKey("dav_last_backup_name")
        val LAST_BACKUP_BYTES = longPreferencesKey("dav_last_backup_bytes")
        val LAST_BACKUP_ERROR = stringPreferencesKey("dav_last_backup_error")
        val LAST_BACKUP_ERROR_AT = longPreferencesKey("dav_last_backup_error_at")
        val LAST_TEST_AT = longPreferencesKey("dav_last_test_at")
        val LAST_TEST_ERROR = stringPreferencesKey("dav_last_test_error")
        val LAST_TEST_KEY = stringPreferencesKey("dav_last_test_key")
        val LAST_SYNC_AT = longPreferencesKey("dav_last_sync_at")
        val LAST_SYNC_ADDED = intPreferencesKey("dav_last_sync_added")
        val LAST_SYNC_REMOVED = intPreferencesKey("dav_last_sync_removed")
        val LAST_SYNC_SENT = booleanPreferencesKey("dav_last_sync_sent")
        val LAST_SYNC_ERROR = stringPreferencesKey("dav_last_sync_error")
        val LAST_SYNC_ERROR_AT = longPreferencesKey("dav_last_sync_error_at")
        val SYNC_HELD_BACK = intPreferencesKey("dav_sync_held_back")
        val LAST_SHARED_AT = longPreferencesKey("dav_last_shared_at")
        val LAST_SHARED_ADDED = intPreferencesKey("dav_last_shared_added")
        val LAST_SHARED_REMOVED = intPreferencesKey("dav_last_shared_removed")
        val LAST_SHARED_SENT = booleanPreferencesKey("dav_last_shared_sent")
        val LAST_SHARED_ERROR = stringPreferencesKey("dav_last_shared_error")
        val LAST_SHARED_ERROR_AT = longPreferencesKey("dav_last_shared_error_at")
    }

    private val local get() = context.davDeviceStore

    // ---- Synced with backups (user_settings) --------------------------------------------------

    val server: Flow<String> = context.dataStore.data.map { it[Keys.URL] ?: "" }
    val user: Flow<String> = context.dataStore.data.map { it[Keys.USER] ?: "" }
    /** The (app) password for the WebDAV login. Secret: never logged, redacted from diagnostics. */
    val password: Flow<String> = context.dataStore.data.map { it[Keys.PASSWORD] ?: "" }
    val folder: Flow<String> = context.dataStore.data.map { it[Keys.FOLDER] ?: WebDavPaths.DEFAULT_FOLDER }
    /** Daily encrypted backup to the server (on Wi-Fi, while charging). */
    val autoBackup: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_BACKUP] ?: false }
    /** How many backups of this device stay in the cloud. */
    val keep: Flow<Int> = context.dataStore.data.map { it[Keys.KEEP] ?: CloudBackupNames.DEFAULT_KEEP }
    /** Keep favourites, wishlist and collections in step with the other devices. */
    val deviceSync: Flow<Boolean> = context.dataStore.data.map { it[Keys.DEVICE_SYNC] ?: false }

    /** Name of the shared family wishlist (6.0); empty = off. */
    val sharedList: Flow<String> = context.dataStore.data.map { it[Keys.SHARED_LIST] ?: "" }
    /** The name this person goes by on the shared wishlist; empty = the device name. */
    val sharedName: Flow<String> = context.dataStore.data.map { it[Keys.SHARED_NAME] ?: "" }

    /** The certificate the user confirmed for the server (see [CertTrust]); empty when none. */
    val trustFingerprint: Flow<String> = context.dataStore.data.map { it[Keys.TRUST] ?: "" }

    val config: Flow<CloudConfig> = context.dataStore.data.map { p ->
        CloudConfig(
            server = p[Keys.URL] ?: "",
            user = p[Keys.USER] ?: "",
            password = p[Keys.PASSWORD] ?: "",
            folder = p[Keys.FOLDER] ?: WebDavPaths.DEFAULT_FOLDER,
            trustFingerprint = p[Keys.TRUST] ?: ""
        )
    }.distinctUntilChanged()

    /** A server address is set. */
    val configured: Flow<Boolean> = config.map { it.isConfigured }.distinctUntilChanged()

    /** Device sync is switched on and has a server. */
    val deviceSyncActive: Flow<Boolean> = combine(configured, deviceSync) { c, on -> c && on }.distinctUntilChanged()

    /** The shared wishlist is switched on (a list name is set) and there is a server. */
    val sharedActive: Flow<Boolean> = combine(configured, sharedList) { c, name -> c && SharedWishlistEngine.folderName(name) != null }.distinctUntilChanged()

    /**
     * A new address also forgets the certificate confirmed for the old one. A `user:password@` in
     * front of the host is dropped (the login has its own fields; the address is logged and backed up).
     */
    suspend fun setServer(value: String) = context.dataStore.edit {
        val old = it[Keys.URL].orEmpty()
        val clean = WebDavPaths.stripCredentials(value)
        it[Keys.URL] = clean
        if (originOf(old) != originOf(clean)) it.remove(Keys.TRUST)
    }
    suspend fun setUser(value: String) = context.dataStore.edit { it[Keys.USER] = value.trim() }
    suspend fun setPassword(value: String) = context.dataStore.edit { it[Keys.PASSWORD] = value }
    suspend fun setFolder(value: String) = context.dataStore.edit {
        it[Keys.FOLDER] = WebDavPaths.folderSegments(value).joinToString("/").ifEmpty { WebDavPaths.DEFAULT_FOLDER }
    }
    suspend fun setAutoBackup(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_BACKUP] = on }
    suspend fun setKeep(value: Int) = context.dataStore.edit { it[Keys.KEEP] = value.coerceIn(CloudSettingKeys.MIN_KEEP, CloudSettingKeys.MAX_KEEP) }
    suspend fun setDeviceSync(on: Boolean) = context.dataStore.edit { it[Keys.DEVICE_SYNC] = on }
    suspend fun setSharedList(value: String) = context.dataStore.edit { it[Keys.SHARED_LIST] = value.trim().take(SharedWishlistEngine.MAX_LIST_NAME) }
    suspend fun setSharedName(value: String) = context.dataStore.edit { it[Keys.SHARED_NAME] = value.trim().take(SharedWishlistJson.MAX_NAME) }
    /** Trusts the server certificate with this SHA-256 fingerprint; empty forgets it. */
    suspend fun setTrustFingerprint(value: String) = context.dataStore.edit {
        if (CertTrust.isValid(value)) it[Keys.TRUST] = CertTrust.normalize(value) else it.remove(Keys.TRUST)
    }

    /** The settings above as they are now, to put back after a restore (see [restoreSnapshot]). */
    suspend fun snapshot(): CloudSyncedSettings {
        val p = context.dataStore.data.first()
        return CloudSyncedSettings(
            server = p[Keys.URL] ?: "",
            user = p[Keys.USER] ?: "",
            password = p[Keys.PASSWORD] ?: "",
            folder = p[Keys.FOLDER] ?: WebDavPaths.DEFAULT_FOLDER,
            autoBackup = p[Keys.AUTO_BACKUP] ?: false,
            keep = p[Keys.KEEP] ?: CloudBackupNames.DEFAULT_KEEP,
            deviceSync = p[Keys.DEVICE_SYNC] ?: false,
            trustFingerprint = p[Keys.TRUST] ?: "",
            sharedList = p[Keys.SHARED_LIST] ?: "",
            sharedName = p[Keys.SHARED_NAME] ?: ""
        )
    }

    suspend fun restoreSnapshot(s: CloudSyncedSettings) = context.dataStore.edit {
        it[Keys.URL] = s.server
        it[Keys.USER] = s.user
        it[Keys.PASSWORD] = s.password
        it[Keys.FOLDER] = s.folder
        it[Keys.AUTO_BACKUP] = s.autoBackup
        it[Keys.KEEP] = s.keep
        it[Keys.DEVICE_SYNC] = s.deviceSync
        if (s.trustFingerprint.isEmpty()) it.remove(Keys.TRUST) else it[Keys.TRUST] = s.trustFingerprint
        it[Keys.SHARED_LIST] = s.sharedList
        it[Keys.SHARED_NAME] = s.sharedName
    }

    // ---- This device only (dav_device) --------------------------------------------------------

    /** The passphrase the cloud backups are encrypted with. Secret; on this device only. */
    val passphrase: Flow<String> = local.data.map { it[LocalKeys.PASSPHRASE] ?: "" }
    val hasPassphrase: Flow<Boolean> = passphrase.map { it.isNotEmpty() }.distinctUntilChanged()

    suspend fun setPassphrase(value: String) = local.edit { it[LocalKeys.PASSPHRASE] = value }

    /** The name other devices and the backup list show for this one. */
    val deviceName: Flow<String> = local.data.map { it[LocalKeys.DEVICE_NAME]?.takeIf { n -> n.isNotBlank() } ?: defaultDeviceName() }

    suspend fun setDeviceName(value: String) = local.edit { it[LocalKeys.DEVICE_NAME] = value.trim().take(40) }

    /** A random id for this installation, made on first use (names can be the same on two devices). */
    suspend fun deviceId(): String {
        local.data.first()[LocalKeys.DEVICE_ID]?.takeIf { it.isNotBlank() }?.let { return it }
        var id = ""
        local.edit { prefs ->
            id = prefs[LocalKeys.DEVICE_ID]?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also { prefs[LocalKeys.DEVICE_ID] = it }
        }
        return id
    }

    val records: Flow<CloudRecords> = local.data.map(::recordsOf).distinctUntilChanged()

    suspend fun recordBackup(at: Long, name: String, bytes: Long) = local.edit {
        it[LocalKeys.LAST_BACKUP_AT] = at
        it[LocalKeys.LAST_BACKUP_NAME] = name
        it[LocalKeys.LAST_BACKUP_BYTES] = bytes
        it[LocalKeys.LAST_BACKUP_ERROR] = ""
        it[LocalKeys.LAST_BACKUP_ERROR_AT] = 0L
    }

    suspend fun recordBackupError(at: Long, message: String) = local.edit {
        it[LocalKeys.LAST_BACKUP_ERROR] = message.take(300)
        it[LocalKeys.LAST_BACKUP_ERROR_AT] = at
    }

    /** [key] identifies the settings that were tested; [error] is null when the test passed. */
    suspend fun recordTest(at: Long, error: String?, key: String) = local.edit {
        it[LocalKeys.LAST_TEST_AT] = at
        it[LocalKeys.LAST_TEST_ERROR] = error?.take(300).orEmpty()
        it[LocalKeys.LAST_TEST_KEY] = key
    }

    suspend fun recordSync(at: Long, added: Int, removed: Int, sent: Boolean) = local.edit {
        it[LocalKeys.LAST_SYNC_AT] = at
        it[LocalKeys.LAST_SYNC_ADDED] = added
        it[LocalKeys.LAST_SYNC_REMOVED] = removed
        it[LocalKeys.LAST_SYNC_SENT] = sent
        it[LocalKeys.LAST_SYNC_ERROR] = ""
        it[LocalKeys.LAST_SYNC_ERROR_AT] = 0L
        it[LocalKeys.SYNC_HELD_BACK] = 0
    }

    suspend fun recordSyncError(at: Long, message: String) = local.edit {
        it[LocalKeys.LAST_SYNC_ERROR] = message.take(300)
        it[LocalKeys.LAST_SYNC_ERROR_AT] = at
    }

    suspend fun recordShared(at: Long, added: Int, removed: Int, sent: Boolean) = local.edit {
        it[LocalKeys.LAST_SHARED_AT] = at
        it[LocalKeys.LAST_SHARED_ADDED] = added
        it[LocalKeys.LAST_SHARED_REMOVED] = removed
        it[LocalKeys.LAST_SHARED_SENT] = sent
        it[LocalKeys.LAST_SHARED_ERROR] = ""
        it[LocalKeys.LAST_SHARED_ERROR_AT] = 0L
    }

    suspend fun recordSharedError(at: Long, message: String) = local.edit {
        it[LocalKeys.LAST_SHARED_ERROR] = message.take(300)
        it[LocalKeys.LAST_SHARED_ERROR_AT] = at
    }

    suspend fun recordSyncHeldBack(removals: Int) = local.edit { it[LocalKeys.SYNC_HELD_BACK] = removals }

    private fun recordsOf(p: Preferences) = CloudRecords(
        lastBackupAt = p[LocalKeys.LAST_BACKUP_AT] ?: 0L,
        lastBackupName = p[LocalKeys.LAST_BACKUP_NAME] ?: "",
        lastBackupBytes = p[LocalKeys.LAST_BACKUP_BYTES] ?: 0L,
        lastBackupError = p[LocalKeys.LAST_BACKUP_ERROR] ?: "",
        lastBackupErrorAt = p[LocalKeys.LAST_BACKUP_ERROR_AT] ?: 0L,
        lastTestAt = p[LocalKeys.LAST_TEST_AT] ?: 0L,
        lastTestError = p[LocalKeys.LAST_TEST_ERROR] ?: "",
        lastTestKey = p[LocalKeys.LAST_TEST_KEY] ?: "",
        lastSyncAt = p[LocalKeys.LAST_SYNC_AT] ?: 0L,
        lastSyncAdded = p[LocalKeys.LAST_SYNC_ADDED] ?: 0,
        lastSyncRemoved = p[LocalKeys.LAST_SYNC_REMOVED] ?: 0,
        lastSyncSent = p[LocalKeys.LAST_SYNC_SENT] ?: false,
        lastSyncError = p[LocalKeys.LAST_SYNC_ERROR] ?: "",
        lastSyncErrorAt = p[LocalKeys.LAST_SYNC_ERROR_AT] ?: 0L,
        syncHeldBack = p[LocalKeys.SYNC_HELD_BACK] ?: 0,
        lastSharedAt = p[LocalKeys.LAST_SHARED_AT] ?: 0L,
        lastSharedAdded = p[LocalKeys.LAST_SHARED_ADDED] ?: 0,
        lastSharedRemoved = p[LocalKeys.LAST_SHARED_REMOVED] ?: 0,
        lastSharedSent = p[LocalKeys.LAST_SHARED_SENT] ?: false,
        lastSharedError = p[LocalKeys.LAST_SHARED_ERROR] ?: "",
        lastSharedErrorAt = p[LocalKeys.LAST_SHARED_ERROR_AT] ?: 0L
    )

    /** `https://host:port` of an address as typed (empty when it is none). */
    private fun originOf(address: String): String = WebDavPaths.normalizeServer(address)?.let { WebDavPaths.originOf(it) }.orEmpty()

    /** "Retroid Pocket 5": the name set in Android's settings, else the model. */
    private fun defaultDeviceName(): String {
        val system = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        return system?.trim()?.takeIf { it.isNotEmpty() } ?: Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "Android"
    }
}
