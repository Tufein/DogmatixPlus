package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.tufein.valkyr.util.CoverSources
import com.tufein.valkyr.util.ScreenScraper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cover sources (4.3.0): the order and switches of the sources, whether title screens and snaps may
 * stand in for box art, and the ScreenScraper credentials. All in `user_settings`, like the other
 * service logins; the two passwords are kept out of backup files ([ScreenScraper.SECRETS]) and out
 * of problem reports (DiagnosticsService.secrets).
 */
@Singleton
class CoverSourceSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val orderKey = stringPreferencesKey(CoverSources.ORDER_KEY)
    private val disabledKey = stringSetPreferencesKey(CoverSources.DISABLED_KEY)
    private val nonBoxKey = booleanPreferencesKey(CoverSources.NON_BOX_KEY)
    private val devIdKey = stringPreferencesKey(ScreenScraper.DEV_ID_KEY)
    private val devPasswordKey = stringPreferencesKey(ScreenScraper.DEV_PASSWORD_KEY)
    private val softNameKey = stringPreferencesKey(ScreenScraper.SOFT_NAME_KEY)
    private val userKey = stringPreferencesKey(ScreenScraper.USER_KEY)
    private val passwordKey = stringPreferencesKey(ScreenScraper.PASSWORD_KEY)

    val chain: Flow<CoverSources.Chain> = context.dataStore.data.map {
        CoverSources.Chain(
            order = CoverSources.orderOf(it[orderKey]),
            disabled = CoverSources.disabledOf(it[disabledKey]),
            nonBoxArt = it[nonBoxKey] ?: false
        )
    }.distinctUntilChanged()

    val credentials: Flow<ScreenScraper.Credentials> = context.dataStore.data.map(::credentialsOf).distinctUntilChanged()

    /** The software name as the user typed it ("" = the default is sent), for the settings field. */
    val softNameTyped: Flow<String> = context.dataStore.data.map { it[softNameKey].orEmpty() }.distinctUntilChanged()

    private fun credentialsOf(p: Preferences) = ScreenScraper.Credentials(
        devId = p[devIdKey].orEmpty(),
        devPassword = p[devPasswordKey].orEmpty(),
        softName = p[softNameKey].orEmpty(),
        user = p[userKey].orEmpty(),
        password = p[passwordKey].orEmpty()
    )

    suspend fun move(source: CoverSources.Source, delta: Int) = context.dataStore.edit {
        it[orderKey] = CoverSources.encodeOrder(CoverSources.move(CoverSources.orderOf(it[orderKey]), source, delta))
    }

    suspend fun setEnabled(source: CoverSources.Source, on: Boolean) = context.dataStore.edit {
        val now = CoverSources.disabledOf(it[disabledKey])
        it[disabledKey] = (if (on) now - source else now + source).map { s -> s.key }.toSet()
    }

    suspend fun setNonBoxArt(on: Boolean) = context.dataStore.edit { it[nonBoxKey] = on }

    enum class Field { DEV_ID, DEV_PASSWORD, SOFT_NAME, USER, PASSWORD }

    suspend fun set(field: Field, value: String) = context.dataStore.edit {
        val key = when (field) {
            Field.DEV_ID -> devIdKey
            Field.DEV_PASSWORD -> devPasswordKey
            Field.SOFT_NAME -> softNameKey
            Field.USER -> userKey
            Field.PASSWORD -> passwordKey
        }
        // Passwords are taken as typed (a space may belong to one); names and ids lose stray spaces.
        it[key] = if (field == Field.DEV_PASSWORD || field == Field.PASSWORD) value else value.trim()
    }
}
