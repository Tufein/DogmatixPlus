package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tufein.valkyr.util.RommRemoteInstall
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote install (5.6.0): the switch, the device token RomM gave this handheld and the device id. In `user_settings` like the other service logins; everything except the switch is kept
 * out of backup files ([RommRemoteInstall.SECRETS]) and, being a RomM token, out of problem reports (DiagnosticsRedactor
 * knows the `rmm_` prefix). It is separate from the "RomM token" of Settings → RomM, which is what the rest of the
 * app uses: RomM only lets a token that is bound to a device claim installs.
 */
@Singleton
class RommDeviceSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val enabledKey = booleanPreferencesKey(RommRemoteInstall.ENABLED_KEY)
    private val tokenKey = stringPreferencesKey(RommRemoteInstall.DEVICE_TOKEN_KEY)
    private val deviceKey = stringPreferencesKey(RommRemoteInstall.DEVICE_ID_KEY)
    private val identifierKey = stringPreferencesKey(RommRemoteInstall.DEVICE_IDENTIFIER_KEY)

    val enabled: Flow<Boolean> = context.dataStore.data.map { it[enabledKey] ?: false }.distinctUntilChanged()

    /** Token and device id of the pairing; both blank until the handheld is paired. */
    data class Pairing(val token: String = "", val deviceId: String = "") {
        val paired: Boolean get() = token.isNotBlank() && deviceId.isNotBlank()
    }

    val pairing: Flow<Pairing> = context.dataStore.data.map { Pairing(it[tokenKey].orEmpty(), it[deviceKey].orEmpty()) }.distinctUntilChanged()

    suspend fun setEnabled(on: Boolean) { context.dataStore.edit { it[enabledKey] = on } }

    suspend fun savePairing(token: String, deviceId: String) {
        context.dataStore.edit { it[tokenKey] = token; it[deviceKey] = deviceId }
    }

    /** Forgets the token and the device id (the server keeps the device until it is removed in RomM). */
    suspend fun clearPairing() {
        context.dataStore.edit { it.remove(tokenKey); it.remove(deviceKey) }
    }

    /**
     * A random id of this installation that RomM uses to know the device again when it is paired a second time
     * (made once; nothing about the hardware or the owner goes into it).
     */
    suspend fun identifier(): String {
        context.dataStore.data.first()[identifierKey]?.takeIf { it.isNotBlank() }?.let { return it }
        var made = ""
        context.dataStore.edit { prefs ->
            made = prefs[identifierKey]?.takeIf { it.isNotBlank() } ?: ("valkyr-" + UUID.randomUUID()).also { prefs[identifierKey] = it }
        }
        return made
    }
}
