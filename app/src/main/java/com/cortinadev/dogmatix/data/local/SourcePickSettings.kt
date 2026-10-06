package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 7.5 "Pick the best source", in the shared preferences file (backups carry it). */
@Singleton
class SourcePickSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val key = booleanPreferencesKey("pick_best_source")

    /**
     * On (the default): a game several sources list comes from the one that has worked best, and
     * a download that fails for good tries the next-best source once.
     */
    val pickBest: Flow<Boolean> = context.dataStore.data.map { it[key] ?: true }

    suspend fun setPickBest(on: Boolean) = context.dataStore.edit { it[key] = on }
}
