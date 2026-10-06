package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 7.0 better versions: the suggestions the user dismissed ("Ignore this suggestion"), as
 * `BetterVersions.ignoreKey`s: this file on the device, that file offered. Same preferences file
 * as the other settings, so backups carry them.
 */
@Singleton
class BetterVersionsSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val IGNORED = stringSetPreferencesKey("upg7_better_ignored")
    }

    val ignored: Flow<Set<String>> = context.dataStore.data.map { it[Keys.IGNORED] ?: emptySet() }

    suspend fun ignore(key: String) {
        context.dataStore.edit { it[Keys.IGNORED] = (it[Keys.IGNORED] ?: emptySet()) + key }
    }

    /** Brings every dismissed suggestion back. */
    suspend fun clear() {
        context.dataStore.edit { it.remove(Keys.IGNORED) }
    }
}
