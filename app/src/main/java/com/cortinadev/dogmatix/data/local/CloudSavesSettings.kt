package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 5.0 cloud-saves settings, in the same preferences file as the rest (so backups carry them). */
@Singleton
class CloudSavesSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val CONTINUE_PLAYING = booleanPreferencesKey("csave_continue_playing")
    }

    /** The "Continue playing" shelf on Home: games last saved on any device (or last played in ES-DE). */
    val continuePlaying: Flow<Boolean> = context.dataStore.data.map { it[Keys.CONTINUE_PLAYING] ?: true }

    suspend fun setContinuePlaying(on: Boolean) = context.dataStore.edit { it[Keys.CONTINUE_PLAYING] = on }
}
