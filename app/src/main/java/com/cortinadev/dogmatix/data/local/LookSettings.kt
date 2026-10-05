package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 5.0 "Look" settings, in the same preferences file as the rest (so backups carry them). */
@Singleton
class LookSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val ANIMATIONS = booleanPreferencesKey("look_animations")
        val GLOW = booleanPreferencesKey("look_glow")
        val LIST_COVERS = booleanPreferencesKey("look_list_covers")
    }

    /** Off = every animation snaps (also follows the system's "remove animations"). */
    val animations: Flow<Boolean> = context.dataStore.data.map { it[Keys.ANIMATIONS] ?: true }
    /** The soft accent glow behind the screens. */
    val glow: Flow<Boolean> = context.dataStore.data.map { it[Keys.GLOW] ?: true }
    /** Small covers in front of the games in the library list. */
    val listCovers: Flow<Boolean> = context.dataStore.data.map { it[Keys.LIST_COVERS] ?: true }

    suspend fun setAnimations(on: Boolean) = context.dataStore.edit { it[Keys.ANIMATIONS] = on }
    suspend fun setGlow(on: Boolean) = context.dataStore.edit { it[Keys.GLOW] = on }
    suspend fun setListCovers(on: Boolean) = context.dataStore.edit { it[Keys.LIST_COVERS] = on }
}
