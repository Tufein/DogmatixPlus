package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.tufein.valkyr.BuildConfig
import com.tufein.valkyr.data.service.CoverSourceStats
import com.tufein.valkyr.util.CoverApis
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
 * of problem reports (DiagnosticsService.secrets). 4.4.0: the API keys of TheGamesDB, SteamGridDB and
 * IGDB ([CoverApis]) live here too, with the same rules (only the Twitch client id may be backed up).
 */
@Singleton
class CoverSourceSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val stats: CoverSourceStats
) {

    private val orderKey = stringPreferencesKey(CoverSources.ORDER_KEY)
    private val disabledKey = stringSetPreferencesKey(CoverSources.DISABLED_KEY)
    private val nonBoxKey = booleanPreferencesKey(CoverSources.NON_BOX_KEY)
    private val devIdKey = stringPreferencesKey(ScreenScraper.DEV_ID_KEY)
    private val devPasswordKey = stringPreferencesKey(ScreenScraper.DEV_PASSWORD_KEY)
    private val softNameKey = stringPreferencesKey(ScreenScraper.SOFT_NAME_KEY)
    private val userKey = stringPreferencesKey(ScreenScraper.USER_KEY)
    private val passwordKey = stringPreferencesKey(ScreenScraper.PASSWORD_KEY)
    private val tgdbKey = stringPreferencesKey(CoverApis.TGDB_KEY)
    private val sgdbKey = stringPreferencesKey(CoverApis.SGDB_KEY)
    private val igdbIdKey = stringPreferencesKey(CoverApis.IGDB_CLIENT_ID_KEY)
    private val igdbSecretKey = stringPreferencesKey(CoverApis.IGDB_CLIENT_SECRET_KEY)

    val chain: Flow<CoverSources.Chain> = context.dataStore.data.map {
        CoverSources.Chain(
            order = CoverSources.orderOf(it[orderKey]),
            disabled = CoverSources.disabledOf(it[disabledKey], withKey(it)),
            nonBoxArt = it[nonBoxKey] ?: false
        )
    }.distinctUntilChanged()

    val credentials: Flow<ScreenScraper.Credentials> = context.dataStore.data.map(::credentialsOf).distinctUntilChanged()

    /** The API keys of the 4.4.0 sources (and the TheGamesDB key the app was built with, see [CoverApis.Keys]). */
    val apiKeys: Flow<CoverApis.Keys> = context.dataStore.data.map(::keysOf).distinctUntilChanged()

    /** The software name as the user typed it ("" = the default is sent), for the settings field. */
    val softNameTyped: Flow<String> = context.dataStore.data.map { it[softNameKey].orEmpty() }.distinctUntilChanged()

    private fun credentialsOf(p: Preferences) = ScreenScraper.Credentials(
        devId = p[devIdKey].orEmpty(),
        devPassword = p[devPasswordKey].orEmpty(),
        softName = p[softNameKey].orEmpty(),
        user = p[userKey].orEmpty(),
        password = p[passwordKey].orEmpty()
    )

    private fun withKey(p: Preferences): Set<CoverSources.Source> = keysOf(p).let { k -> CoverApis.OWN.filter { s -> k.ownConfigured(s) }.toSet() }

    private fun keysOf(p: Preferences) = CoverApis.Keys(
        tgdb = p[tgdbKey].orEmpty(),
        sgdb = p[sgdbKey].orEmpty(),
        igdbId = p[igdbIdKey].orEmpty(),
        igdbSecret = p[igdbSecretKey].orEmpty(),
        tgdbBuiltIn = BuildConfig.THEGAMESDB_API_KEY
    )

    suspend fun move(source: CoverSources.Source, delta: Int) = context.dataStore.edit {
        it[orderKey] = CoverSources.encodeOrder(CoverSources.move(CoverSources.orderOf(it[orderKey]), source, delta))
    }

    suspend fun setEnabled(source: CoverSources.Source, on: Boolean) = context.dataStore.edit {
        val now = CoverSources.disabledOf(it[disabledKey], withKey(it))
        it[disabledKey] = (if (on) now - source else now + source).map { s -> s.key }.toSet()
    }

    suspend fun setNonBoxArt(on: Boolean) = context.dataStore.edit { it[nonBoxKey] = on }

    enum class Field { DEV_ID, DEV_PASSWORD, SOFT_NAME, USER, PASSWORD, TGDB_KEY, SGDB_KEY, IGDB_CLIENT_ID, IGDB_CLIENT_SECRET }

    suspend fun set(field: Field, value: String) = context.dataStore.edit {
        val key = when (field) {
            Field.DEV_ID -> devIdKey
            Field.DEV_PASSWORD -> devPasswordKey
            Field.SOFT_NAME -> softNameKey
            Field.USER -> userKey
            Field.PASSWORD -> passwordKey
            Field.TGDB_KEY -> tgdbKey
            Field.SGDB_KEY -> sgdbKey
            Field.IGDB_CLIENT_ID -> igdbIdKey
            Field.IGDB_CLIENT_SECRET -> igdbSecretKey
        }
        val keysBefore = keysOf(it)
        // Passwords are taken as typed (a space may belong to one); names, ids and API keys lose stray spaces.
        it[key] = if (field == Field.DEV_PASSWORD || field == Field.PASSWORD) value else value.trim()
        // The first key of a 4.4.0 source switches that source on (they start off).
        val setUp = keysOf(it).newlyConfigured(keysBefore)
        if (setUp.isNotEmpty()) it[disabledKey] = (CoverSources.disabledOf(it[disabledKey], withKey(it)) - setUp).map { s -> s.key }.toSet()
    }.also {
        // A new key starts clean: the rest and the problem of the old one are forgotten.
        when (field) {
            Field.TGDB_KEY -> CoverSources.Source.THEGAMESDB
            Field.SGDB_KEY -> CoverSources.Source.STEAMGRIDDB
            Field.IGDB_CLIENT_ID, Field.IGDB_CLIENT_SECRET -> CoverSources.Source.IGDB
            else -> null
        }?.let { s -> stats.pauseSource(s, 0); stats.sourceProblem(s, null) }
    }
}
