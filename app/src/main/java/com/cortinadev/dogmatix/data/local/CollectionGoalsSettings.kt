package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 6.0 collection goals: the consoles the user wants to complete (same preferences file, so backups carry them). */
@Singleton
class CollectionGoalsSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val GOALS = stringSetPreferencesKey("coll6_goal_consoles")
    }

    /** Console ids marked as a goal. */
    val goals: Flow<Set<String>> = context.dataStore.data.map { it[Keys.GOALS] ?: emptySet() }

    suspend fun toggle(consoleId: String) {
        context.dataStore.edit { prefs ->
            val now = prefs[Keys.GOALS] ?: emptySet()
            prefs[Keys.GOALS] = if (consoleId in now) now - consoleId else now + consoleId
        }
    }
}
