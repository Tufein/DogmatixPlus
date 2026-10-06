package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 7.0 "Descriptions for your launcher": which frontends receive game information and whether it
 * is written after every download. Kept in the shared preferences file, so backups carry it.
 */
@Singleton
class FrontendMetadataSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val AUTO = booleanPreferencesKey("meta7_auto")
        val ESDE = booleanPreferencesKey("meta7_esde")
        val PEGASUS = booleanPreferencesKey("meta7_pegasus")
    }

    /** After every finished download, write that game's information into the switched-on frontends. Off by default. */
    val autoWrite: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO] ?: false }

    /** Write ES-DE's `gamelist.xml` (needs the ES-DE folder of Settings → ES-DE). */
    val esde: Flow<Boolean> = context.dataStore.data.map { it[Keys.ESDE] ?: true }

    /** Write Pegasus' `metadata.txt` next to the games. */
    val pegasus: Flow<Boolean> = context.dataStore.data.map { it[Keys.PEGASUS] ?: false }

    suspend fun setAutoWrite(on: Boolean) = context.dataStore.edit { it[Keys.AUTO] = on }
    suspend fun setEsde(on: Boolean) = context.dataStore.edit { it[Keys.ESDE] = on }
    suspend fun setPegasus(on: Boolean) = context.dataStore.edit { it[Keys.PEGASUS] = on }
}
